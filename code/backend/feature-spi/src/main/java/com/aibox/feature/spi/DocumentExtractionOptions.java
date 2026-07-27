package com.aibox.feature.spi;

public record DocumentExtractionOptions(
        int maxCharacters,
        PdfVisualMode pdfVisualMode,
        int maxPdfPages
) {
    public DocumentExtractionOptions {
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("maxCharacters must be positive");
        }
        pdfVisualMode = pdfVisualMode == null ? PdfVisualMode.OCR_ONLY : pdfVisualMode;
        if (maxPdfPages <= 0) {
            throw new IllegalArgumentException("maxPdfPages must be positive");
        }
    }

    public static DocumentExtractionOptions textAndOcr(int maxCharacters) {
        return new DocumentExtractionOptions(
                maxCharacters,
                PdfVisualMode.OCR_ONLY,
                Integer.MAX_VALUE
        );
    }

    public enum PdfVisualMode {
        OCR_ONLY,
        ALL_PAGES
    }
}
