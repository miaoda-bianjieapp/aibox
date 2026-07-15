package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;

public record ArtifactDraft(
        String kind,
        String title,
        String mimeType,
        Map<String, Object> content,
        Map<String, Object> metadata,
        List<OutputAssetDraft> outputAssets
) {
    public ArtifactDraft {
        content = content == null ? Map.of() : Map.copyOf(content);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        outputAssets = outputAssets == null ? List.of() : List.copyOf(outputAssets);
    }

    public ArtifactDraft(
            String kind,
            String title,
            String mimeType,
            Map<String, Object> content,
            Map<String, Object> metadata
    ) {
        this(kind, title, mimeType, content, metadata, List.of());
    }
}
