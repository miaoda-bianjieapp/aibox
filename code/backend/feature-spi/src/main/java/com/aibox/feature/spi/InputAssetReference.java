package com.aibox.feature.spi;

import java.util.UUID;

public record InputAssetReference(
        UUID id,
        String fileName,
        String mediaType,
        long sizeBytes
) {
}
