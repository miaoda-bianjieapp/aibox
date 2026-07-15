package com.aibox.feature.spi;

import java.util.Map;
import java.util.UUID;

public record AudioTranscriptionRequest(
        UUID tenantId,
        UUID runId,
        String modelAlias,
        String deploymentCode,
        UUID inputAssetId,
        String language,
        String prompt,
        Map<String, Object> metadata
) {
    public AudioTranscriptionRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public AudioTranscriptionRequest(
            UUID tenantId,
            UUID runId,
            String modelAlias,
            UUID inputAssetId,
            String language,
            String prompt,
            Map<String, Object> metadata
    ) {
        this(tenantId, runId, modelAlias, null, inputAssetId, language, prompt, metadata);
    }
}
