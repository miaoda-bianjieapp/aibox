package com.aibox.feature.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DocumentComparisonExports {

    public static final String EXCEL = "excel";
    public static final String ANNOTATED_BASELINE = "annotatedBaseline";
    public static final String EXCEL_CONTENT_FIELD = "excelAssetId";
    public static final String ANNOTATED_BASELINE_CONTENT_FIELD =
            "annotatedBaselineAssetId";
    public static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private DocumentComparisonExports() {
    }

    public static List<DocumentComparisonExportOption> availableOptions(
            UUID baselineAssetId,
            String baselineFileName,
            DocumentComparisonResponse comparison
    ) {
        List<DocumentComparisonExportOption> result = new ArrayList<>();
        result.add(new DocumentComparisonExportOption(
                EXCEL,
                "导出 Excel 报告",
                "多文档对比报告.xlsx",
                XLSX_MEDIA_TYPE
        ));
        String extension = extension(baselineFileName);
        if (baselineAssetId != null
                && List.of(".docx", ".pdf").contains(extension)
                && hasBaselineNotes(baselineAssetId, extension, comparison)) {
            result.add(new DocumentComparisonExportOption(
                    ANNOTATED_BASELINE,
                    "导出基准文档标注版",
                    annotatedFileName(baselineFileName, extension),
                    ".docx".equals(extension) ? DOCX_MEDIA_TYPE : "application/pdf"
            ));
        }
        return List.copyOf(result);
    }

    public static DocumentComparisonExportOption requireAvailable(
            String exportType,
            UUID baselineAssetId,
            String baselineFileName,
            DocumentComparisonResponse comparison
    ) {
        String normalized = normalizeType(exportType);
        return availableOptions(baselineAssetId, baselineFileName, comparison)
                .stream()
                .filter(option -> option.type().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "The requested document comparison export is unavailable"
                ));
    }

    public static String contentField(String exportType) {
        return switch (normalizeType(exportType)) {
            case EXCEL -> EXCEL_CONTENT_FIELD;
            case ANNOTATED_BASELINE -> ANNOTATED_BASELINE_CONTENT_FIELD;
            default -> throw new IllegalArgumentException(
                    "Unsupported document comparison export type"
            );
        };
    }

    public static String normalizeType(String value) {
        String normalized = value == null ? "" : value.trim();
        if (EXCEL.equals(normalized) || ANNOTATED_BASELINE.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException(
                "Unsupported document comparison export type"
        );
    }

    private static boolean hasBaselineNotes(
            UUID baselineAssetId,
            String extension,
            DocumentComparisonResponse comparison
    ) {
        if (comparison == null) return false;
        String locatorType = ".docx".equals(extension)
                ? "WORD_PARAGRAPH"
                : "PDF_PAGE";
        Map<String, DocumentCitation> citations = new LinkedHashMap<>();
        comparison.citations().forEach(
                citation -> citations.put(citation.marker(), citation)
        );
        Set<String> referencedMarkers = new LinkedHashSet<>();
        comparison.pairwiseComparisons().forEach(pair ->
                pair.differences().forEach(difference ->
                        referencedMarkers.addAll(difference.citationMarkers())
                )
        );
        comparison.crossDocumentConclusion().findings().forEach(finding ->
                referencedMarkers.addAll(finding.citationMarkers())
        );
        comparison.risks().forEach(risk ->
                referencedMarkers.addAll(risk.citationMarkers())
        );
        return referencedMarkers.stream()
                .map(citations::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(citation ->
                        baselineAssetId.equals(citation.assetId())
                                && locatorType.equals(
                                String.valueOf(citation.locator().get("type"))
                        )
                );
    }

    private static String annotatedFileName(String original, String extension) {
        String name = original == null || original.isBlank()
                ? "基准文档"
                : original.trim();
        int index = name.lastIndexOf('.');
        String base = index <= 0 ? name : name.substring(0, index);
        return (base.endsWith("_对比标注") ? base : base + "_对比标注")
                + extension;
    }

    private static String extension(String name) {
        if (name == null) return "";
        int index = name.lastIndexOf('.');
        return index < 0
                ? ""
                : name.substring(index).toLowerCase(Locale.ROOT);
    }
}
