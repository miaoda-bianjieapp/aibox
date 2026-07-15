package com.aibox.feature.spi;

public record GeneratedVideo(
        String sourceUrl,
        String fileName,
        String mediaType,
        byte[] content
) {
    public GeneratedVideo {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
