package com.aibox.feature.spi;

public record DocumentTranslationUnit(
        String id,
        String text,
        String context
) {
    public DocumentTranslationUnit {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Translation unit id is required");
        }
        text = text == null ? "" : text;
        context = context == null ? "" : context;
        if (text.isBlank()) {
            throw new IllegalArgumentException("Translation unit text is required");
        }
    }
}
