package com.aibox.api;

import java.time.Instant;
import java.util.List;

public record ApiError(
        String code,
        String message,
        String traceId,
        Instant timestamp,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {
    }
}

