package com.aibox.feature.spi;

public interface DocumentComparisonExporter {

    GeneratedDocumentExport export(DocumentComparisonExportRequest request);
}
