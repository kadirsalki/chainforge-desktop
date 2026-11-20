package dev.chainforge.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chainforge.core.ChainAdapter;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.rpc.Cluster;
import org.p2p.solanaj.rpc.RpcClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Native SOL transfers on Solana Devnet. Private key is a Base58-encoded 64-byte keypair.
 */
public final class SolanaAdapter implements ChainAdapter {
    private static final long LAMPORTS_PER_SOL = 1_000_000_000L;
    private static final URI DEVNET_RPC = URI.create("https://api.devnet.solana.com");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private RpcClient client() {
        return new RpcClient(Cluster.DEVNET);
    }

    /**
     * Read the raw JSON-RPC response instead of relying on Solanaj's loosely
     * typed SignatureStatuses.value list. This preserves err and
     * confirmationStatus exactly as returned by the Solana RPC.
     */
    private JsonNode querySignatureStatus(String signature) throws Exception {
        Map<String, Object> requestBody = Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "getSignatureStatuses",
                "params", List.of(
                        List.of(signature),
                        Map.of("searchTransactionHistory", true)
                )
        );
        HttpRequest request = HttpRequest.newBuilder(DEVNET_RPC)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(requestBody)))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Solana RPC HTTP " + response.statusCode());
        }
        JsonNode root = JSON.readTree(response.body());
        if (root.hasNonNull("error")) {
            throw new IllegalStateException("Solana RPC error: " + root.get("error"));
        }
        JsonNode values = root.path("result").path("value");
        if (!values.isArray() || values.isEmpty() || values.get(0).isNull()) return null;
        return values.get(0);
    }

    private StatusResult toStatusResult(JsonNode status) {
        if (status == null) return new StatusResult("PENDING", null, null);
        JsonNode err = status.get("err");
        if (err != null && !err.isNull()) {
            return new StatusResult("FAILED", null, "Solana on-chain error: " + err);
        }
        String confirmation = status.path("confirmationStatus").asText("");
        if ("confirmed".equalsIgnoreCase(confirmation) || "finalized".equalsIgnoreCase(confirmation)) {
            return new StatusResult("CONFIRMED", null, null);
        }
        // Older RPC nodes may omit confirmationStatus. A present slot with
        // confirmations=null represents a rooted/finalized transaction.
        if (confirmation.isBlank() && status.has("slot")
                && status.has("confirmations") && status.get("confirmations").isNull()) {
            return new StatusResult("CONFIRMED", null, null);
        }
        return new StatusResult("PENDING", null, null);
    }

    @Override
    public String family() {
        return "SOLANA";
    }

    @Override
    public String networkKey() {
        return "solana-devnet";
    }

    @Override
    public CompletableFuture<String> getBalance(String address, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!"SOL".equalsIgnoreCase(asset))
                    throw new IllegalArgumentException("Only native SOL is enabled on Devnet.");
                long lamports = client().getApi().getBalance(new PublicKey(address));
                return BigDecimal.valueOf(lamports).divide(BigDecimal.valueOf(LAMPORTS_PER_SOL), 9, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<StatusResult> checkTransactionStatus(String signature) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return toStatusResult(querySignatureStatus(signature));
            } catch (Exception e) {
                throw new RuntimeException("Could not query Solana transaction status: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<TransferResult> transfer(String privateKey, String to, BigDecimal amount, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!"SOL".equalsIgnoreCase(asset))
                    throw new IllegalArgumentException("Only native SOL is enabled on Devnet.");
                long lamports = amount.multiply(BigDecimal.valueOf(LAMPORTS_PER_SOL)).setScale(0, RoundingMode.DOWN).longValueExact();
                if (lamports <= 0) throw new IllegalArgumentException("Amount is below one lamport.");
                Account signer = new Account(SolanaKeys.decode(privateKey));
                PublicKey recipient = new PublicKey(to);
                Transaction tx = new Transaction();
                tx.addInstruction(SystemProgram.transfer(signer.getPublicKey(), recipient, lamports));
                RpcClient rpc = client();
                String signature = rpc.getApi().sendTransaction(tx, signer);
                // Poll the canonical signature-status RPC. Never mark a submitted tx as confirmed by assumption.
                long deadline = System.currentTimeMillis() + 45_000L;
                while (System.currentTimeMillis() < deadline) {
                    StatusResult status = toStatusResult(querySignatureStatus(signature));
                    if ("FAILED".equals(status.status())) {
                        throw new IllegalStateException(status.error());
                    }
                    if ("CONFIRMED".equals(status.status())) {
                        return new TransferResult(signature, "CONFIRMED", "network fee (lamports)");
                    }
                    Thread.sleep(1_000L);
                }
                return new TransferResult(signature, "PENDING", "confirmation not yet observed");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
