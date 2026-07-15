package com.aibox.feature.spi;

public record OutputAssetDraft(
        String contentField,
        String fileName,
        String mediaType,
        byte[] content
) {
    public OutputAssetDraft {
        if (contentField == null || contentField.isBlank()) {
            throw new IllegalArgumentException("contentField is required");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        content = content == null ? new byte[0] : content.clone();
        if (content.length == 0) {
            throw new IllegalArgumentException("output asset content is empty");
        }
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}

