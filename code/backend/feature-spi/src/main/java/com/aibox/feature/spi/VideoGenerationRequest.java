package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record VideoGenerationRequest(
        UUID tenantId,
        UUID runId,
        String modelAlias,
        String deploymentCode,
        String prompt,
        List<UUID> inputAssetIds,
        Integer durationSeconds,
        String aspectRatio,
        String resolution,
        int count,
        Map<String, Object> metadata
) {
    public VideoGenerationRequest {
        inputAssetIds = inputAssetIds == null ? List.of() : List.copyOf(inputAssetIds);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        count = Math.max(1, count);
    }
}
