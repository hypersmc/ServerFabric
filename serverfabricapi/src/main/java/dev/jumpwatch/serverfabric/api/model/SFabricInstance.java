package dev.jumpwatch.serverfabric.api.model;

import dev.jumpwatch.serverfabric.api.enums.SFabricInstanceState;

public record SFabricInstance(
        String name,
        String hostId,
        int port,
        String template,
        String serverVersion,
        SFabricInstanceState state
) {}