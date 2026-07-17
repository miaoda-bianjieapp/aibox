package com.aibox.feature.spi;

import java.util.UUID;

public record InputAssetReference(
        UUID id,
        String fileName,
        String mediaType,
        long sizeBytes,
        Integer width,
        Integer height
) {
    public InputAssetReference(
            UUID id,
            String fileName,
            String mediaType,
            long sizeBytes
    ) {
        this(id, fileName, mediaType, sizeBytes, null, null);
    }
}
