package dev.chainforge.core;

import java.math.BigDecimal;
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

    record TransferResult(String hash, String status, String fee) {
    }

    record StatusResult(String status, String fee, String error) {
    }
}