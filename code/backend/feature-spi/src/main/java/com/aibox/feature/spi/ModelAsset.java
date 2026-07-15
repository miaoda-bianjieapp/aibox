package com.aibox.feature.spi;

import java.util.UUID;

public record ModelAsset(UUID id, String fileName, String mediaType, byte[] content) {

    public ModelAsset {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}

