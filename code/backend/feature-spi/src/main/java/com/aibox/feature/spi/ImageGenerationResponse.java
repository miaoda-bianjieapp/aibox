package com.aibox.feature.spi;

import java.util.List;

public record ImageGenerationResponse(
        List<GeneratedImage> images,
        String provider,
        String model,
        String providerRequestId,
        Integer inputUnits,
        Integer outputUnits
) {
    public ImageGenerationResponse {
        images = images == null ? List.of() : List.copyOf(images);
    }
}

