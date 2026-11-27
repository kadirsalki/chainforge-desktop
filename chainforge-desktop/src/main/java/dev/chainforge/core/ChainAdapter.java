package dev.chainforge.core;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Chain-neutral API. Chain-specific address, fee and signing rules stay inside each adapter.
 */
public interface ChainAdapter {
    String family();

    String networkKey();

    CompletableFuture<String> getBalance(String address, String asset);

    CompletableFuture<TransferResult> transfer(String privateKey, String to, BigDecimal amount, String asset);

    CompletableFuture<StatusResult> checkTransactionStatus(String hash);

    /**
     * Incoming transfers to {@code address}, newest first, read from the chain / an indexer.
     * Adapters skip hashes in {@code knownHashes} where that saves requests. The default is "none".
     */
    default CompletableFuture<List<IncomingTx>> fetchIncoming(String address, Set<String> knownHashes) {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * @param asset     {@link #NATIVE} for the network's own coin, otherwise ERC20:&lt;contract&gt;
     * @param timestamp UTC "yyyy-MM-dd HH:mm:ss" (same format SQLite CURRENT_TIMESTAMP uses) or null
     */
    record IncomingTx(String hash, String from, String to, String amount, String asset, String status, String fee, String timestamp, String tokenSymbol) {
        public IncomingTx(String hash, String from, String to, String amount, String asset, String status, String fee, String timestamp) {
            this(hash, from, to, amount, asset, status, fee, timestamp, null);
        }
    }

    String NATIVE = "NATIVE";

    /** Formats epoch seconds like SQLite's CURRENT_TIMESTAMP (UTC, "yyyy-MM-dd HH:mm:ss"). */
    static String utc(long epochSeconds) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochSecond(epochSeconds));
    }

    /**
     * @param error optional human-readable reason when the transfer was included on-chain but failed (null otherwise)
     */
    record TransferResult(String hash, String status, String fee, String error) {
        public TransferResult(String hash, String status, String fee) {
            this(hash, status, fee, null);
        }
    }

    record StatusResult(String status, String fee, String error) {
    }
}