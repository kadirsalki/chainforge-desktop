package dev.chainforge.bitcoin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chainforge.core.ChainAdapter;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bitcoin adapter (testnet or mainnet, one instance per network): native BTC only, P2WPKH wallets, UTXO selection and fee-rate policy.
 * Chain data comes from an Esplora-compatible HTTP API (mempool.space by default),
 * so no local node is required. Transactions are built and signed locally with bitcoinj.
 */
public final class BitcoinAdapter implements ChainAdapter {
    private static final long SATS_PER_BTC = 100_000_000L;
    /** Standard dust limit for P2WPKH outputs. */
    private static final long DUST_SATS = 294;
    private static final long MAX_FEE_RATE = 200; // sat/vB safety cap
    // Conservative vsize estimate: overhead 11 + 68 per P2WPKH input + 34 recipient + 31 change.
    private static final int VB_OVERHEAD = 11, VB_INPUT = 68, VB_OUT_TO = 34, VB_OUT_CHANGE = 31;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    record Utxo(String txid, int vout, long value) {
        String key() {
            return txid + ":" + vout;
        }
    }

    // Esplora can lag a moment behind our own broadcasts. Remember what we just spent / created so a
    // batch of back-to-back transfers never re-selects a spent output or ignores fresh change.
    private final String networkKey;
    private final String api;
    private final BitcoinNetwork net;

    /**
     * @param api Esplora-compatible base URL, e.g. https://mempool.space/testnet/api (testnet3) or https://mempool.space/api (mainnet)
     */
    public BitcoinAdapter(String networkKey, String api, BitcoinNetwork net) {
        if (api == null || api.isBlank()) throw new IllegalArgumentException("Bitcoin API URL is empty for " + networkKey);
        this.networkKey = networkKey;
        this.api = api.trim().replaceAll("/+$", "");
        this.net = net;
    }

    private final Set<String> recentlySpent = new HashSet<>();
    private final Map<String, Long> lastStatusCheck = new ConcurrentHashMap<>();
    private volatile long cachedFeeRate;
    private volatile long cachedFeeAt;
    private final Map<String, Map<String, Utxo>> recentChange = new HashMap<>();

    @Override
    public String family() {
        return "BITCOIN";
    }

    @Override
    public String networkKey() {
        return networkKey;
    }

