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
        List<ModelAsset> inlineInputAssets,
        UUID maskAssetId,
        boolean preserveUnmaskedPixels,
        String size,
        int count,
        Map<String, Object> metadata
) {
    public ImageGenerationRequest {
        inputAssetIds = inputAssetIds == null ? List.of() : List.copyOf(inputAssetIds);
        inlineInputAssets = inlineInputAssets == null ? List.of() : List.copyOf(inlineInputAssets);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        count = Math.max(1, count);
    }

    public ImageGenerationRequest(
            UUID tenantId,
            UUID runId,
            String modelAlias,
            String deploymentCode,
            String prompt,
            List<UUID> inputAssetIds,
            List<ModelAsset> inlineInputAssets,
            String size,
            int count,
            Map<String, Object> metadata
    ) {
        this(
                tenantId,
                runId,
                modelAlias,
                deploymentCode,
                prompt,
                inputAssetIds,
                inlineInputAssets,
                null,
                false,
                size,
                count,
                metadata
        );
    }

    public ImageGenerationRequest(
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
        this(
                tenantId,
                runId,
                modelAlias,
                deploymentCode,
                prompt,
                inputAssetIds,
                List.of(),
                null,
                false,
                size,
                count,
                metadata
        );
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
        this(
                tenantId,
                runId,
                modelAlias,
                deploymentCode,
                prompt,
                List.of(),
                List.of(),
                null,
                false,
                size,
                count,
                metadata
        );
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
        this(
                tenantId,
                runId,
                modelAlias,
                null,
                prompt,
                List.of(),
                List.of(),
                null,
                false,
                size,
                count,
                metadata
        );
    }
}
