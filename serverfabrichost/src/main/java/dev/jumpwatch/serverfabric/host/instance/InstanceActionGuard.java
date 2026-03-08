package dev.jumpwatch.serverfabric.host.instance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class InstanceActionGuard {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void withLock(String instanceName, IoRunnable action) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(instanceName, k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } finally {
            try {
                if (!lock.hasQueuedThreads()) {
                    locks.remove(instanceName, lock);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public <T> T withLock(String instanceName, IoSupplier<T> action) throws IOException {
        ReentrantLock lock = locks.computeIfAbsent(instanceName, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } finally {
            try {
                if (!lock.hasQueuedThreads()) {
                    locks.remove(instanceName, lock);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    @FunctionalInterface
    public interface IoRunnable {
        void run() throws IOException;
    }

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }
}
