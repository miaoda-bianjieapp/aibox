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
        Map<String, String> selectedModels,
        String selectedModelCode,
        ArtifactReference baseArtifact
) {
    public FeatureExecutionContext {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        inputAssetIds = inputAssetIds == null ? List.of() : List.copyOf(inputAssetIds);
        selectedModels = selectedModels == null ? Map.of() : Map.copyOf(selectedModels);
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
        this(tenantId, userId, taskId, runId, featureCode, featureVersion,
                parameters, inputAssetIds, Map.of(), null, null);
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
                parameters, inputAssetIds, Map.of(), null, baseArtifact);
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
            String selectedModelCode,
            ArtifactReference baseArtifact
    ) {
        this(tenantId, userId, taskId, runId, featureCode, featureVersion,
                parameters, inputAssetIds, Map.of(), selectedModelCode, baseArtifact);
    }

    public String selectedModelCode(ModelCapability capability) {
        String selected = selectedModels.get(capability.name());
        if (selected != null && !selected.isBlank()) return selected;
        return selectedModels.isEmpty() ? selectedModelCode : null;
    }
}
