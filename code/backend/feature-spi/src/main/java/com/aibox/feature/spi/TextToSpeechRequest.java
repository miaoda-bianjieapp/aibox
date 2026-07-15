package com.aibox.feature.spi;

import java.util.Map;
import java.util.UUID;

public record TextToSpeechRequest(
        UUID tenantId,
        UUID runId,
        String modelAlias,
        String deploymentCode,
        String text,
        String voice,
        Double speed,
        String format,
        Map<String, Object> metadata
) {
    public TextToSpeechRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
