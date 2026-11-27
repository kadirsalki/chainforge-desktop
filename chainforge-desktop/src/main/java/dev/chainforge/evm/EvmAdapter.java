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
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.Log;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * EVM native coin and ERC-20 adapter for one configured network (Sepolia, Ethereum mainnet, ...).
 * Token asset syntax: ERC20:<contract-address>.
 */
public final class EvmAdapter implements ChainAdapter {
    private static final BigInteger PLAIN_TRANSFER_GAS = BigInteger.valueOf(21_000);
    private static final BigInteger TOKEN_HISTORY_WINDOW = BigInteger.valueOf(1000);
    private static final String TRANSFER_TOPIC = Hash.sha3String("Transfer(address,address,uint256)");

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
                    return send(w, credentials, contract, BigInteger.ZERO, tokenTransferData(to, units), "ERC-20 transfer");
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

    public record FeeTarget(String address, BigDecimal amount) {
    }

    public BigDecimal estimateBatchFee(String sender, String asset, int decimals, List<FeeTarget> targets) throws Exception {
        try (Web3j w = client()) {
            var rpcId = w.ethChainId().send();
            if (rpcId.hasError() || !BigInteger.valueOf(chainId).equals(rpcId.getChainId()))
                throw new IllegalStateException("The RPC does not match the selected network.");
            var price = w.ethGasPrice().send();
            if (price.hasError()) throw new IllegalStateException(price.getError().getMessage());
            BigInteger gasPrice = price.getGasPrice();
            BigInteger total = BigInteger.ZERO;
            for (FeeTarget target : targets) {
                boolean token = isToken(asset);
                BigInteger units = target.amount().movePointRight(decimals).toBigIntegerExact();
                Transaction probe = token
                        ? Transaction.createFunctionCallTransaction(sender, null, gasPrice, null,
                                tokenContract(asset), BigInteger.ZERO, tokenTransferData(target.address(), units))
                        : Transaction.createEtherTransaction(sender, null, gasPrice, null, target.address(), units);
                try {
                    total = total.add(estimatedGasLimit(w, probe, !token, "Transfer"));
                } catch (Exception e) {
                    throw new IllegalStateException(target.address() + ": " + root(e), e);
                }
            }
            return Convert.fromWei(new BigDecimal(total.multiply(gasPrice)), Convert.Unit.ETHER);
        }
    }

