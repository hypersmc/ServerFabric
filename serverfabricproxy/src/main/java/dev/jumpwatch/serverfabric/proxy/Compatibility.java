package dev.jumpwatch.serverfabric.proxy;

public final class Compatibility {
    private Compatibility() {}

    public static boolean isHostApiCompatible(
            int localCurrent,
            int localMinSupported,
            int remoteCurrent,
            int remoteMinSupported
    ) {
        return remoteCurrent >= localMinSupported
                && localCurrent >= remoteMinSupported;
    }
}
