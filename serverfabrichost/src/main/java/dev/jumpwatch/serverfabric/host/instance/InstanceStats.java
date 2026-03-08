package dev.jumpwatch.serverfabric.host.instance;

public record InstanceStats(
        String name,
        String state,
        boolean alive,
        boolean stopping,
        long pid,
        long uptimeMs,
        long startedAtMs,
        long lastOutputAtMs,
        long memoryRssBytes,
        long memoryVirtualBytes,
        long diskUsageBytes
) {}
