package com.aibox.feature.spi;

public final class ModelProviderException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final String providerRequestId;

    public ModelProviderException(String code, String message, boolean retryable) {
        this(code, message, retryable, null, null);
    }

    public ModelProviderException(String code, String message, boolean retryable, Throwable cause) {
        this(code, message, retryable, cause, null);
    }

    public ModelProviderException(
            String code,
            String message,
            boolean retryable,
            Throwable cause,
            String providerRequestId
    ) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
        this.providerRequestId = providerRequestId;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public String providerRequestId() {
        return providerRequestId;
    }

    public ModelProviderException withProviderRequestId(
            String requestId,
            boolean retryableAfterSubmission
    ) {
        if (requestId == null || requestId.isBlank()) return this;
        return new ModelProviderException(
                code, getMessage(), retryableAfterSubmission, getCause(), requestId.trim()
        );
    }
}