    private static void requireBtc(String asset) {
        if (!"BTC".equalsIgnoreCase(asset))
            throw new IllegalArgumentException("Only native BTC is enabled for Bitcoin.");
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofSeconds(30));
    }

    // Public Esplora APIs (mempool.space) rate-limit by IP. Keep a minimum gap between our own requests and
    // retry with backoff on HTTP 429 / 5xx / timeouts instead of failing the transfer straight away.
    private static final long MIN_GAP_MS = 400;
    private static final int MAX_ATTEMPTS = 5;
    private long lastRequestAt;

    private synchronized void throttle() throws InterruptedException {
        long wait = lastRequestAt + MIN_GAP_MS - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastRequestAt = System.currentTimeMillis();
    }

    private static long backoffMs(HttpResponse<String> res, int attempt) {
        long ms = Math.min(30_000L, 1_500L << attempt); // 3s, 6s, 12s, 24s
        if (res != null) {
            String ra = res.headers().firstValue("Retry-After").orElse(null);
            try {
                if (ra != null) ms = Math.max(ms, Math.min(30_000L, Long.parseLong(ra.trim()) * 1000L));
            } catch (NumberFormatException ignored) {
                // HTTP-date form is rare for these APIs; keep the computed backoff
            }
        }
        return ms;
    }

    private HttpResponse<String> send(HttpRequest r) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> res = null;
            try {
                throttle();
                res = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code != 429 && code < 500) return res;
                last = new IllegalStateException(code == 429 ? "Bitcoin API rate limit (HTTP 429 Too Many Requests)" : "Bitcoin API HTTP " + code);
                if (attempt == MAX_ATTEMPTS) return res;
            } catch (InterruptedException e) {
                throw e;
            } catch (java.io.IOException e) { // includes HttpTimeoutException / connect failures
                last = e;
                if (attempt == MAX_ATTEMPTS)
                    throw new IllegalStateException("Bitcoin API did not answer after " + MAX_ATTEMPTS + " attempts (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")", e);
            }
            Thread.sleep(backoffMs(res, attempt));
        }
        throw last;
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> res = send(req(path).GET().build());
        if (res.statusCode() < 200 || res.statusCode() >= 300)
            throw new IllegalStateException("Bitcoin API HTTP " + res.statusCode() + ": " + res.body());
        return JSON.readTree(res.body());
    }

    private static String fmtBtc(long sats) {
        return BigDecimal.valueOf(sats).divide(BigDecimal.valueOf(SATS_PER_BTC), 8, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    @Override
    public CompletableFuture<String> getBalance(String address, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireBtc(asset);
                BitcoinKeys.validateAddress(address, net);
                JsonNode n = getJson("/address/" + address);
                long confirmed = n.path("chain_stats").path("funded_txo_sum").asLong() - n.path("chain_stats").path("spent_txo_sum").asLong();
                long mempool = n.path("mempool_stats").path("funded_txo_sum").asLong() - n.path("mempool_stats").path("spent_txo_sum").asLong();
                return fmtBtc(confirmed + mempool);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<StatusResult> checkTransactionStatus(String txid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // A batch can leave dozens of transactions pending; asking the API about each one every few
                // seconds is what triggers rate limits. A block takes ~10 min, so once a minute is plenty.
                Long last = lastStatusCheck.get(txid);
                if (last != null && System.currentTimeMillis() - last < 60_000L) return new StatusResult("PENDING", null, null);
                lastStatusCheck.put(txid, System.currentTimeMillis());
                HttpResponse<String> res = send(req("/tx/" + txid + "/status").GET().build());
                // Unknown to the API (not yet propagated / indexed) is not proof of failure.
                if (res.statusCode() == 404) return new StatusResult("PENDING", null, null);
                if (res.statusCode() < 200 || res.statusCode() >= 300)
                    throw new IllegalStateException("Bitcoin API HTTP " + res.statusCode() + ": " + res.body());
                if (!JSON.readTree(res.body()).path("confirmed").asBoolean(false))
                    return new StatusResult("PENDING", null, null);
                String fee = null;
                try {
                    JsonNode tx = getJson("/tx/" + txid);
                    if (tx.hasNonNull("fee")) fee = tx.get("fee").asLong() + " sat";
                } catch (Exception ignored) {
                    // status is the important part; the fee is informational
                }
                lastStatusCheck.remove(txid);
                return new StatusResult("CONFIRMED", fee, null);
            } catch (Exception e) {
                throw new RuntimeException("Could not query Bitcoin transaction status: " + e.getMessage(), e);
            }
        });
    }

    private long feeRate() {
        if (cachedFeeRate > 0 && System.currentTimeMillis() - cachedFeeAt < 60_000L) return cachedFeeRate;
        try {
            JsonNode f = getJson("/v1/fees/recommended");
            long rate = f.path("halfHourFee").asLong(0);
            if (rate <= 0) rate = f.path("hourFee").asLong(0);
            if (rate <= 0) rate = f.path("minimumFee").asLong(1);
            cachedFeeRate = Math.max(1, Math.min(rate, MAX_FEE_RATE));
            cachedFeeAt = System.currentTimeMillis();
            return cachedFeeRate;
        } catch (Exception e) {
            return cachedFeeRate > 0 ? cachedFeeRate : 2; // Reuse the last known rate (or a small default) if the estimator is unreachable.
        }
    }

    private synchronized List<Utxo> spendable(String address) throws Exception {
        JsonNode arr = getJson("/address/" + address + "/utxo");
        List<Utxo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode n : arr) {
            Utxo u = new Utxo(n.path("txid").asText(), n.path("vout").asInt(), n.path("value").asLong());
            seen.add(u.key());
            if (!recentlySpent.contains(u.key())) out.add(u);
        }
        // Esplora now knows about our change outputs; stop tracking those.
        var walletChange = recentChange.get(address);
        if (walletChange != null) {
            walletChange.keySet().removeIf(seen::contains);
            for (Utxo c : walletChange.values()) if (!recentlySpent.contains(c.key())) out.add(c);
        }
        out.sort(Comparator.comparingLong(Utxo::value).reversed());
        return out;
    }

    private static long fee(int inputs, boolean change, long rate) {
        return (long) Math.ceil((VB_OVERHEAD + (double) VB_INPUT * inputs + VB_OUT_TO + (change ? VB_OUT_CHANGE : 0)) * rate);
    }

    private record CoinSelection(List<Utxo> inputs, long fee, long change) {
    }

    private static CoinSelection selectCoins(List<Utxo> available, long sats, long rate) {
        List<Utxo> inputs = new ArrayList<>();
        long total = 0;
        for (Utxo utxo : available) {
            inputs.add(utxo);
            total = Math.addExact(total, utxo.value());
            long withChange = fee(inputs.size(), true, rate);
            if (total - sats - withChange >= DUST_SATS)
                return new CoinSelection(inputs, withChange, total - sats - withChange);
            long noChange = fee(inputs.size(), false, rate);
            if (total - sats >= noChange && total - sats - noChange < DUST_SATS)
                return new CoinSelection(inputs, total - sats, 0);
        }
        throw new IllegalStateException("Insufficient spendable BTC for amount plus fee at " + rate + " sat/vB.");
    }

    public BigDecimal estimateBatchFee(String sender, List<BigDecimal> amounts) throws Exception {
        List<Utxo> available = spendable(sender);
        long rate = feeRate(), estimated = 0;
        int changeIndex = 0;
        for (BigDecimal amount : amounts) {
            long sats = amount.movePointRight(8).longValueExact();
            if (sats < DUST_SATS) throw new IllegalArgumentException("A planned Bitcoin transfer is below " + DUST_SATS + " sat.");
            CoinSelection selection = selectCoins(available, sats, rate);
            estimated = Math.addExact(estimated, selection.fee());
            available.removeAll(selection.inputs());
            if (selection.change() > 0) available.add(new Utxo("estimated-change", changeIndex++, selection.change()));
            available.sort(Comparator.comparingLong(Utxo::value).reversed());
        }
        return BigDecimal.valueOf(estimated, 8);
    }

    @Override
    public CompletableFuture<TransferResult> transfer(String privateKey, String to, BigDecimal amount, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                requireBtc(asset);
                long sats = amount.movePointRight(8).setScale(0, RoundingMode.DOWN).longValueExact();
                if (sats < DUST_SATS)
                    throw new IllegalArgumentException("Amount is below the dust limit (" + DUST_SATS + " sat).");
                ECKey key = BitcoinKeys.fromWif(privateKey, net);
                Address recipient = BitcoinKeys.parseAddress(to, net);
                String from = BitcoinKeys.address(key, net);
                Script ownScript = ScriptBuilder.createP2WPKHOutputScript(key);
                long rate = feeRate();

                // Largest-first coin selection.
                List<Utxo> all;
                synchronized (this) {
                    all = spendable(from);
                }
                CoinSelection selection = selectCoins(all, sats, rate);
                var picked = selection.inputs();
                long changeSats = selection.change(), feeSats = selection.fee();

                Transaction tx = new Transaction();
                tx.addOutput(Coin.ofSat(sats), recipient);
                if (changeSats > 0) tx.addOutput(Coin.ofSat(changeSats), key.toAddress(org.bitcoinj.base.ScriptType.P2WPKH, net));
                for (Utxo u : picked) {
                    tx.addSignedInput(new TransactionOutPoint(u.vout(), Sha256Hash.wrap(u.txid())), ownScript, Coin.ofSat(u.value()), key);
                }
                String txid = tx.getTxId().toString();
                String raw = HexFormat.of().formatHex(tx.serialize());

                HttpResponse<String> res = send(req("/tx").header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(raw)).build());
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    String body = res.body() == null ? "" : res.body();
                    String lower = body.toLowerCase(java.util.Locale.ROOT);
                    // A retry after a timeout can find the first attempt already accepted: same txid, so that is a success.
                    boolean alreadyKnown = lower.contains("already in") || lower.contains("txn-already-known") || lower.contains("already known");
                    if (!alreadyKnown) {
                        if (lower.contains("too-long-mempool-chain") || lower.contains("too many unconfirmed") || lower.contains("ancestor"))
                            throw new IllegalStateException("Bitcoin mempool chain limit reached: a wallet can have about 25 chained unconfirmed transfers. "
                                    + "Wait for the next block (~10 min) and continue with the remaining recipients. Details: " + body);
                        throw new IllegalStateException("Bitcoin broadcast rejected (HTTP " + res.statusCode() + "): " + body);
                    }
                }

                synchronized (this) {
                    var walletChange = recentChange.computeIfAbsent(from, address -> new HashMap<>());
                    for (Utxo u : picked) {
                        recentlySpent.add(u.key());
                        walletChange.remove(u.key());
                    }
                    if (changeSats > 0) {
                        Utxo c = new Utxo(txid, 1, changeSats);
                        walletChange.put(c.key(), c);
                    }
                }
                // A Bitcoin block takes ~10 minutes; confirmation is tracked afterwards via checkTransactionStatus.
                return new TransferResult(txid, "PENDING", feeSats + " sat");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<IncomingTx>> fetchIncoming(String address, Set<String> knownHashes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BitcoinKeys.validateAddress(address, net);
                List<IncomingTx> out = new ArrayList<>();
                // Esplora returns the mempool transactions plus the 25 newest confirmed ones.
                for (JsonNode tx : getJson("/address/" + address + "/txs")) {
                    String txid = tx.path("txid").asText();
                    if (knownHashes.contains(txid)) continue; // pending ones are updated by the status poller
                    boolean spendsOwnCoins = false;
                    String from = null;
                    for (JsonNode in : tx.path("vin")) {
                        String a = in.path("prevout").path("scriptpubkey_address").asText(null);
                        if (a != null && from == null) from = a;
                        if (address.equalsIgnoreCase(a)) spendsOwnCoins = true;
                    }
                    if (spendsOwnCoins) continue; // outgoing (or change) - not a deposit
                    long received = 0;
                    for (JsonNode o : tx.path("vout"))
                        if (address.equalsIgnoreCase(o.path("scriptpubkey_address").asText())) received += o.path("value").asLong();
                    if (received <= 0) continue;
                    boolean confirmed = tx.path("status").path("confirmed").asBoolean(false);
                    String ts = confirmed && tx.path("status").has("block_time") ? ChainAdapter.utc(tx.path("status").path("block_time").asLong()) : null;
                    out.add(new IncomingTx(txid, from, address, fmtBtc(received), NATIVE, confirmed ? "CONFIRMED" : "PENDING", null, ts));
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
