package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FeatureExecutionContext(
        UUID tenantId,
        UUID userId,
        UUID taskId,
        UUID runId,
        String featureCode,
        int featureVersion,
        Map<String, Object> parameters,
        List<UUID> inputAssetIds,
        String selectedModelCode,
        ArtifactReference baseArtifact
) {
    public FeatureExecutionContext {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        inputAssetIds = inputAssetIds == null ? List.of() : List.copyOf(inputAssetIds);
    }

    public FeatureExecutionContext(
            UUID tenantId,
            UUID userId,
            UUID taskId,
            UUID runId,
            String featureCode,
            int featureVersion,
            Map<String, Object> parameters,
            List<UUID> inputAssetIds
    ) {
        this(tenantId, userId, taskId, runId, featureCode, featureVersion, parameters, inputAssetIds, null, null);
    }

    public FeatureExecutionContext(
            UUID tenantId,
            UUID userId,
            UUID taskId,
            UUID runId,
            String featureCode,
            int featureVersion,
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            ArtifactReference baseArtifact
    ) {
        this(tenantId, userId, taskId, runId, featureCode, featureVersion,
                parameters, inputAssetIds, null, baseArtifact);
    }
}
