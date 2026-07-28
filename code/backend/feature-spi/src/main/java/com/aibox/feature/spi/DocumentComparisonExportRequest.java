package com.aibox.feature.spi;

import java.util.UUID;

public record DocumentComparisonExportRequest(
        UUID baselineAssetId,
        String baselineFileName,
        String mode,
        DocumentComparisonResponse comparison
) {
    public DocumentComparisonExportRequest {
        baselineFileName = baselineFileName == null ? "" : baselineFileName;
        mode = mode == null ? "auto" : mode;
    }
}
