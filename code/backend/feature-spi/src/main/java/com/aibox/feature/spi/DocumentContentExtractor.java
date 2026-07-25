package com.aibox.feature.spi;

import java.util.UUID;

@FunctionalInterface
public interface DocumentContentExtractor {

    DocumentExtractionResult extract(UUID assetId, int maxCharacters);
}
