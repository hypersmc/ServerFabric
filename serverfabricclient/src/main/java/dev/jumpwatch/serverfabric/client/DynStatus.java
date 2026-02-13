package dev.jumpwatch.serverfabric.client;

import java.util.List;

public record DynStatus(List<Instance> instances) {
    public record Instance(String name, int port, String state, String hostId) {}
}
