package com.aibox.feature.spi;

import java.util.List;

public record VisualPageTranslation(
        int pageNumber,
        List<VisualTranslationBlock> blocks
) {
    public VisualPageTranslation {
        if (pageNumber <= 0) {
            throw new IllegalArgumentException("Translated page number must be positive");
        }
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
