package com.aibox.feature.spi;

public interface DocumentComparisonExporter {

    DocumentComparisonExportResult export(DocumentComparisonExportRequest request);
}
