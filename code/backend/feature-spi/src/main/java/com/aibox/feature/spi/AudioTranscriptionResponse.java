package com.aibox.feature.spi;

public record AudioTranscriptionResponse(
        String text,
        String provider,
        String model,
        String providerRequestId,
        Integer inputUnits,
        Integer outputUnits
) {
}

