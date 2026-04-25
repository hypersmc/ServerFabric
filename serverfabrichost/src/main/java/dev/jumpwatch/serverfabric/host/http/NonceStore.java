package dev.jumpwatch.serverfabric.host.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NonceStore {

    private static final long CLEANUP_INTERVAL_MS = 60_000L;

    private final long ttlMs;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private volatile long lastCleanupAtMs = 0L;

    public NonceStore(long ttlSeconds) {
        this.ttlMs = Math.max(1L, ttlSeconds) * 1000L;
    }

    public boolean markIfNew(String key) {
        long now = System.currentTimeMillis();
        cleanupIfNeeded(now);

        Long existing = seen.get(key);
        if (existing != null && existing > now) {
            return false;
        }

        seen.put(key, now + ttlMs);
        return true;
    }

    private void cleanupIfNeeded(long now) {
        if ((now - lastCleanupAtMs) < CLEANUP_INTERVAL_MS) {
            return;
        }

        lastCleanupAtMs = now;

        for (var it = seen.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue() <= now) {
                it.remove();
            }
        }
    }
}