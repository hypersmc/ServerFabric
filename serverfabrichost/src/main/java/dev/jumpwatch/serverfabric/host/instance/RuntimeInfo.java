package dev.jumpwatch.serverfabric.host.instance;

public record RuntimeInfo(
        String name,
        String state,
        boolean alive,
        boolean stopping,
        long startedAtMs,
        long lastOutputAtMs,
        long pid
) {}