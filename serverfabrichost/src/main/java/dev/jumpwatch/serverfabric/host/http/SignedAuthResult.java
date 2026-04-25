package dev.jumpwatch.serverfabric.host.http;

public record SignedAuthResult(
        boolean success,
        boolean headersPresent,
        String reason,
        String keyId
) {
    public static SignedAuthResult missing() {
        return new SignedAuthResult(false, false, "missing", null);
    }

    public static SignedAuthResult ok(String keyId) {
        return new SignedAuthResult(true, true, "ok", keyId);
    }

    public static SignedAuthResult fail(String reason, String keyId) {
        return new SignedAuthResult(false, true, reason, keyId);
    }
}