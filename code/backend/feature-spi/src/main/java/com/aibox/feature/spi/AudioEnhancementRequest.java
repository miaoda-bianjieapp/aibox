package com.aibox.feature.spi;

import java.util.Map;
import java.util.UUID;

public record AudioEnhancementRequest(
        UUID tenantId,
        UUID runId,
        String modelAlias,
        String deploymentCode,
        UUID inputAssetId,
        boolean keepBackgroundMusic,
        String format,
        Map<String, Object> metadata
) {
    public AudioEnhancementRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
