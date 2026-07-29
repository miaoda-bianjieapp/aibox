package com.aibox.feature.spi;

import java.util.UUID;

@FunctionalInterface
public interface TabularAnalysisProcessor {

    TabularAnalysisDataset analyze(UUID assetId, TabularAnalysisLimits limits);
}
