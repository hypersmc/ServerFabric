package dev.jumpwatch.serverfabric.api.model;


public record SFabricCreateResponse(
        String name,
        String hostId,
        int port,
        String template,
        String serverVersion
) {}