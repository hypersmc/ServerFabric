package dev.jumpwatch.serverfabric.proxy;

public record HostVersionInfo(
        String product,
        String version,
        int hostApiVersion,
        int minSupportedHostApiVersion
) {}