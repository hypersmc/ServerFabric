package dev.jumpwatch.serverfabric.proxy;

public record MessagingVersionInfo(
        String product,
        String version,
        int messagingProtocolVersion,
        int minSupportedMessagingProtocolVersion
) {}
