package com.aibox.feature.spi;

import java.util.Map;
import java.util.UUID;

public record TextGenerationRequest(
        UUID tenantId,
        UUID runId,
        String modelAlias,
        String deploymentCode,
        String systemPrompt,
        String userPrompt,
        Integer maxOutputTokens,
        Double temperature,
        Map<String, Object> metadata
) {
    public TextGenerationRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public TextGenerationRequest(
            UUID tenantId,
            UUID runId,
            String modelAlias,
            String systemPrompt,
            String userPrompt,
            Integer maxOutputTokens,
            Double temperature,
            Map<String, Object> metadata
    ) {
        this(tenantId, runId, modelAlias, null, systemPrompt, userPrompt,
                maxOutputTokens, temperature, metadata);
    }
}
