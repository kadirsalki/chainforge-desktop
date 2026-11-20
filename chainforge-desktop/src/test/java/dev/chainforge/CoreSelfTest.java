package dev.chainforge;

import dev.chainforge.core.SimulationLock;
import dev.chainforge.solana.SolanaKeys;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Dependency-free smoke tests for core safety primitives.
 */
public final class CoreSelfTest {
    public static void main(String[] args) {
        SimulationLock lock = new SimulationLock();
        if (!lock.tryAcquire()) throw new AssertionError("first simulation lock must succeed");
        if (lock.tryAcquire()) throw new AssertionError("concurrent simulation must be rejected");
        if (!lock.isRunning()) throw new AssertionError("lock must report running");
        lock.release();
        if (lock.isRunning() || !lock.tryAcquire()) throw new AssertionError("lock must be reusable after release");
        lock.release();

        SecureRandom random = new SecureRandom();
        for (int length : new int[]{1, 32, 64}) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            if (!Arrays.equals(bytes, SolanaKeys.decode(SolanaKeys.encode(bytes))))
                throw new AssertionError("Base58 round-trip failed for " + length + " bytes");
        }
        System.out.println("PASS simulation lock: concurrent batch rejected; released lock reusable");
        System.out.println("PASS Solana Base58: 1/32/64-byte round trips");
    }
}
