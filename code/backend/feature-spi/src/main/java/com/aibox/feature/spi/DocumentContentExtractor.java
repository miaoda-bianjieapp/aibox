package com.aibox.feature.spi;

import java.util.UUID;

@FunctionalInterface
public interface DocumentContentExtractor {

    DocumentExtractionResult extract(UUID assetId, int maxCharacters);

    default DocumentExtractionResult extract(
            UUID assetId,
            DocumentExtractionOptions options
    ) {
        return extract(assetId, options.maxCharacters());
    }
}
