package com.aibox.feature.spi;

public record GeneratedImage(
        String sourceUrl,
        String mediaType,
        String revisedPrompt,
        byte[] content
) {
    public GeneratedImage {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}

