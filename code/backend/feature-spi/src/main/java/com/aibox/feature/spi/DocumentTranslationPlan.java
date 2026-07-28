package com.aibox.feature.spi;

import java.util.List;

public record DocumentTranslationPlan(
        String format,
        int pageCount,
        int translatableCharacters,
        List<DocumentTranslationUnit> textUnits,
        List<DocumentVisualPage> visualPages
) {
    public DocumentTranslationPlan {
        format = format == null ? "" : format;
        textUnits = textUnits == null ? List.of() : List.copyOf(textUnits);
        visualPages = visualPages == null ? List.of() : List.copyOf(visualPages);
        if (translatableCharacters < 0) {
            throw new IllegalArgumentException("Translatable character count cannot be negative");
        }
        if (textUnits.isEmpty() && visualPages.isEmpty()) {
            throw new IllegalArgumentException("Translation plan must contain text or visual pages");
        }
    }
}
