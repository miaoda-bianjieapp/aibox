package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ImageGenerationRequest(
        UUID tenantId,
        UUID runId,
        String modelAlias,
        String deploymentCode,
        String prompt,
        List<UUID> inputAssetIds,
        String size,
        int count,
        Map<String, Object> metadata
) {
    public ImageGenerationRequest {
        inputAssetIds = inputAssetIds == null ? List.of() : List.copyOf(inputAssetIds);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        count = Math.max(1, count);
    }

    public ImageGenerationRequest(
            UUID tenantId,
            UUID runId,
            String modelAlias,
            String deploymentCode,
            String prompt,
            String size,
            int count,
            Map<String, Object> metadata
    ) {
        this(tenantId, runId, modelAlias, deploymentCode, prompt, List.of(), size, count, metadata);
    }

    public ImageGenerationRequest(
            UUID tenantId,
            UUID runId,
            String modelAlias,
            String prompt,
            String size,
            int count,
            Map<String, Object> metadata
    ) {
        this(tenantId, runId, modelAlias, null, prompt, List.of(), size, count, metadata);
    }
}
