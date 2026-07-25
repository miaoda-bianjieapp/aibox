package com.aibox.feature.spi;

import java.util.List;

public record DocumentExtractionResult(
        String text,
        String format,
        int pageCount,
        int sheetCount,
        List<ModelAsset> ocrPageImages,
        List<Integer> ocrPageNumbers
) {
    public DocumentExtractionResult {
        text = text == null ? "" : text;
        format = format == null ? "" : format;
        ocrPageImages = ocrPageImages == null ? List.of() : List.copyOf(ocrPageImages);
        ocrPageNumbers = ocrPageNumbers == null ? List.of() : List.copyOf(ocrPageNumbers);
        if (ocrPageImages.size() != ocrPageNumbers.size()) {
            throw new IllegalArgumentException("OCR page images and page numbers must have the same size");
        }
    }

    public boolean requiresOcr() {
        return !ocrPageImages.isEmpty();
    }
}
