package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MultimodalTextGenerationRequest(
        UUID tenantId,
        UUID runId,
        String modelAlias,
        String deploymentCode,
        String systemPrompt,
        String userPrompt,
        List<UUID> inputAssetIds,
        Integer maxOutputTokens,
        Double temperature,
        Map<String, Object> metadata
) {
    public MultimodalTextGenerationRequest {
        inputAssetIds = inputAssetIds == null ? List.of() : List.copyOf(inputAssetIds);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public MultimodalTextGenerationRequest(
            UUID tenantId,
            UUID runId,
            String modelAlias,
            String systemPrompt,
            String userPrompt,
            List<UUID> inputAssetIds,
            Integer maxOutputTokens,
            Double temperature,
            Map<String, Object> metadata
    ) {
        this(tenantId, runId, modelAlias, null, systemPrompt, userPrompt, inputAssetIds,
                maxOutputTokens, temperature, metadata);
    }
}
