package com.aibox.feature.spi;

import java.util.Map;
import java.util.UUID;

public record DocumentCitation(
        String marker,
        UUID assetId,
        String fileName,
        String excerpt,
        Map<String, Object> locator
) {
    public DocumentCitation {
        locator = locator == null ? Map.of() : Map.copyOf(locator);
    }
}
