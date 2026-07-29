package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DocumentComparisonRequest(
        UUID tenantId,
        UUID userId,
        UUID runId,
        UUID taskId,
        UUID baselineAssetId,
        List<UUID> comparisonAssetIds,
        String mode,
        String instructions,
        String textModelAlias,
        String textDeploymentCode,
        String visionModelAlias,
        String visionDeploymentCode,
        Integer maxOutputTokens,
        Map<String, Object> metadata
) {
    public DocumentComparisonRequest {
        comparisonAssetIds = comparisonAssetIds == null
                ? List.of()
                : List.copyOf(comparisonAssetIds);
        mode = mode == null ? "auto" : mode.trim();
        instructions = instructions == null ? "" : instructions.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public List<UUID> allAssetIds() {
        if (baselineAssetId == null) return comparisonAssetIds;
        java.util.ArrayList<UUID> ids = new java.util.ArrayList<>();
        ids.add(baselineAssetId);
        ids.addAll(comparisonAssetIds);
        return List.copyOf(ids);
    }
}
