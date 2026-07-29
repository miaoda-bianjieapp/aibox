package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DocumentTranslationProcessor {

    DocumentTranslationPlan prepare(
            UUID assetId,
            int maxCharacters,
            int maxScannedPdfPages
    );

    TranslatedDocumentOutput render(
            UUID assetId,
            DocumentTranslationPlan plan,
            Map<String, String> textTranslations,
            List<VisualPageTranslation> visualTranslations
    );
}
