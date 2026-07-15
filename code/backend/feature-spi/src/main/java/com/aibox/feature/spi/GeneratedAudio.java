package com.aibox.feature.spi;

public record GeneratedAudio(String fileName, String mediaType, byte[] content) {
    public GeneratedAudio {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
