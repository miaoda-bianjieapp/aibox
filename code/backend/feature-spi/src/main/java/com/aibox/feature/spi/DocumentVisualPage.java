package com.aibox.feature.spi;

public record DocumentVisualPage(
        int pageNumber,
        ModelAsset image
) {
    public DocumentVisualPage {
        if (pageNumber <= 0) {
            throw new IllegalArgumentException("Visual page number must be positive");
        }
        if (image == null) {
            throw new IllegalArgumentException("Visual page image is required");
        }
    }
}
