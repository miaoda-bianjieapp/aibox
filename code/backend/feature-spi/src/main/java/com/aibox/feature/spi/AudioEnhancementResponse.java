package com.aibox.feature.spi;

public record AudioEnhancementResponse(
        GeneratedAudio audio,
        String provider,
        String model,
        String providerRequestId,
        Integer inputUnits,
        Integer outputUnits
) {
}
