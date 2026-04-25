package dev.jumpwatch.serverfabric.host.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HostSecurityGuard {

    private static final long FAILED_AUTH_WINDOW_MS = 2 * 60 * 1000L;   // 2 minutes
    private static final int FAILED_AUTH_LIMIT = 5;
    private static final long BAN_DURATION_MS = 15 * 60 * 1000L;        // 15 minutes

    private static final long RATE_WINDOW_MS = 60 * 1000L;              // 1 minute
    private static final long CLEANUP_INTERVAL_MS = 60 * 1000L;         // 1 minute

    private final Map<String, FailedAuthState> failedAuth = new ConcurrentHashMap<>();
    private final Map<String, Long> bannedUntil = new ConcurrentHashMap<>();
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    private final List<CidrRule> trustedCidrs;

    private volatile long lastCleanupAtMs = 0L;

    public HostSecurityGuard(String trustedCidrsCsv) {
        this.trustedCidrs = parseTrustedCidrs(trustedCidrsCsv);
    }

    public boolean isTrusted(String ip) {
        if (ip == null || ip.isBlank() || "-".equals(ip)) {
            return false;
        }

        InetAddress addr;
        try {
            addr = InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            return false;
        }

        for (CidrRule rule : trustedCidrs) {
            if (rule.matches(addr)) {
                return true;
            }
        }

        return false;
    }

    public SecurityDecision checkRequest(String ip, String path) {
        long now = System.currentTimeMillis();
        cleanupIfNeeded(now);

        if (ip == null || ip.isBlank()) {
            ip = "-";
        }

        if (isTrusted(ip)) {
            return new SecurityDecision(true, 200, null, null, 0L);
        }

        Long bannedUntilMs = bannedUntil.get(ip);
        if (bannedUntilMs != null) {
            if (bannedUntilMs > now) {
                long retryAfterSeconds = Math.max(1L, (bannedUntilMs - now + 999L) / 1000L);
                return new SecurityDecision(
                        false,
                        403,
                        "ip_banned",
                        "This IP is temporarily blocked due to repeated failed authentication.",
                        retryAfterSeconds
                );
            } else {
                bannedUntil.remove(ip);
            }
        }

        RouteGroup group = RouteGroup.forPath(path);
        String rateKey = ip + "|" + group.name();

        RateWindow window = rateWindows.computeIfAbsent(rateKey, k -> new RateWindow(now, 0));
        synchronized (window) {
            if ((now - window.windowStartedAtMs) >= RATE_WINDOW_MS) {
                window.windowStartedAtMs = now;
                window.count = 0;
            }

            window.count++;

            int limit = group.limitPerMinute;
            if (window.count > limit) {
                long retryAfterSeconds = Math.max(1L, (RATE_WINDOW_MS - (now - window.windowStartedAtMs) + 999L) / 1000L);
                return new SecurityDecision(
                        false,
                        429,
                        "rate_limited",
                        "Too many requests for route group " + group.name() + ".",
                        retryAfterSeconds
                );
            }
        }

        return new SecurityDecision(true, 200, null, null, 0L);
    }

    public AuthFailureResult recordAuthFailure(String ip) {
        long now = System.currentTimeMillis();
        cleanupIfNeeded(now);

        if (ip == null || ip.isBlank()) {
            ip = "-";
        }

        if (isTrusted(ip)) {
            return new AuthFailureResult(false, 0, 0L, true);
        }

        FailedAuthState state = failedAuth.computeIfAbsent(ip, k -> new FailedAuthState(now, 0));

        synchronized (state) {
            if ((now - state.windowStartedAtMs) >= FAILED_AUTH_WINDOW_MS) {
                state.windowStartedAtMs = now;
                state.count = 0;
            }

            state.count++;

            if (state.count >= FAILED_AUTH_LIMIT) {
                long bannedUntilMs = now + BAN_DURATION_MS;
                bannedUntil.put(ip, bannedUntilMs);
                failedAuth.remove(ip);

                return new AuthFailureResult(
                        true,
                        FAILED_AUTH_LIMIT,
                        Math.max(1L, BAN_DURATION_MS / 1000L),
                        false
                );
            }

            return new AuthFailureResult(
                    false,
                    state.count,
                    0L,
                    false
            );
        }
    }

    public void recordAuthSuccess(String ip) {
        if (ip == null || ip.isBlank()) {
            ip = "-";
        }
        failedAuth.remove(ip);
    }

    private void cleanupIfNeeded(long now) {
        if ((now - lastCleanupAtMs) < CLEANUP_INTERVAL_MS) {
            return;
        }

        lastCleanupAtMs = now;

        for (var it = bannedUntil.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue() <= now) {
                it.remove();
            }
        }

        for (var it = failedAuth.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            FailedAuthState s = entry.getValue();
            if ((now - s.windowStartedAtMs) >= FAILED_AUTH_WINDOW_MS) {
                it.remove();
            }
        }

        for (var it = rateWindows.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            RateWindow s = entry.getValue();
            if ((now - s.windowStartedAtMs) >= (RATE_WINDOW_MS * 2)) {
                it.remove();
            }
        }
    }

    private List<CidrRule> parseTrustedCidrs(String csv) {
        List<CidrRule> rules = new ArrayList<>();

        if (csv == null || csv.isBlank()) {
            return rules;
        }

        for (String raw : csv.split(",")) {
            String part = raw.trim();
            if (part.isEmpty()) continue;

            try {
                rules.add(CidrRule.parse(part));
            } catch (Exception ignored) {
                System.out.println("[ServerFabric-Host] Ignoring invalid trusted CIDR/IP entry: " + part);
            }
        }

        return rules;
    }

    public record SecurityDecision(
            boolean allowed,
            int statusCode,
            String error,
            String message,
            long retryAfterSeconds
    ) {}

    public record AuthFailureResult(
            boolean banned,
            int failures,
            long banSeconds,
            boolean trusted
    ) {}

    private static final class FailedAuthState {
        long windowStartedAtMs;
        int count;

        FailedAuthState(long windowStartedAtMs, int count) {
            this.windowStartedAtMs = windowStartedAtMs;
            this.count = count;
        }
    }

    private static final class RateWindow {
        long windowStartedAtMs;
        int count;

        RateWindow(long windowStartedAtMs, int count) {
            this.windowStartedAtMs = windowStartedAtMs;
            this.count = count;
        }
    }

    private enum RouteGroup {
        READ(120),
        CONTROL(60),
        DANGEROUS(20);

        final int limitPerMinute;

        RouteGroup(int limitPerMinute) {
            this.limitPerMinute = limitPerMinute;
        }

        static RouteGroup forPath(String path) {
            if (path == null) return READ;

            return switch (path) {
                case "/server/create", "/server/delete", "/server/kill" -> DANGEROUS;
                case "/server/start", "/server/stop", "/server/restart",
                     "/server/command", "/server/runtime", "/server/logs", "/server/stats" -> CONTROL;
                default -> READ;
            };
        }
    }

    private static final class CidrRule {
        private final byte[] network;
        private final int prefixLength;
        private final int totalBits;

        private CidrRule(byte[] network, int prefixLength, int totalBits) {
            this.network = network;
            this.prefixLength = prefixLength;
            this.totalBits = totalBits;
        }

        static CidrRule parse(String value) throws UnknownHostException {
            if (value.contains("/")) {
                String[] parts = value.split("/", 2);
                InetAddress addr = InetAddress.getByName(parts[0].trim());
                int prefix = Integer.parseInt(parts[1].trim());

                int totalBits = addr.getAddress().length * 8;
                if (prefix < 0 || prefix > totalBits) {
                    throw new IllegalArgumentException("Invalid prefix length: " + prefix);
                }

                return new CidrRule(addr.getAddress(), prefix, totalBits);
            }

            InetAddress addr = InetAddress.getByName(value.trim());
            int totalBits = addr.getAddress().length * 8;
            return new CidrRule(addr.getAddress(), totalBits, totalBits);
        }

        boolean matches(InetAddress addr) {
            byte[] candidate = addr.getAddress();
            if (candidate.length != network.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }

            if (remainingBits == 0) {
                return true;
            }

            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}