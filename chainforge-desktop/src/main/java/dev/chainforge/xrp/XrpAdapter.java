package dev.chainforge.xrp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import dev.chainforge.core.ChainAdapter;
import org.xrpl.xrpl4j.crypto.keys.KeyPair;
import org.xrpl.xrpl4j.crypto.signing.SingleSignedTransaction;
import org.xrpl.xrpl4j.crypto.signing.bc.BcSignatureService;
import org.xrpl.xrpl4j.model.transactions.Address;
import org.xrpl.xrpl4j.model.transactions.Payment;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * XRP Ledger adapter (one instance per network): native XRP payments only. Talks to a rippled JSON-RPC
 * endpoint over HTTP; transactions are built and signed locally (xrpl4j) and only the signed blob is sent.
 * The seed never leaves the process.
 */
public final class XrpAdapter implements ChainAdapter {
    private static final BigDecimal DROPS_PER_XRP = BigDecimal.valueOf(1_000_000L);
    /** Never pay more than 0.01 XRP in fees, even if the open-ledger fee spikes. */
    private static final long MAX_FEE_DROPS = 10_000;
    /** The transaction can only be validated within this many ledgers (~4 s each), then it is dead for good. */
    private static final int LEDGER_WINDOW = 20;
    private static final long CONFIRM_TIMEOUT_MS = 45_000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String networkKey;
    private final String rpc;
    private volatile boolean testnet;
    private final BcSignatureService signer = new BcSignatureService();
    // The open ledger's account_info can lag behind our own queued submissions; remember the next sequence per account.
    private final Map<String, Long> nextSequence = new HashMap<>();

    public XrpAdapter(String networkKey, String rpc) {
        if (rpc == null || rpc.isBlank()) throw new IllegalArgumentException("XRP RPC URL is empty for " + networkKey);
        this.networkKey = networkKey;
        this.rpc = rpc.trim();
    }

    public XrpAdapter withTestnet(boolean testnet) {
        this.testnet = testnet;
        return this;
    }

    @Override
    public String family() {
        return "XRP";
    }

    @Override
    public String networkKey() {
        return networkKey;
    }

    private static void requireXrp(String asset) {
        if (!"XRP".equalsIgnoreCase(asset))
            throw new IllegalArgumentException("Only native XRP is enabled. Issued currencies (trust lines) are not supported.");
    }

