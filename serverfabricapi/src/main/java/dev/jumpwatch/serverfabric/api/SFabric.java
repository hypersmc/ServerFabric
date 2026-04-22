package dev.jumpwatch.serverfabric.api;

public final class SFabric {

    private static volatile SFabricAPI api;

    private SFabric() {}

    public static boolean isAvailable() {
        return api != null;
    }

    public static SFabricAPI get() {
        if (api == null) {
            throw new IllegalStateException("SFabricAPI is not available yet.");
        }
        return api;
    }

    public static void set(SFabricAPI impl) {
        api = impl;
    }

    public static void clear() {
        api = null;
    }
}