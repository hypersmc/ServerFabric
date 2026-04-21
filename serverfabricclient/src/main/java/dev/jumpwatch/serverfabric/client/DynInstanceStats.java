package dev.jumpwatch.serverfabric.client;

public record DynInstanceStats(
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