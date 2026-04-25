package dev.jumpwatch.serverfabric.host.http;

import java.util.Locale;

public enum AuthMode {
    TOKEN_ONLY,
    TOKEN_OR_SIGNED,
    SIGNED_ONLY;

    public static AuthMode parse(String value) {
        if (value == null || value.isBlank()) {
            return TOKEN_ONLY;
        }

        try {
            return AuthMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return TOKEN_ONLY;
        }
    }
}