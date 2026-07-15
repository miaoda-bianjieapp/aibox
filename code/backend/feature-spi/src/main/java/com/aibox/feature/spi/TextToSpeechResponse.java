package com.aibox.feature.spi;

public record TextToSpeechResponse(
        GeneratedAudio audio,
        String provider,
        String model,
        String providerRequestId,
        Integer inputUnits,
        Integer outputUnits
) {
}
