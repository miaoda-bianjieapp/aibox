package com.aibox.feature.spi;

import java.util.Map;
import java.util.UUID;

public record ArtifactReference(
        UUID id,
        int versionNumber,
        String kind,
        String mimeType,
        Map<String, Object> content,
        Map<String, Object> metadata
) {
    public ArtifactReference {
        content = content == null ? Map.of() : Map.copyOf(content);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

