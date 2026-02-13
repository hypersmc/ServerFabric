package dev.jumpwatch.serverfabric.client;

import java.util.List;

public record DynTemplates(List<Item> items) {
    public record Item(String template, String hostId) {}
}
