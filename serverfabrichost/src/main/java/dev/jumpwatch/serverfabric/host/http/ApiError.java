package dev.jumpwatch.serverfabric.host.http;

public record ApiError(
        boolean ok,
        String error,
        String message,
        String requestId
) {
    public static ApiError of(String error, String message, String requestId) {
        return new ApiError(false, error, message, requestId);
    }
}
