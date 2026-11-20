package dev.chainforge.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide guard: only one simulation batch may submit transfers at a time.
 */
public final class SimulationLock {
    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean tryAcquire() {
        return running.compareAndSet(false, true);
    }

    public void release() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }
}
