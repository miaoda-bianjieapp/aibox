package com.aibox.feature.spi;

import java.util.List;

public record DocumentExtractionResult(
        String text,
        String format,
        int pageCount,
        int sheetCount,
        List<ModelAsset> visualPageImages,
        List<Integer> visualPageNumbers
) {
    public DocumentExtractionResult {
        text = text == null ? "" : text;
        format = format == null ? "" : format;
        visualPageImages = visualPageImages == null ? List.of() : List.copyOf(visualPageImages);
        visualPageNumbers = visualPageNumbers == null ? List.of() : List.copyOf(visualPageNumbers);
        if (visualPageImages.size() != visualPageNumbers.size()) {
            throw new IllegalArgumentException(
                    "Visual page images and page numbers must have the same size"
            );
        }
    }

    public boolean requiresVision() {
        return !visualPageImages.isEmpty();
    }
}