    private static String tokenTransferData(String recipient, BigInteger units) {
        Function function = new Function("transfer", List.of(new Address(recipient), new Uint256(units)),
                List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {}));
        return FunctionEncoder.encode(function);
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
        BigInteger gasLimit = estimatedGasLimit(w, probe, plain, what);
        RawTransaction tx = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, target, value, data == null ? "" : data);
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, credentials);
        var sent = w.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        if (sent.hasError())
            throw new IllegalStateException(what + " broadcast rejected: " + sent.getError().getMessage());
        String hash = sent.getTransactionHash();
        if (hash == null || hash.isBlank())
            throw new IllegalStateException("RPC did not return a transaction hash.");
        try {
            return awaitReceipt(w, hash, gasLimit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TransferResult(hash, "PENDING", null, "Receipt polling interrupted");
        } catch (Exception e) {
            return new TransferResult(hash, "PENDING", null, "Receipt unavailable: " + root(e));
        }
    }

    private static BigInteger estimatedGasLimit(Web3j w, Transaction probe, boolean plain, String what) throws Exception {
        var estimate = w.ethEstimateGas(probe).send();
        if (estimate.hasError() || estimate.getAmountUsed() == null)
            throw new IllegalStateException(what + " would fail on-chain, so it was NOT sent (no fee spent): "
                    + (estimate.hasError() ? estimate.getError().getMessage() : "gas estimate unavailable") + ".");
        BigInteger estimated = estimate.getAmountUsed();
        // A plain transfer to an ordinary account estimates exactly 21,000: keep it exact. Anything more
        // (token transfers, recipients with code) gets a 20% safety margin.
        return plain && estimated.compareTo(PLAIN_TRANSFER_GAS) <= 0
                ? estimated
                : estimated.multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
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
        if (!"tokentx".equals(action) || (chainId != 1 && chainId != 11155111))
            return indexerQuery(indexer, action, address);
        String fallback = chainId == 1 ? "https://eth.blockscout.com/api" : "https://eth-sepolia.blockscout.com/api";

        JsonNode primary = null;
        Exception primaryFailure = null;
        try {
            primary = indexerQuery(indexer, action, address);
            if (!primary.isEmpty()) return primary;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            primaryFailure = e;
        }
        try {
            return indexerQuery(fallback, action, address);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (primary != null) return primary;
            throw new IllegalStateException("Routescan: " + root(primaryFailure) + " · Blockscout: " + root(e));
        }
    }

    private JsonNode indexerQuery(String endpoint, String action, String address) throws Exception {
        String url = endpoint + "?module=account&action=" + action + "&address=" + URLEncoder.encode(address, StandardCharsets.UTF_8)
                + "&sort=desc&page=1&offset=100";
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300)
            throw new IllegalStateException("History API HTTP " + res.statusCode() + " for " + networkKey);
        JsonNode root = JSON.readTree(res.body());
        JsonNode result = root.path("result");
        if (result.isArray()) return result;
        String msg = root.path("message").asText("");
        if ("0".equals(root.path("status").asText()) && (msg + " " + result.asText()).toLowerCase(Locale.ROOT).contains("no transactions")) return JSON.createArrayNode();
        throw new IllegalStateException("History API: " + msg + " " + (result.isTextual() ? result.asText() : ""));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    @Override
    public CompletableFuture<List<IncomingTx>> fetchIncoming(String address, Set<String> knownHashes) {
        return fetchIncoming(address, List.of(), failure -> { throw failure; });
    }

    public CompletableFuture<List<IncomingTx>> fetchIncoming(String address, List<TokenInfo> trackedTokens,
                                                              Consumer<IllegalStateException> onSourceFailure) {
        if (indexer == null || indexer.isBlank())
            return CompletableFuture.failedFuture(new IllegalStateException("No history API configured for " + networkKey));
        return CompletableFuture.supplyAsync(() -> {
            Map<String, IncomingTx> out = new LinkedHashMap<>();
            for (String action : List.of("tokentx", "txlist", "txlistinternal")) {
                try {
                    if (!"tokentx".equals(action)) Thread.sleep(300);
                    for (JsonNode row : indexerQuery(action, address)) {
                        IncomingTx tx = incomingTransfer(row, address, "tokentx".equals(action));
                        if (tx == null) continue;
                        mergeTransfer(out, tx);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Incoming transfer sync interrupted", e);
                } catch (Exception e) {
                    onSourceFailure.accept(new IllegalStateException(action + ": " + root(e), e));
                }
            }
            if (!trackedTokens.isEmpty()) {
                try {
                    fetchRecentTokenLogs(address, trackedTokens).forEach(out::putIfAbsent);
                } catch (Exception e) {
                    onSourceFailure.accept(new IllegalStateException("ERC-20 RPC history: " + root(e), e));
                }
            }
            return List.copyOf(out.values());
        });
    }

    private static void mergeTransfer(Map<String, IncomingTx> transfers, IncomingTx tx) {
        String key = (tx.hash() + "|" + tx.to() + "|" + tx.asset()).toLowerCase(Locale.ROOT);
        transfers.merge(key, tx, (previous, current) -> {
            BigDecimal total = new BigDecimal(previous.amount()).add(new BigDecimal(current.amount()));
            String symbol = current.tokenSymbol() == null ? previous.tokenSymbol() : current.tokenSymbol();
            return new IncomingTx(current.hash(), current.from(), current.to(), plain(total),
                    current.asset(), current.status(), current.fee(), current.timestamp(), symbol);
        });
    }

    private Map<String, IncomingTx> fetchRecentTokenLogs(String address, List<TokenInfo> tokens) throws Exception {
        validateAddress(address);
        Map<String, TokenInfo> byContract = new HashMap<>();
        for (TokenInfo token : tokens) byContract.put(token.contract().toLowerCase(Locale.ROOT), token);

        Web3j web3j = client();
        try {
            var head = web3j.ethBlockNumber().send();
            if (head.hasError()) throw new IllegalStateException(head.getError().getMessage());
            BigInteger latest = head.getBlockNumber();
            BigInteger first = latest.subtract(TOKEN_HISTORY_WINDOW).add(BigInteger.ONE).max(BigInteger.ZERO);
            String recipientTopic = Numeric.toHexStringWithPrefixZeroPadded(Numeric.toBigInt(address), 64);
            var filter = new EthFilter(DefaultBlockParameter.valueOf(first), DefaultBlockParameter.valueOf(latest),
                    List.copyOf(byContract.keySet()));
            filter.addSingleTopic(TRANSFER_TOPIC).addNullTopic().addSingleTopic(recipientTopic);
            var response = web3j.ethGetLogs(filter).send();
            if (response.hasError()) throw new IllegalStateException(response.getError().getMessage());

            Map<String, String> timestamps = new HashMap<>();
            Map<String, IncomingTx> transfers = new LinkedHashMap<>();
            for (var item : response.getLogs()) {
                if (!(item.get() instanceof Log log)) continue;
                IncomingTx transfer = incomingTokenLog(log, Map.of(address.toLowerCase(Locale.ROOT), address), byContract);
                if (transfer == null) continue;
                String timestamp = timestamps.get(log.getBlockHash());
                if (timestamp == null) {
                    timestamp = blockTimestamp(web3j, log.getBlockHash());
                    timestamps.put(log.getBlockHash(), timestamp);
                }
                mergeTransfer(transfers, withTimestamp(transfer, timestamp));
            }
            return transfers;
        } finally {
            web3j.shutdown();
        }
    }

    public List<IncomingTx> lookupIncoming(String hash, List<String> wallets, List<TokenInfo> tokens) throws Exception {
        if (!hash.matches("(?i)0x[0-9a-f]{64}")) throw new IllegalArgumentException("Enter a valid EVM transaction hash.");
        Map<String, String> recipients = new HashMap<>();
        for (String address : wallets) recipients.put(address.toLowerCase(Locale.ROOT), address);
        Map<String, TokenInfo> contracts = new HashMap<>();
        for (TokenInfo token : tokens) contracts.put(token.contract().toLowerCase(Locale.ROOT), token);
        try (Web3j w = client()) {
            var rpcId = w.ethChainId().send();
            if (rpcId.hasError() || !BigInteger.valueOf(chainId).equals(rpcId.getChainId()))
                throw new IllegalStateException("The RPC does not match the selected network.");
            var response = w.ethGetTransactionReceipt(hash).send();
            if (response.hasError()) throw new IllegalStateException(response.getError().getMessage());
            var receipt = response.getTransactionReceipt().orElseThrow(() ->
                    new IllegalStateException("No confirmed receipt found on this network."));
            if (!receipt.isStatusOK()) throw new IllegalStateException("This transaction failed on-chain.");
            String timestamp = blockTimestamp(w, receipt.getBlockHash());
            Map<String, IncomingTx> transfers = new LinkedHashMap<>();
            for (Log log : receipt.getLogs()) {
                IncomingTx transfer = incomingTokenLog(log, recipients, contracts);
                if (transfer != null) {
                    // A single hash can pay several registered wallets.
                    mergeTransfer(transfers, withTimestamp(transfer, timestamp));
                }
            }
            String to = receipt.getTo() == null ? null : recipients.get(receipt.getTo().toLowerCase(Locale.ROOT));
            if (to != null && !to.equalsIgnoreCase(receipt.getFrom())) {
                var transaction = w.ethGetTransactionByHash(hash).send();
                if (transaction.hasError()) throw new IllegalStateException(transaction.getError().getMessage());
                var nativeTx = transaction.getTransaction().orElseThrow();
                if (nativeTx.getValue().signum() > 0)
                    mergeTransfer(transfers, new IncomingTx(receipt.getTransactionHash(), receipt.getFrom(), to,
                            plain(Convert.fromWei(new BigDecimal(nativeTx.getValue()), Convert.Unit.ETHER)),
                            NATIVE, "CONFIRMED", null, timestamp));
            }
            return List.copyOf(transfers.values());
        }
    }

    private static IncomingTx incomingTokenLog(Log log, Map<String, String> recipients, Map<String, TokenInfo> contracts) {
        if (log.isRemoved() || log.getAddress() == null || log.getTransactionHash() == null || log.getBlockHash() == null
                || log.getTopics() == null || log.getTopics().size() != 3 || log.getData() == null
                || !TRANSFER_TOPIC.equalsIgnoreCase(log.getTopics().get(0))
                || !log.getData().matches("(?i)0x[0-9a-f]{64}")) return null;
        String fromTopic = log.getTopics().get(1), toTopic = log.getTopics().get(2);
        if (!fromTopic.matches("(?i)0x0{24}[0-9a-f]{40}") || !toTopic.matches("(?i)0x0{24}[0-9a-f]{40}")) return null;
        String to = recipients.get(("0x" + toTopic.substring(26)).toLowerCase(Locale.ROOT));
        String from = "0x" + fromTopic.substring(26);
        TokenInfo token = contracts.get(log.getAddress().toLowerCase(Locale.ROOT));
        if (to == null || token == null || to.equalsIgnoreCase(from)) return null;
        BigInteger raw = Numeric.toBigInt(log.getData());
        if (raw.signum() <= 0) return null;
        return new IncomingTx(log.getTransactionHash(), from, to,
                plain(new BigDecimal(raw, token.decimals())), tokenAsset(token.contract()),
                "CONFIRMED", null, null, token.symbol());
    }

    private static String blockTimestamp(Web3j w, String blockHash) throws Exception {
        var block = w.ethGetBlockByHash(blockHash, false).send();
        if (block.hasError()) throw new IllegalStateException(block.getError().getMessage());
        if (block.getBlock() == null) throw new IllegalStateException("Confirmed block unavailable.");
        return ChainAdapter.utc(block.getBlock().getTimestamp().longValueExact());
    }

    private static IncomingTx withTimestamp(IncomingTx transfer, String timestamp) {
        return new IncomingTx(transfer.hash(), transfer.from(), transfer.to(), transfer.amount(), transfer.asset(),
                transfer.status(), transfer.fee(), timestamp, transfer.tokenSymbol());
    }

    private static IncomingTx incomingTransfer(JsonNode row, String address, boolean token) {
        if (!address.equalsIgnoreCase(row.path("to").asText()) || !"0".equals(row.path("isError").asText("0")))
            return null;
        String from = row.path("from").asText();
        String hash = row.path("hash").asText();
        if (hash.isBlank() || address.equalsIgnoreCase(from)) return null;

        String contract = row.path("contractAddress").asText();
        int decimals = 18;
        String symbol = null;
        if (token) {
            if (!contract.matches("(?i)0x[0-9a-f]{40}") || contract.toLowerCase(Locale.ROOT).startsWith("0xffffffffffffffffffffffffffffffffffffff"))
                return null;
            JsonNode tokenDecimal = row.path("tokenDecimal");
            decimals = tokenDecimal.asInt(-1);
            if (decimals < 0 || decimals > 255) return null;
            String ticker = row.path("tokenSymbol").asText().strip();
            if (!ticker.isBlank() && ticker.length() <= 32 && ticker.codePoints().noneMatch(c ->
                    Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) symbol = ticker;
        }
        BigInteger raw;
        try {
            raw = new BigInteger(row.path("value").asText());
        } catch (NumberFormatException e) {
            return null;
        }
        if (raw.signum() <= 0) return null;
        return new IncomingTx(hash, from, address, plain(new BigDecimal(raw, decimals)),
                token ? "ERC20:" + contract : NATIVE, "CONFIRMED", null,
                ChainAdapter.utc(row.path("timeStamp").asLong()), symbol);
    }
}
