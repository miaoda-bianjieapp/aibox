package com.aibox.feature.spi;

public record DocumentComparisonExportOption(
        String type,
        String label,
        String fileName,
        String mediaType
) {
    public DocumentComparisonExportOption {
        type = DocumentComparisonExports.normalizeType(type);
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("export label is required");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("export file name is required");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("export media type is required");
        }
    }
}
