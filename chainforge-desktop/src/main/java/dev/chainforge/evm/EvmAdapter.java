package dev.chainforge.evm;

import dev.chainforge.core.ChainAdapter;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * EVM native coin and ERC-20 adapter for one configured network (Sepolia, Ethereum mainnet, ...).
 * Token asset syntax: ERC20:<contract-address>.
 */
public final class EvmAdapter implements ChainAdapter {
    private static final BigInteger PLAIN_TRANSFER_GAS = BigInteger.valueOf(21_000);

    private final String networkKey;
    private final String rpc;
    private final long chainId;
    /** Etherscan-compatible account-history API (JSON-RPC cannot list the transfers received by an address). */
    private volatile String indexer;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public EvmAdapter(String networkKey, String rpc, long chainId) {
        if (rpc == null || rpc.isBlank()) throw new IllegalArgumentException("EVM RPC URL is empty for " + networkKey);
        this.networkKey = networkKey;
        this.rpc = rpc.trim();
        this.chainId = chainId;
    }

    public EvmAdapter withIndexer(String url) {
        this.indexer = url;
        return this;
    }

    private Web3j client() {
        return Web3j.build(new HttpService(rpc));
    }

    @Override
    public String family() {
        return "EVM";
    }

    @Override
    public String networkKey() {
        return networkKey;
    }

    public record TokenInfo(String contract, String symbol, int decimals, String name) {
    }

    public static String tokenAsset(String contract) {
        validateAddress(contract);
        return "ERC20:" + contract;
    }

    public static boolean isToken(String asset) {
        return asset != null && asset.toUpperCase().startsWith("ERC20:");
    }

    public static String tokenContract(String asset) {
        if (!isToken(asset)) throw new IllegalArgumentException("Not an ERC-20 asset");
        return asset.substring(asset.indexOf(':') + 1).trim();
    }

    private static void validateAddress(String a) {
        if (a == null || !a.matches("(?i)^0x[0-9a-f]{40}$"))
            throw new IllegalArgumentException("Invalid ERC-20 contract address.");
    }

    public CompletableFuture<TokenInfo> tokenInfo(String contract) {
        return CompletableFuture.supplyAsync(() -> {
            validateAddress(contract);
            try (Web3j w = client()) {
                String name = callString(w, contract, "name");
                String symbol = callString(w, contract, "symbol");
                Function f = new Function("decimals", List.of(), List.of(new TypeReference<Uint8>() {
                }));
                List<Type> out = FunctionReturnDecoder.decode(w.ethCall(Transaction.createEthCallTransaction(null, contract, FunctionEncoder.encode(f)), DefaultBlockParameterName.LATEST).send().getValue(), f.getOutputParameters());
                if (out.isEmpty()) throw new IllegalStateException("Token contract did not return decimals().");
                int decimals = ((BigInteger) out.get(0).getValue()).intValueExact();
                if (decimals < 0 || decimals > 36)
                    throw new IllegalStateException("Unsupported token decimals: " + decimals);
                return new TokenInfo(contract, symbol, decimals, name);
            } catch (Exception e) {
                throw new RuntimeException("Could not read ERC-20 metadata: " + root(e), e);
            }
        });
    }

    private String callString(Web3j w, String contract, String method) throws Exception {
        Function f = new Function(method, List.of(), List.of(new TypeReference<Utf8String>() {
        }));
        var response = w.ethCall(Transaction.createEthCallTransaction(null, contract, FunctionEncoder.encode(f)), DefaultBlockParameterName.LATEST).send();
        if (response.hasError()) throw new IllegalStateException(response.getError().getMessage());
        List<Type> out = FunctionReturnDecoder.decode(response.getValue(), f.getOutputParameters());
        if (out.isEmpty()) throw new IllegalStateException("Contract did not return " + method + "().");
        return String.valueOf(out.get(0).getValue());
    }

