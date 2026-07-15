package com.aibox.feature.spi;

import java.util.List;

public record VideoGenerationResponse(
        List<GeneratedVideo> videos,
        String provider,
        String model,
        String providerRequestId,
        Integer inputUnits,
        Integer outputUnits
) {
    public VideoGenerationResponse {
        videos = videos == null ? List.of() : List.copyOf(videos);
    }
}
