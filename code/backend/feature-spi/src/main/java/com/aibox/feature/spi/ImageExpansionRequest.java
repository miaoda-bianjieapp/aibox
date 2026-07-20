package com.aibox.feature.spi;

import java.util.Map;
import java.util.UUID;

public record ImageExpansionRequest(
        UUID tenantId,
        UUID runId,
        String modelAlias,
        String deploymentCode,
        String prompt,
        UUID inputAssetId,
        String aspectRatio,
        double expansionScale,
        ImagePreservationMode preservationMode,
        Map<String, Object> metadata
) {
    public ImageExpansionRequest {
        if (inputAssetId == null) {
            throw new IllegalArgumentException("inputAssetId is required");
        }
        if (aspectRatio == null || aspectRatio.isBlank()) {
            throw new IllegalArgumentException("aspectRatio is required");
        }
        if (!Double.isFinite(expansionScale) || expansionScale < 1.0) {
            throw new IllegalArgumentException("expansionScale must be finite and at least 1.0");
        }
        preservationMode = preservationMode == null ? ImagePreservationMode.STRICT : preservationMode;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
