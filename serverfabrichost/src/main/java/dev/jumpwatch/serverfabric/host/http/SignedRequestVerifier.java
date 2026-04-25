package dev.jumpwatch.serverfabric.host.http;

import com.sun.net.httpserver.HttpExchange;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

public final class SignedRequestVerifier {

    private static final String H_KEY_ID = "X-SFabric-KeyId";
    private static final String H_TIMESTAMP = "X-SFabric-Timestamp";
    private static final String H_NONCE = "X-SFabric-Nonce";
    private static final String H_BODY_SHA256 = "X-SFabric-Body-SHA256";
    private static final String H_SIGNATURE = "X-SFabric-Signature";

    private final String configuredKeyId;
    private final byte[] secretBytes;
    private final long clockSkewSeconds;
    private final NonceStore nonceStore;

    public SignedRequestVerifier(
            String configuredKeyId,
            String configuredSecret,
            long clockSkewSeconds,
            long nonceTtlSeconds
    ) {
        this.configuredKeyId = configuredKeyId == null ? "" : configuredKeyId.trim();
        this.secretBytes = configuredSecret == null
                ? new byte[0]
                : configuredSecret.getBytes(StandardCharsets.UTF_8);
        this.clockSkewSeconds = Math.max(1L, clockSkewSeconds);
        this.nonceStore = new NonceStore(Math.max(1L, nonceTtlSeconds));
    }

    public SignedAuthResult verify(RequestContext ctx, HttpExchange ex, String rawBody) {
        String keyId = header(ex, H_KEY_ID);
        String timestamp = header(ex, H_TIMESTAMP);
        String nonce = header(ex, H_NONCE);
        String bodySha256 = header(ex, H_BODY_SHA256);
        String signature = header(ex, H_SIGNATURE);

        boolean anySignedHeadersPresent =
                notBlank(keyId) || notBlank(timestamp) || notBlank(nonce)
                        || notBlank(bodySha256) || notBlank(signature);

        if (!anySignedHeadersPresent) {
            return SignedAuthResult.missing();
        }

        if (configuredKeyId.isBlank() || secretBytes.length == 0) {
            return SignedAuthResult.fail("not_configured", keyId);
        }

        if (!notBlank(keyId) || !notBlank(timestamp) || !notBlank(nonce)
                || !notBlank(bodySha256) || !notBlank(signature)) {
            return SignedAuthResult.fail("missing_headers", keyId);
        }

        if (!configuredKeyId.equals(keyId)) {
            return SignedAuthResult.fail("bad_key", keyId);
        }

        long requestEpochSeconds;
        try {
            requestEpochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return SignedAuthResult.fail("bad_timestamp", keyId);
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - requestEpochSeconds) > clockSkewSeconds) {
            return SignedAuthResult.fail("timestamp_out_of_window", keyId);
        }

        String actualBodyHash = sha256Hex(rawBody == null ? "" : rawBody);
        if (!secureEqualsHex(bodySha256, actualBodyHash)) {
            return SignedAuthResult.fail("bad_body_hash", keyId);
        }

        String canonical = buildCanonical(
                ex.getRequestMethod(),
                ex.getRequestURI().getPath(),
                timestamp,
                nonce,
                actualBodyHash
        );

        String expectedSignature;
        try {
            expectedSignature = hmacSha256Hex(secretBytes, canonical);
        } catch (Exception e) {
            return SignedAuthResult.fail("signature_error", keyId);
        }

        if (!secureEqualsHex(signature, expectedSignature)) {
            return SignedAuthResult.fail("bad_signature", keyId);
        }

        String nonceKey = keyId + ":" + nonce;
        if (!nonceStore.markIfNew(nonceKey)) {
            return SignedAuthResult.fail("replay", keyId);
        }

        return SignedAuthResult.ok(keyId);
    }

    private String buildCanonical(
            String method,
            String path,
            String timestamp,
            String nonce,
            String bodySha256
    ) {
        return (method == null ? "" : method.trim().toUpperCase(Locale.ROOT))
                + "\n" + (path == null ? "" : path)
                + "\n" + timestamp
                + "\n" + nonce
                + "\n" + bodySha256;
    }

    private static String header(HttpExchange ex, String name) {
        String value = ex.getRequestHeaders().getFirst(name);
        return value == null ? null : value.trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hmacSha256Hex(byte[] secret, String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] out = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(out);
    }

    private static boolean secureEqualsHex(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }

        byte[] a = provided.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        byte[] b = expected.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a, b);
    }
}