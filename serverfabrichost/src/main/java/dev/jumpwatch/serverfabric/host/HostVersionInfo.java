package dev.jumpwatch.serverfabric.host;

public record HostVersionInfo(
        String product,
        String version,
        int hostApiVersion,
        int minSupportedHostApiVersion
) {}
