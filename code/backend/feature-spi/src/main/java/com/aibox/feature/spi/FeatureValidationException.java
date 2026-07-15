package com.aibox.feature.spi;

public final class FeatureValidationException extends RuntimeException {

    private final String field;

    public FeatureValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}