    @Override
    public CompletableFuture<String> getBalance(String address, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try (Web3j w = client()) {
                if (isToken(asset)) {
                    String contract = tokenContract(asset);
                    TokenInfo info = tokenInfo(contract).join();
                    Function f = new Function("balanceOf", List.of(new Address(address)), List.of(new TypeReference<Uint256>() {
                    }));
                    var res = w.ethCall(Transaction.createEthCallTransaction(address, contract, FunctionEncoder.encode(f)), DefaultBlockParameterName.LATEST).send();
                    if (res.hasError()) throw new IllegalStateException(res.getError().getMessage());
                    List<Type> out = FunctionReturnDecoder.decode(res.getValue(), f.getOutputParameters());
                    if (out.isEmpty()) throw new IllegalStateException("balanceOf returned no value.");
                    return new BigDecimal((BigInteger) out.get(0).getValue()).movePointLeft(info.decimals()).stripTrailingZeros().toPlainString();
                }
                var b = w.ethGetBalance(address, DefaultBlockParameterName.LATEST).send();
                return Convert.fromWei(new BigDecimal(b.getBalance()), Convert.Unit.ETHER).stripTrailingZeros().toPlainString();
            } catch (Exception e) {
                throw new RuntimeException(root(e), e);
            }
        });
    }

    @Override
    public CompletableFuture<TransferResult> transfer(String privateKey, String to, BigDecimal amount, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try (Web3j w = client()) {
                Credentials credentials = Credentials.create(privateKey);
                if (isToken(asset)) {
                    String contract = tokenContract(asset);
                    validateRecipient(to);
                    TokenInfo info = tokenInfo(contract).join();
                    BigInteger units = amount.movePointRight(info.decimals()).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
                    if (units.signum() <= 0)
                        throw new IllegalArgumentException("Token amount is below the smallest unit.");
                    Function f = new Function("transfer", List.of(new Address(to), new Uint256(units)), List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {
                    }));
                    return send(w, credentials, contract, BigInteger.ZERO, FunctionEncoder.encode(f), "ERC-20 transfer");
                }
                if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive.");
                validateRecipient(to);
                BigInteger wei = Convert.toWei(amount, Convert.Unit.ETHER).toBigIntegerExact();
                return send(w, credentials, to, wei, "", "Native coin transfer");
            } catch (Exception e) {
                throw new RuntimeException(root(e), e);
            }
        });
    }

    private static void validateRecipient(String a) {
        if (a == null || !a.matches("(?i)^0x[0-9a-f]{40}$"))
            throw new IllegalArgumentException("Invalid EVM address. Expected 0x followed by 40 hexadecimal characters.");
    }

    /**
     * Builds, simulates, signs and broadcasts one transaction. The gas limit comes from eth_estimateGas, so
     * a transfer that would fail on-chain (for example a recipient that needs more than the flat 21,000 gas of a
     * plain transfer, or one that rejects the call) is stopped BEFORE broadcasting and costs no fee.
     */
    private TransferResult send(Web3j w, Credentials credentials, String target, BigInteger value, String data, String what) throws Exception {
        String from = credentials.getAddress();
        BigInteger nonce = w.ethGetTransactionCount(from, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        BigInteger gasPrice = w.ethGasPrice().send().getGasPrice();
        boolean plain = data == null || data.isEmpty();
        Transaction probe = plain
                ? Transaction.createEtherTransaction(from, nonce, gasPrice, null, target, value)
                : Transaction.createFunctionCallTransaction(from, nonce, gasPrice, null, target, value, data);
        var estimate = w.ethEstimateGas(probe).send();
        if (estimate.hasError() || estimate.getAmountUsed() == null)
            throw new IllegalStateException(what + " would fail on-chain, so it was NOT sent (no fee spent): "
                    + (estimate.hasError() ? estimate.getError().getMessage() : "gas estimate unavailable") + ".");
        BigInteger estimated = estimate.getAmountUsed();
        // A plain transfer to an ordinary account estimates exactly 21,000: keep it exact. Anything more
        // (token transfers, recipients with code) gets a 20% safety margin.
        BigInteger gasLimit = plain && estimated.compareTo(PLAIN_TRANSFER_GAS) <= 0
                ? estimated
                : estimated.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
        RawTransaction tx = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, target, value, data == null ? "" : data);
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, credentials);
        var sent = w.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        if (sent.hasError())
            throw new IllegalStateException(what + " broadcast rejected: " + sent.getError().getMessage());
        String hash = sent.getTransactionHash();
        if (hash == null || hash.isBlank())
            throw new IllegalStateException("RPC did not return a transaction hash.");
        return awaitReceipt(w, hash, gasLimit);
    }

    @Override
    public CompletableFuture<StatusResult> checkTransactionStatus(String hash) {
        return CompletableFuture.supplyAsync(() -> {
            try (Web3j w = client()) {
                var receiptResponse = w.ethGetTransactionReceipt(hash).send();
                if (receiptResponse.hasError())
                    throw new IllegalStateException(receiptResponse.getError().getMessage());
                var receipt = receiptResponse.getTransactionReceipt();
                if (receipt.isPresent()) {
                    var r = receipt.get();
                    String reason = null;
                    if (!r.isStatusOK()) {
                        BigInteger limit = null;
                        try {
                            var t = w.ethGetTransactionByHash(hash).send().getTransaction();
                            if (t.isPresent()) limit = t.get().getGas();
                        } catch (Exception ignored) {
                            // the generic reason below is still correct
                        }
                        reason = failureReason(r.getGasUsed(), limit);
                    }
                    return new StatusResult(r.isStatusOK() ? "CONFIRMED" : "FAILED",
                            r.getGasUsed() == null ? null : r.getGasUsed().toString() + " gas", reason);
                }
                var txResponse = w.ethGetTransactionByHash(hash).send();
                if (txResponse.hasError()) throw new IllegalStateException(txResponse.getError().getMessage());
                // A missing receipt is not proof of failure; keep it pending even if this RPC
                // has not indexed the transaction yet.
                return new StatusResult("PENDING", null, null);
            } catch (Exception e) {
                throw new RuntimeException("Could not query " + networkKey + " transaction status: " + root(e), e);
            }
        });
    }

    private TransferResult awaitReceipt(Web3j w, String hash, BigInteger gasLimit) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            var r = w.ethGetTransactionReceipt(hash).send().getTransactionReceipt();
            if (r.isPresent()) {
                var receipt = r.get();
                String fee = receipt.getGasUsed() + " gas";
                if (receipt.isStatusOK()) return new TransferResult(hash, "CONFIRMED", fee);
                return new TransferResult(hash, "FAILED", fee, failureReason(receipt.getGasUsed(), gasLimit));
            }
            Thread.sleep(1500);
        }
        return new TransferResult(hash, "PENDING", "receipt not observed within 90 seconds");
    }

    /** Best-effort explanation for a mined-but-failed transaction. */
    private static String failureReason(BigInteger gasUsed, BigInteger gasLimit) {
        if (gasUsed != null && gasLimit != null && gasUsed.compareTo(gasLimit) >= 0)
            return "Out of gas: the transaction used its whole gas limit (" + gasLimit + "). The recipient probably runs code on receipt and needs more gas than this limit.";
        return "Transaction reverted on-chain (status 0). Open the transaction in the block explorer for the trace.";
    }

    private static String root(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    // ---- incoming history (Etherscan-compatible indexer) ----

    private JsonNode indexerQuery(String action, String address) throws Exception {
        String url = indexer + "?module=account&action=" + action + "&address=" + URLEncoder.encode(address, StandardCharsets.UTF_8)
                + "&sort=desc&page=1&offset=100";
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300)
            throw new IllegalStateException("History API HTTP " + res.statusCode() + " for " + networkKey);
        JsonNode root = JSON.readTree(res.body());
        JsonNode result = root.path("result");
        if (result.isArray()) return result;
        String msg = root.path("message").asText("");
        if ("0".equals(root.path("status").asText()) && msg.toLowerCase().contains("no transactions")) return JSON.createArrayNode();
        throw new IllegalStateException("History API: " + msg + " " + (result.isTextual() ? result.asText() : ""));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    @Override
    public CompletableFuture<List<IncomingTx>> fetchIncoming(String address, Set<String> knownHashes) {
        if (indexer == null || indexer.isBlank())
            return CompletableFuture.failedFuture(new IllegalStateException("No history API configured for " + networkKey));
        return CompletableFuture.supplyAsync(() -> {
            try {
                String me = address.toLowerCase();
                // hash|asset -> aggregated transfer (a transaction can pay the same wallet directly and via internal calls)
                Map<String, IncomingTx> out = new LinkedHashMap<>();
                java.util.function.BiConsumer<IncomingTx, BigDecimal> add = (tx, amount) -> {
                    String k = tx.hash() + "|" + tx.asset();
                    IncomingTx old = out.get(k);
                    BigDecimal total = old == null ? amount : amount.add(new BigDecimal(old.amount()));
                    out.put(k, new IncomingTx(tx.hash(), tx.from(), tx.to(), plain(total), tx.asset(), tx.status(), tx.fee(), tx.timestamp()));
                };
                for (String action : new String[]{"txlist", "txlistinternal"}) {
                    for (JsonNode t : indexerQuery(action, address)) {
                        if (!me.equalsIgnoreCase(t.path("to").asText())) continue;
                        if (!"0".equals(t.path("isError").asText("0"))) continue; // value did not move
                        BigDecimal wei = new BigDecimal(t.path("value").asText("0"));
                        if (wei.signum() <= 0) continue;
                        add.accept(new IncomingTx(t.path("hash").asText(), t.path("from").asText(), address, "0", NATIVE, "CONFIRMED", null,
                                ChainAdapter.utc(t.path("timeStamp").asLong())), Convert.fromWei(wei, Convert.Unit.ETHER));
                    }
                    Thread.sleep(300);
                }
                for (JsonNode t : indexerQuery("tokentx", address)) {
                    String contract = t.path("contractAddress").asText();
                    // Routescan lists native transfers as a pseudo token at 0xff..fe; those are already covered above.
                    if (contract.toLowerCase().startsWith("0xffffffffffffffffffffffffffffffffffffff")) continue;
                    if (!me.equalsIgnoreCase(t.path("to").asText())) continue;
                    BigDecimal raw = new BigDecimal(t.path("value").asText("0"));
                    if (raw.signum() <= 0) continue;
                    int decimals = t.path("tokenDecimal").asInt(0);
                    add.accept(new IncomingTx(t.path("hash").asText(), t.path("from").asText(), address, "0", "ERC20:" + contract, "CONFIRMED", null,
                            ChainAdapter.utc(t.path("timeStamp").asLong())), raw.movePointLeft(decimals));
                }
                return List.copyOf(out.values());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
