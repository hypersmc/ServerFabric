package dev.jumpwatch.serverfabric.api.model;

public record SFabricCreateRequest(
        String hostId,
        String template,
        String instanceName,
        String versionOverride
) {}