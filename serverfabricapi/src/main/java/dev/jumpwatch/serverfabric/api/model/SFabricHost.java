package dev.jumpwatch.serverfabric.api.model;

public record SFabricHost(
        String id,
        String baseUrl,
        String connectHost,
        boolean available
) {}