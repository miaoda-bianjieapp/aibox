package com.aibox.feature.spi;

import java.util.List;

public record AudioTranscriptionResponse(
        String text,
        List<AudioTranscriptSegment> segments,
        String detectedLanguage,
        Double audioDurationSeconds,
        String provider,
        String model,
        String providerRequestId,
        Integer inputUnits,
        Integer outputUnits
) {
    public AudioTranscriptionResponse {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public AudioTranscriptionResponse(
            String text,
            String provider,
            String model,
            String providerRequestId,
            Integer inputUnits,
            Integer outputUnits
    ) {
        this(text, List.of(), null, null, provider, model, providerRequestId, inputUnits, outputUnits);
    }
}

