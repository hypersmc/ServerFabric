package dev.jumpwatch.serverfabric.host.http;

public record RequestContext(
        String requestId,
        String method,
        String path,
        String remoteIp,
        long startedAtMs
) {}
