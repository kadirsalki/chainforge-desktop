package dev.chainforge.bitcoin;

import dev.chainforge.core.ChainAdapter;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * Extension point: Bitcoin UTXO selection, fee-rate policy, PSBT and signing belong here.
 */
public final class BitcoinAdapter implements ChainAdapter {
    public String family() {
        return "BITCOIN";
    }

    public String networkKey() {
        return "bitcoin-signet";
    }

    public CompletableFuture<String> getBalance(String a, String asset) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Bitcoin adapter planned, not enabled."));
    }

    public CompletableFuture<StatusResult> checkTransactionStatus(String hash) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Bitcoin transaction status lookup is not enabled."));
    }

    public CompletableFuture<TransferResult> transfer(String k, String to, BigDecimal n, String asset) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Bitcoin adapter planned, not enabled."));
    }
}