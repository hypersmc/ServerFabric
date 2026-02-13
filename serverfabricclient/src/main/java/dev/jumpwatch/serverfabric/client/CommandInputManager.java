package dev.jumpwatch.serverfabric.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CommandInputManager {
    private final Map<UUID, String> pendingInstance = new ConcurrentHashMap<>();

    public void begin(UUID player, String instanceName) {
        pendingInstance.put(player, instanceName);
    }

    public String consume(UUID player) {
        return pendingInstance.remove(player);
    }

    public boolean isPending(UUID player) {
        return pendingInstance.containsKey(player);
    }

    public void cancel(UUID player) {
        pendingInstance.remove(player);
    }
}