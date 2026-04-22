package dev.jumpwatch.serverfabric.host.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class InstanceStatsService {

    private static final long DISK_CACHE_TTL_MS = 30_000L; // 30 seconds

    private final InstanceStore store;
    private final Map<String, DiskCacheEntry> diskCache = new ConcurrentHashMap<>();

    public InstanceStatsService(InstanceStore store) {
        this.store = store;
    }

    public InstanceStats collect(String instanceName, ManagedInstance mi) throws IOException {
        if (mi == null) {
            throw new IOException("Not running: " + instanceName);
        }

        ManagedInstance.RuntimeSnapshot snap = mi.snapshot();

        long pid = snap.pid();
        long rss = -1L;
        long vmem = -1L;

        if (pid > 0) {
            long[] mem = readLinuxMemoryStats(pid);
            rss = mem[0];
            vmem = mem[1];
        }

        Path dir = store.dir(instanceName);
        long disk = Files.isDirectory(dir) ? getCachedDiskUsage(instanceName, dir) : -1L;

        long uptimeMs = 0L;
        if (snap.startedAtMs() > 0) {
            uptimeMs = Math.max(0L, System.currentTimeMillis() - snap.startedAtMs());
        }

        return new InstanceStats(
                snap.name(),
                snap.state().name(),
                snap.alive(),
                snap.stopping(),
                pid,
                uptimeMs,
                snap.startedAtMs(),
                snap.lastOutputAtMs(),
                rss,
                vmem,
                disk
        );
    }

    public void invalidateDiskCache(String instanceName) {
        if (instanceName == null || instanceName.isBlank()) {
            return;
        }
        diskCache.remove(instanceName);
    }

    public void clearDiskCache() {
        diskCache.clear();
    }

    private long getCachedDiskUsage(String instanceName, Path root) {
        long now = System.currentTimeMillis();

        DiskCacheEntry cached = diskCache.get(instanceName);
        if (cached != null && (now - cached.cachedAtMs()) < DISK_CACHE_TTL_MS) {
            return cached.bytes();
        }

        long disk = calculateDiskUsage(root);
        diskCache.put(instanceName, new DiskCacheEntry(disk, now));
        return disk;
    }

    private long[] readLinuxMemoryStats(long pid) {
        Path status = Path.of("/proc", String.valueOf(pid), "status");
        if (!Files.exists(status)) {
            return new long[] { -1L, -1L };
        }

        long rss = -1L;
        long vmem = -1L;

        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("VmRSS:")) {
                    rss = parseKbLineToBytes(line);
                } else if (line.startsWith("VmSize:")) {
                    vmem = parseKbLineToBytes(line);
                }
            }
        } catch (Exception ignored) {
        }

        return new long[] { rss, vmem };
    }

    private long parseKbLineToBytes(String line) {
        // Example: VmRSS:    734520 kB
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) return -1L;

        try {
            long kb = Long.parseLong(parts[1]);
            return kb * 1024L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private long calculateDiskUsage(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return -1L;
        }
    }

    private record DiskCacheEntry(long bytes, long cachedAtMs) {}
}