package com.aibox.feature.spi;

public record ImageExpansionResponse(
        ImageGenerationResponse generation,
        int sourceWidth,
        int sourceHeight,
        int targetWidth,
        int targetHeight
) {
    public ImageExpansionResponse {
        if (generation == null) {
            throw new IllegalArgumentException("generation is required");
        }
    }
}