    /** Calls a rippled JSON-RPC method and returns its "result" node. The caller inspects result.status / result.error. */
    private JsonNode call(String method, ObjectNode params) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("method", method);
        body.putArray("params").add(params == null ? JSON.createObjectNode() : params);
        HttpRequest req = HttpRequest.newBuilder(URI.create(rpc)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300)
            throw new IllegalStateException("XRPL RPC HTTP " + res.statusCode() + ": " + res.body());
        JsonNode result = JSON.readTree(res.body()).path("result");
        if (result.isMissingNode()) throw new IllegalStateException("XRPL RPC returned no result: " + res.body());
        return result;
    }

    private static String errorOf(JsonNode r) {
        return r.path("error_message").asText(r.path("error").asText("unknown error"));
    }

    private static ObjectNode obj() {
        return JSON.createObjectNode();
    }

    /** Returns the account_data node, or null if the account does not exist (never funded). */
    private JsonNode accountData(String address) throws Exception {
        JsonNode r = call("account_info", obj().put("account", address).put("ledger_index", "current"));
        if ("success".equals(r.path("status").asText())) return r.path("account_data");
        if ("actNotFound".equals(r.path("error").asText())) return null;
        throw new IllegalStateException("XRPL account_info failed: " + errorOf(r));
    }

    private static String drops(BigDecimal d) {
        return d.divide(DROPS_PER_XRP, 6, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    @Override
    public CompletableFuture<String> getBalance(String address, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireXrp(asset);
                XrpKeys.validateAddress(address);
                JsonNode acct = accountData(address);
                if (acct == null) return "0"; // not activated yet
                return drops(new BigDecimal(acct.path("Balance").asText("0")));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private long feeDrops() throws Exception {
        JsonNode r = call("fee", null);
        JsonNode d = r.path("drops");
        long base = d.path("base_fee").asLong(10);
        long open = d.path("open_ledger_fee").asLong(base);
        return Math.max(base, Math.min(open, MAX_FEE_DROPS));
    }

    /** Reserve (in drops) the account must keep: base reserve + owner reserve per owned object. */
    private BigDecimal reserveDrops(JsonNode acct) {
        BigDecimal base = BigDecimal.valueOf(1_000_000L), inc = BigDecimal.valueOf(200_000L);
        try {
            JsonNode v = call("server_info", null).path("info").path("validated_ledger");
            if (v.hasNonNull("reserve_base_xrp")) base = new BigDecimal(v.get("reserve_base_xrp").asText()).multiply(DROPS_PER_XRP);
            if (v.hasNonNull("reserve_inc_xrp")) inc = new BigDecimal(v.get("reserve_inc_xrp").asText()).multiply(DROPS_PER_XRP);
        } catch (Exception ignored) {
            // fall back to the current network defaults (1 XRP + 0.2 XRP per object)
        }
        return base.add(inc.multiply(BigDecimal.valueOf(acct.path("OwnerCount").asLong(0))));
    }

    @Override
    public CompletableFuture<TransferResult> transfer(String seed, String to, BigDecimal amount, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireXrp(asset);
                long amountDrops = amount.multiply(DROPS_PER_XRP).setScale(0, RoundingMode.DOWN).longValueExact();
                if (amountDrops <= 0) throw new IllegalArgumentException("Amount is below one drop (0.000001 XRP).");
                XrpKeys.Destination dest = XrpKeys.parseDestination(to); // classic address or X-address (address + destination tag)
                KeyPair keys = XrpKeys.keyPair(seed);
                String from = XrpKeys.address(keys);
                if (from.equals(dest.classic())) throw new IllegalArgumentException("Sender and recipient are the same account.");

                JsonNode acct = accountData(from);
                if (acct == null)
                    throw new IllegalStateException("Sender account " + from + " is not activated on " + networkKey + ". Fund it with at least the base reserve first.");
                long fee = feeDrops();
                BigDecimal balance = new BigDecimal(acct.path("Balance").asText("0"));
                BigDecimal reserve = reserveDrops(acct);
                if (balance.subtract(BigDecimal.valueOf(amountDrops + fee)).compareTo(reserve) < 0)
                    throw new IllegalStateException("Insufficient spendable XRP: balance " + drops(balance) + " XRP, reserve " + drops(reserve)
                            + " XRP stays locked, amount + fee " + drops(BigDecimal.valueOf(amountDrops + fee)) + " XRP.");

                long ledger = call("ledger_current", null).path("ledger_current_index").asLong();
                if (ledger <= 0) throw new IllegalStateException("Could not read the current ledger index.");
                long seq;
                synchronized (nextSequence) {
                    seq = Math.max(acct.path("Sequence").asLong(), nextSequence.getOrDefault(from, 0L));
                }

                Payment payment = Payment.builder()
                        .account(Address.of(from))
                        .destination(Address.of(dest.classic()))
                        .destinationTag(Optional.ofNullable(dest.tag()).map(UnsignedInteger::valueOf))
                        .amount(XrpCurrencyAmount.of(UnsignedLong.valueOf(amountDrops)))
                        .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(fee)))
                        .sequence(UnsignedInteger.valueOf(seq))
                        .lastLedgerSequence(UnsignedInteger.valueOf(ledger + LEDGER_WINDOW))
                        .signingPublicKey(keys.publicKey())
                        .build();
                SingleSignedTransaction<Payment> signed = signer.sign(keys.privateKey(), payment);
                String hash = signed.hash().value();
                String blob = signed.signedTransactionBytes().hexValue();

                JsonNode sub = call("submit", obj().put("tx_blob", blob));
                if (!"success".equals(sub.path("status").asText()))
                    throw new IllegalStateException("XRPL submit failed: " + errorOf(sub));
                String engine = sub.path("engine_result").asText("");
                String engineMsg = sub.path("engine_result_message").asText("");
                boolean applied = engine.startsWith("tes") || engine.equals("terQUEUED");
                boolean claimedFee = engine.startsWith("tec"); // included in a ledger, sequence consumed, fee burned
                if (applied || claimedFee) {
                    synchronized (nextSequence) {
                        nextSequence.merge(from, seq + 1, Math::max);
                    }
                }
                String hint = engine.equals("tecDST_TAG_NEEDED") ? " Add a destination tag to this recipient." : "";
                if (claimedFee)
                    throw new IllegalStateException("XRPL rejected the payment: " + engine + " — " + engineMsg + hint + " (the " + fee + " drop fee is still charged; tx " + hash + ")");
                if (!applied)
                    throw new IllegalStateException("XRPL rejected the payment: " + engine + " — " + engineMsg);

                long deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    StatusResult s = statusOf(hash);
                    if ("CONFIRMED".equals(s.status())) return new TransferResult(hash, "CONFIRMED", fee + " drops");
                    if ("FAILED".equals(s.status()))
                        throw new IllegalStateException("Payment failed on-ledger: " + s.error() + " (tx " + hash + ")");
                    Thread.sleep(2_000L);
                }
                return new TransferResult(hash, "PENDING", fee + " drops");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private StatusResult statusOf(String hash) throws Exception {
        JsonNode r = call("tx", obj().put("transaction", hash).put("binary", false));
        if (!"success".equals(r.path("status").asText())) {
            if ("txnNotFound".equals(r.path("error").asText())) return new StatusResult("PENDING", null, null);
            throw new IllegalStateException("XRPL tx lookup failed: " + errorOf(r));
        }
        if (!r.path("validated").asBoolean(false)) return new StatusResult("PENDING", null, null);
        String code = r.path("meta").path("TransactionResult").asText("");
        String fee = r.hasNonNull("Fee") ? r.get("Fee").asText() + " drops" : null;
        if ("tesSUCCESS".equals(code)) return new StatusResult("CONFIRMED", fee, null);
        return new StatusResult("FAILED", fee, "XRPL result " + code);
    }

    @Override
    public CompletableFuture<StatusResult> checkTransactionStatus(String hash) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return statusOf(hash);
            } catch (Exception e) {
                throw new RuntimeException("Could not query XRPL transaction status: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<List<IncomingTx>> fetchIncoming(String address, Set<String> knownHashes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                XrpKeys.validateAddress(address);
                JsonNode r = call("account_tx", obj().put("account", address).put("ledger_index_min", -1).put("ledger_index_max", -1).put("limit", 50).put("forward", false));
                if ("actNotFound".equals(r.path("error").asText())) return List.<IncomingTx>of(); // never funded
                if (!"success".equals(r.path("status").asText())) throw new IllegalStateException("XRPL account_tx failed: " + errorOf(r));
                List<IncomingTx> out = new ArrayList<>();
                for (JsonNode e : r.path("transactions")) {
                    JsonNode tx = e.has("tx_json") ? e.path("tx_json") : e.path("tx");
                    JsonNode meta = e.path("meta");
                    String hash = e.path("hash").asText(tx.path("hash").asText());
                    if (hash.isEmpty() || knownHashes.contains(hash)) continue;
                    if (!"Payment".equals(tx.path("TransactionType").asText())) continue;
                    if (!address.equals(tx.path("Destination").asText())) continue;
                    if (!"tesSUCCESS".equals(meta.path("TransactionResult").asText())) continue;
                    JsonNode delivered = meta.has("delivered_amount") ? meta.path("delivered_amount") : tx.path("Amount");
                    if (!delivered.isTextual()) continue; // issued currencies are not supported
                    String amount = drops(new BigDecimal(delivered.asText()));
                    // Keep the destination tag visible: store our address in X-address form when a tag was used.
                    String to = address;
                    if (tx.hasNonNull("DestinationTag")) to = XrpKeys.encode(address, tx.path("DestinationTag").asLong(), testnet);
                    long rippleTime = tx.path("date").asLong(e.path("date").asLong(0));
                    String ts = rippleTime > 0 ? ChainAdapter.utc(rippleTime + 946_684_800L) : null;
                    out.add(new IncomingTx(hash, tx.path("Account").asText(), to, amount, "XRP", e.path("validated").asBoolean(true) ? "CONFIRMED" : "PENDING", null, ts));
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
