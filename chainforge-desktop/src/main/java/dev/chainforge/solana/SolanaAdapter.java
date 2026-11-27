package dev.chainforge.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chainforge.core.ChainAdapter;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.rpc.RpcClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Native SOL transfers on one configured Solana network (devnet, mainnet-beta, ...). Private key is a Base58-encoded 64-byte keypair.
 */
public final class SolanaAdapter implements ChainAdapter {
    private static final long LAMPORTS_PER_SOL = 1_000_000_000L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final String networkKey;
    private final String rpc;

    public SolanaAdapter(String networkKey, String rpc) {
        if (rpc == null || rpc.isBlank()) throw new IllegalArgumentException("Solana RPC URL is empty for " + networkKey);
        this.networkKey = networkKey;
        this.rpc = rpc.trim();
    }

    private RpcClient client() {
        return new RpcClient(rpc);
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(rpc))
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
        return networkKey;
    }

    @Override
    public CompletableFuture<String> getBalance(String address, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!"SOL".equalsIgnoreCase(asset))
                    throw new IllegalArgumentException("Only native SOL is enabled.");
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
                    throw new IllegalArgumentException("Only native SOL is enabled.");
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
                    StatusResult status;
                    try {
                        status = toStatusResult(querySignatureStatus(signature));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new TransferResult(signature, "PENDING", null, "Confirmation check interrupted");
                    } catch (Exception e) {
                        return new TransferResult(signature, "PENDING", null, "Confirmation unavailable: " + e.getMessage());
                    }
                    if ("FAILED".equals(status.status())) {
                        return new TransferResult(signature, "FAILED", status.fee(), status.error());
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

    private JsonNode rpcCall(String method, List<Object> params) throws Exception {
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params);
        HttpRequest request = HttpRequest.newBuilder(URI.create(rpc)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Solana RPC HTTP " + response.statusCode() + (response.statusCode() == 429 ? " (rate limited)" : ""));
        JsonNode root = JSON.readTree(response.body());
        if (root.hasNonNull("error")) throw new IllegalStateException("Solana RPC error: " + root.get("error"));
        return root.path("result");
    }

    public BigDecimal estimateBatchFee(int transfers) throws Exception {
        var commitment = Map.of("commitment", "confirmed");
        String blockhash = rpcCall("getLatestBlockhash", List.of(commitment)).path("value").path("blockhash").asText();
        if (blockhash.isBlank()) throw new IllegalStateException("Solana did not return a recent blockhash.");
        var message = new FeeQuoteMessage(blockhash);
        String encoded = java.util.Base64.getEncoder().encodeToString(message.serialize());
        var fee = rpcCall("getFeeForMessage", List.of(encoded, commitment)).path("value");
        if (!fee.isIntegralNumber() || fee.bigIntegerValue().signum() < 0)
            throw new IllegalStateException("Solana did not return a fee quote.");
        return new BigDecimal(fee.bigIntegerValue()).multiply(BigDecimal.valueOf(transfers)).movePointLeft(9);
    }

    // Match the one-signer native transfer without signing or unlocking the selected wallet.
    private static final class FeeQuoteMessage extends org.p2p.solanaj.core.Message {
        FeeQuoteMessage(String blockhash) {
            Account payer = new Account();
            setFeePayer(payer);
            addInstruction(SystemProgram.transfer(payer.getPublicKey(), new Account().getPublicKey(), 1));
            setRecentBlockHash(blockhash);
        }
    }

    /** Native SOL received by the address: recent signatures, then the account's lamport delta per transaction. */
    @Override
    public CompletableFuture<List<IncomingTx>> fetchIncoming(String address, Set<String> knownHashes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SolanaKeys.validateAddress(address);
                List<IncomingTx> out = new ArrayList<>();
                JsonNode sigs = rpcCall("getSignaturesForAddress", List.of(address, Map.of("limit", 20)));
                for (JsonNode s : sigs) {
                    String sig = s.path("signature").asText();
                    if (knownHashes.contains(sig) || (s.hasNonNull("err"))) continue;
                    Thread.sleep(250); // public RPC nodes rate-limit bursts
                    JsonNode tx = rpcCall("getTransaction", List.of(sig, Map.of("encoding", "jsonParsed", "maxSupportedTransactionVersion", 0)));
                    if (tx.isNull() || tx.path("meta").path("err").isObject()) continue;
                    JsonNode keys = tx.path("transaction").path("message").path("accountKeys");
                    int idx = -1;
                    String payer = null;
                    for (int i = 0; i < keys.size(); i++) {
                        String k = keys.get(i).isObject() ? keys.get(i).path("pubkey").asText() : keys.get(i).asText();
                        if (i == 0) payer = k;
                        if (k.equals(address)) idx = i;
                    }
                    if (idx < 0 || address.equals(payer)) continue; // not involved, or sent by this wallet
                    long delta = tx.path("meta").path("postBalances").path(idx).asLong() - tx.path("meta").path("preBalances").path(idx).asLong();
                    if (delta <= 0) continue;
                    String amount = BigDecimal.valueOf(delta).divide(BigDecimal.valueOf(LAMPORTS_PER_SOL), 9, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
                    long bt = tx.path("blockTime").asLong(0);
                    out.add(new IncomingTx(sig, payer, address, amount, NATIVE, "CONFIRMED", null, bt > 0 ? ChainAdapter.utc(bt) : null));
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
