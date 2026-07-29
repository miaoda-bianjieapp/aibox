package com.aibox.feature.spi;

import java.util.UUID;

public record DocumentComparisonExportRequest(
        String exportType,
        UUID baselineAssetId,
        String baselineFileName,
        String mode,
        DocumentComparisonResponse comparison
) {
    public DocumentComparisonExportRequest {
        exportType = DocumentComparisonExports.normalizeType(exportType);
        baselineFileName = baselineFileName == null ? "" : baselineFileName;
        mode = mode == null ? "auto" : mode;
    }
}
