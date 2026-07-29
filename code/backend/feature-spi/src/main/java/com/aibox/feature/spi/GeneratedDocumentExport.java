package com.aibox.feature.spi;

public record GeneratedDocumentExport(
        String contentField,
        String fileName,
        String mediaType,
        byte[] content
) {
    public GeneratedDocumentExport {
        if (contentField == null || contentField.isBlank()) {
            throw new IllegalArgumentException("contentField is required");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType is required");
        }
        content = content == null ? new byte[0] : content.clone();
        if (content.length == 0) {
            throw new IllegalArgumentException("content is required");
        }
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
