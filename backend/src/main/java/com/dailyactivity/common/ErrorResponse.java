package com.dailyactivity.common;

import java.time.Instant;

public record ErrorResponse(String code, String message, String path, Instant timestamp, String requestId) {
    public static ErrorResponse of(ApiException exception, String path, String requestId) {
        return new ErrorResponse(exception.code(), exception.getMessage(), path, Instant.now(), requestId);
    }
}
