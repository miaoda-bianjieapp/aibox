package com.aibox.feature.spi;

import java.util.List;

public record DocumentComparisonExportResult(
        List<GeneratedDocumentExport> exports,
        List<String> warnings
) {
    public DocumentComparisonExportResult {
        exports = exports == null ? List.of() : List.copyOf(exports);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
