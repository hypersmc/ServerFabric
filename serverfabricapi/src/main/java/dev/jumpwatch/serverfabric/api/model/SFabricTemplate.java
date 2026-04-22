package dev.jumpwatch.serverfabric.api.model;

public record SFabricTemplate(
        String hostId,
        String name,
        String displayName,
        String defaultVersion
) {}