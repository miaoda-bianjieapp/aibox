package com.aibox.feature.spi;

public record TextGenerationResponse(
        String text,
        String provider,
        String model,
        String providerRequestId,
        Integer inputTokens,
        Integer outputTokens
) {
}

