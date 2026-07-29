package com.aibox.feature.spi;

public record VisualTranslationBlock(
        double x,
        double y,
        double width,
        double height,
        String text
) {
    public VisualTranslationBlock {
        if (!normalized(x) || !normalized(y)
                || width <= 0 || height <= 0
                || x + width > 1.000001 || y + height > 1.000001) {
            throw new IllegalArgumentException("Visual translation block bounds are invalid");
        }
        text = text == null ? "" : text.trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException("Visual translation block text is required");
        }
    }

    private static boolean normalized(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
