package com.aibox.feature.spi;

public record TabularAnalysisLimits(
        int maxVisibleSheets,
        int maxRows,
        int maxColumnsPerSheet,
        int maxNonEmptyCells
) {
    public TabularAnalysisLimits {
        if (maxVisibleSheets <= 0
                || maxRows <= 0
                || maxColumnsPerSheet <= 0
                || maxNonEmptyCells <= 0) {
            throw new IllegalArgumentException("Tabular analysis limits must be positive");
        }
    }
}
