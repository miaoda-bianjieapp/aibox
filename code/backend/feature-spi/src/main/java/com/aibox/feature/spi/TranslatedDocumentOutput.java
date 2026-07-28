package com.aibox.feature.spi;

public record TranslatedDocumentOutput(
        String mediaType,
        byte[] content
) {
    public TranslatedDocumentOutput {
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("Translated document media type is required");
        }
        content = content == null ? new byte[0] : content.clone();
        if (content.length == 0) {
            throw new IllegalArgumentException("Translated document content is empty");
        }
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
