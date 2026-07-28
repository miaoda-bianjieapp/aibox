package com.aibox.platform.document;

import com.aibox.feature.spi.DocumentCitation;
import com.aibox.feature.spi.DocumentComparisonExportRequest;
import com.aibox.feature.spi.DocumentComparisonExportResult;
import com.aibox.feature.spi.DocumentComparisonExporter;
import com.aibox.feature.spi.DocumentComparisonResponse;
import com.aibox.feature.spi.GeneratedDocumentExport;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.platform.asset.AssetService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFComment;
import org.apache.poi.xwpf.usermodel.XWPFComments;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMarkupRange;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public final class PlatformDocumentComparisonExporter
        implements DocumentComparisonExporter {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final AssetService assetService;

    public PlatformDocumentComparisonExporter(AssetService assetService) {
        this.assetService = assetService;
    }

    @Override
    public DocumentComparisonExportResult export(
            DocumentComparisonExportRequest request
    ) {
        List<GeneratedDocumentExport> exports = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            exports.add(new GeneratedDocumentExport(
                    "excelAssetId",
                    "多文档对比报告.xlsx",
                    XLSX_MEDIA_TYPE,
                    writeExcel(request)
            ));
        } catch (RuntimeException exception) {
            warnings.add("Excel 报告生成失败，正文对比结果仍可正常查看");
        }

        String extension = extension(request.baselineFileName());
        if (request.baselineAssetId() != null
                && Set.of(".docx", ".pdf").contains(extension)) {
            try {
                ModelAsset baseline = assetService.readForModel(
                        request.baselineAssetId()
                );
                AnnotatedExport annotated = ".docx".equals(extension)
                        ? annotateDocx(request, baseline)
                        : annotatePdf(request, baseline);
                exports.add(new GeneratedDocumentExport(
                        "annotatedBaselineAssetId",
                        annotated.fileName(),
                        annotated.mediaType(),
                        annotated.content()
                ));
            } catch (RuntimeException exception) {
                warnings.add("基准文档标注版生成失败，原始文档和正文结果未受影响");
            }
        }
        return new DocumentComparisonExportResult(exports, warnings);
    }

    private static byte[] writeExcel(DocumentComparisonExportRequest request) {
        DocumentComparisonResponse comparison = request.comparison();
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            writeOverview(workbook, request, header);
            writePairwise(workbook, comparison, header);
            writeConsensus(workbook, comparison, header);
            writeRisks(workbook, comparison, header);
            writeSources(workbook, comparison, header);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to generate comparison workbook", exception);
        }
    }

    private static void writeOverview(
            XSSFWorkbook workbook,
            DocumentComparisonExportRequest request,
            CellStyle header
    ) {
        Sheet sheet = workbook.createSheet("对比概览");
        List<List<String>> rows = List.of(
                List.of("对比模式", request.mode()),
                List.of("识别模式", request.comparison().detectedMode()),
                List.of("是否包含基准", request.baselineAssetId() == null ? "否" : "是"),
                List.of("对比结论", request.comparison().summary()),
                List.of("警告", String.join("；", request.comparison().warnings()))
        );
        for (int index = 0; index < rows.size(); index++) {
            Row row = sheet.createRow(index);
            Cell key = row.createCell(0);
            key.setCellValue(rows.get(index).get(0));
            key.setCellStyle(header);
            row.createCell(1).setCellValue(cellText(rows.get(index).get(1)));
        }
        setWidths(sheet, 20, 100);
    }

    private static void writePairwise(
            XSSFWorkbook workbook,
            DocumentComparisonResponse comparison,
            CellStyle header
    ) {
        int number = 0;
        Set<String> names = new LinkedHashSet<>();
        for (DocumentComparisonResponse.PairwiseComparison pair
                : comparison.pairwiseComparisons()) {
            number++;
            String name = uniqueSheetName(
                    "差异-" + pair.comparisonFileName(),
                    number,
                    names
            );
            Sheet sheet = workbook.createSheet(name);
            writeHeader(
                    sheet.createRow(0),
                    header,
                    List.of(
                            "主题", "变化类型", "基准内容",
                            "对比内容", "影响", "来源"
                    )
            );
            int rowNumber = 1;
            for (DocumentComparisonResponse.Difference difference
                    : pair.differences()) {
                Row row = sheet.createRow(rowNumber++);
                writeCells(row, List.of(
                        difference.topic(),
                        changeTypeLabel(difference.changeType()),
                        difference.baselineContent(),
                        difference.comparisonContent(),
                        difference.impact(),
                        markers(difference.citationMarkers())
                ));
            }
            sheet.createFreezePane(0, 1);
            setWidths(sheet, 24, 14, 48, 48, 48, 20);
        }
    }

    private static void writeConsensus(
            XSSFWorkbook workbook,
            DocumentComparisonResponse comparison,
            CellStyle header
    ) {
        Sheet sheet = workbook.createSheet("综合结论");
        writeHeader(
                sheet.createRow(0),
                header,
                List.of("主题", "各文档内容", "共同点", "主要差异", "影响", "来源")
        );
        int rowNumber = 1;
        for (DocumentComparisonResponse.ConsensusFinding finding
                : comparison.crossDocumentConclusion().findings()) {
            String statements = finding.documentStatements().stream()
                    .map(item -> item.fileName() + "：" + item.content())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            Row row = sheet.createRow(rowNumber++);
            writeCells(row, List.of(
                    finding.topic(),
                    statements,
                    finding.commonality(),
                    finding.difference(),
                    finding.impact(),
                    markers(finding.citationMarkers())
            ));
        }
        sheet.createFreezePane(0, 1);
        setWidths(sheet, 24, 60, 40, 48, 48, 20);
    }

    private static void writeRisks(
            XSSFWorkbook workbook,
            DocumentComparisonResponse comparison,
            CellStyle header
    ) {
        Sheet sheet = workbook.createSheet("风险清单");
        writeHeader(
                sheet.createRow(0),
                header,
                List.of("级别", "风险", "依据", "建议", "影响文档", "来源")
        );
        int rowNumber = 1;
        for (DocumentComparisonResponse.Risk risk : comparison.risks()) {
            Row row = sheet.createRow(rowNumber++);
            writeCells(row, List.of(
                    severityLabel(risk.severity()),
                    risk.title(),
                    risk.basis(),
                    risk.recommendation(),
                    risk.affectedAssetIds().stream()
                            .map(Object::toString)
                            .reduce((left, right) -> left + "\n" + right)
                            .orElse(""),
                    markers(risk.citationMarkers())
            ));
        }
        sheet.createFreezePane(0, 1);
        setWidths(sheet, 12, 30, 56, 56, 40, 20);
    }

    private static void writeSources(
            XSSFWorkbook workbook,
            DocumentComparisonResponse comparison,
            CellStyle header
    ) {
        Sheet sheet = workbook.createSheet("来源");
        writeHeader(
                sheet.createRow(0),
                header,
                List.of("标记", "文件", "位置", "摘录")
        );
        int rowNumber = 1;
        for (DocumentCitation citation : comparison.citations()) {
            Row row = sheet.createRow(rowNumber++);
            writeCells(row, List.of(
                    citation.marker(),
                    citation.fileName(),
                    locatorText(citation.locator()),
                    citation.excerpt()
            ));
        }
        sheet.createFreezePane(0, 1);
        setWidths(sheet, 12, 40, 30, 100);
    }

    private static AnnotatedExport annotateDocx(
            DocumentComparisonExportRequest request,
            ModelAsset baseline
    ) {
        Map<Integer, List<String>> notes = paragraphNotes(request);
        try (XWPFDocument document = new XWPFDocument(
                new ByteArrayInputStream(baseline.content())
        );
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFComments comments = document.getDocComments();
            if (comments == null) comments = document.createComments();
            BigInteger commentId = nextCommentId(comments);
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            for (Map.Entry<Integer, List<String>> entry : notes.entrySet()) {
                int paragraphIndex = entry.getKey() - 1;
                if (paragraphIndex < 0 || paragraphIndex >= paragraphs.size()) continue;
                XWPFParagraph paragraph = paragraphs.get(paragraphIndex);
                paragraph.getRuns().forEach(run -> run.setTextHighlightColor("yellow"));
                if (paragraph.getRuns().isEmpty()) {
                    XWPFRun run = paragraph.createRun();
                    run.setTextHighlightColor("yellow");
                }
                addComment(
                        paragraph,
                        comments,
                        commentId,
                        String.join("\n\n", entry.getValue())
                );
                commentId = commentId.add(BigInteger.ONE);
            }
            document.write(output);
            return new AnnotatedExport(
                    annotatedFileName(request.baselineFileName(), ".docx"),
                    DOCX_MEDIA_TYPE,
                    output.toByteArray()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to annotate DOCX", exception);
        }
    }

    private static AnnotatedExport annotatePdf(
            DocumentComparisonExportRequest request,
            ModelAsset baseline
    ) {
        Map<Integer, List<String>> notes = pageNotes(request);
        try (PDDocument document = Loader.loadPDF(baseline.content());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (Map.Entry<Integer, List<String>> entry : notes.entrySet()) {
                int pageIndex = entry.getKey() - 1;
                if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) continue;
                PDPage page = document.getPage(pageIndex);
                PDRectangle box = page.getCropBox();
                PDAnnotationText annotation = new PDAnnotationText();
                annotation.setName("Comment");
                annotation.setContents(String.join("\n\n", entry.getValue()));
                annotation.setColor(new PDColor(
                        new float[]{1.0f, 0.78f, 0.12f},
                        PDDeviceRGB.INSTANCE
                ));
                annotation.setRectangle(new PDRectangle(
                        Math.max(box.getLowerLeftX(), box.getUpperRightX() - 34),
                        Math.max(box.getLowerLeftY(), box.getUpperRightY() - 34),
                        28,
                        28
                ));
                annotation.setPage(page);
                page.getAnnotations().add(annotation);
            }
            document.save(output);
            return new AnnotatedExport(
                    annotatedFileName(request.baselineFileName(), ".pdf"),
                    "application/pdf",
                    output.toByteArray()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to annotate PDF", exception);
        }
    }

    private static Map<Integer, List<String>> paragraphNotes(
            DocumentComparisonExportRequest request
    ) {
        return locationNotes(request, "WORD_PARAGRAPH", "paragraphStart");
    }

    private static Map<Integer, List<String>> pageNotes(
            DocumentComparisonExportRequest request
    ) {
        return locationNotes(request, "PDF_PAGE", "pageNumber");
    }

    private static Map<Integer, List<String>> locationNotes(
            DocumentComparisonExportRequest request,
            String locatorType,
            String locatorField
    ) {
        Map<String, List<String>> noteByMarker = findingNotes(request.comparison());
        LinkedHashMap<Integer, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (DocumentCitation citation : request.comparison().citations()) {
            if (!request.baselineAssetId().equals(citation.assetId())) continue;
            if (!locatorType.equals(citation.locator().get("type"))) continue;
            Object rawLocation = citation.locator().get(locatorField);
            int location = rawLocation instanceof Number number
                    ? number.intValue()
                    : parsePositiveInt(rawLocation);
            if (location <= 0) continue;
            List<String> markerNotes = noteByMarker.get(citation.marker());
            if (markerNotes == null || markerNotes.isEmpty()) continue;
            grouped.computeIfAbsent(location, ignored -> new LinkedHashSet<>())
                    .addAll(markerNotes);
        }
        LinkedHashMap<Integer, List<String>> result = new LinkedHashMap<>();
        grouped.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static Map<String, List<String>> findingNotes(
            DocumentComparisonResponse comparison
    ) {
        LinkedHashMap<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
        for (DocumentComparisonResponse.PairwiseComparison pair
                : comparison.pairwiseComparisons()) {
            for (DocumentComparisonResponse.Difference difference : pair.differences()) {
                String note = "差异：" + difference.topic()
                        + "\n影响：" + difference.impact();
                addFindingNote(values, difference.citationMarkers(), note);
            }
        }
        for (DocumentComparisonResponse.ConsensusFinding finding
                : comparison.crossDocumentConclusion().findings()) {
            String note = "综合结论：" + finding.topic()
                    + "\n影响：" + finding.impact();
            addFindingNote(values, finding.citationMarkers(), note);
        }
        for (DocumentComparisonResponse.Risk risk : comparison.risks()) {
            String note = severityLabel(risk.severity()) + "：" + risk.title()
                    + "\n建议：" + risk.recommendation();
            addFindingNote(values, risk.citationMarkers(), note);
        }
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static void addFindingNote(
            Map<String, LinkedHashSet<String>> target,
            List<String> markers,
            String note
    ) {
        for (String marker : markers) {
            target.computeIfAbsent(marker, ignored -> new LinkedHashSet<>()).add(note);
        }
    }

    private static void addComment(
            XWPFParagraph paragraph,
            XWPFComments comments,
            BigInteger id,
            String text
    ) {
        XWPFComment comment = comments.createComment(id);
        comment.setAuthor("元作 AI");
        comment.setInitials("AI");
        Calendar now = GregorianCalendar.from(
                Instant.now().atZone(java.time.ZoneId.systemDefault())
        );
        comment.setDate(now);
        comment.createParagraph().createRun().setText(abbreviate(text, 2_000));

        CTMarkupRange start = paragraph.getCTP().insertNewCommentRangeStart(0);
        start.setId(id);
        CTMarkupRange end = paragraph.getCTP().addNewCommentRangeEnd();
        end.setId(id);
        paragraph.createRun().getCTR().addNewCommentReference().setId(id);
    }

    private static BigInteger nextCommentId(XWPFComments comments) {
        return comments.getComments().stream()
                .map(XWPFComment::getId)
                .map(PlatformDocumentComparisonExporter::parseBigInteger)
                .max(Comparator.naturalOrder())
                .orElse(BigInteger.valueOf(-1))
                .add(BigInteger.ONE);
    }

    private static BigInteger parseBigInteger(String value) {
        try {
            return new BigInteger(value);
        } catch (RuntimeException ignored) {
            return BigInteger.ZERO;
        }
    }

    private static int parsePositiveInt(Object value) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        return style;
    }

    private static void writeHeader(
            Row row,
            CellStyle style,
            List<String> values
    ) {
        for (int index = 0; index < values.size(); index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(values.get(index));
            cell.setCellStyle(style);
        }
    }

    private static void writeCells(Row row, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            row.createCell(index).setCellValue(cellText(values.get(index)));
        }
    }

    private static String cellText(String value) {
        String normalized = value == null ? "" : value;
        if (!normalized.isEmpty() && "=+-@".indexOf(normalized.charAt(0)) >= 0) {
            return "'" + normalized;
        }
        return normalized;
    }

    private static void setWidths(Sheet sheet, int... widths) {
        for (int index = 0; index < widths.length; index++) {
            sheet.setColumnWidth(index, Math.min(255, widths[index]) * 256);
        }
    }

    private static String uniqueSheetName(
            String requested,
            int number,
            Set<String> used
    ) {
        String fallback = "差异-" + number;
        String base = WorkbookUtil.createSafeSheetName(
                requested == null || requested.isBlank() ? fallback : requested
        );
        if (base == null || base.isBlank()) base = fallback;
        if (base.length() > 31) base = base.substring(0, 31);
        String result = base;
        int suffix = 2;
        while (!used.add(result)) {
            String tail = "-" + suffix++;
            result = base.substring(0, Math.min(base.length(), 31 - tail.length()))
                    + tail;
        }
        return result;
    }

    private static String markers(List<String> values) {
        return String.join(" ", values.stream().map(value -> "[" + value + "]").toList());
    }

    private static String locatorText(Map<String, Object> locator) {
        if (locator == null || locator.isEmpty()) return "";
        return switch (String.valueOf(locator.get("type"))) {
            case "PDF_PAGE" -> "第 " + locator.get("pageNumber") + " 页";
            case "WORD_PARAGRAPH" ->
                    "第 " + locator.get("paragraphStart") + "-"
                            + locator.get("paragraphEnd") + " 段";
            case "EXCEL_ROWS" ->
                    locator.get("sheetName") + " 第 " + locator.get("startRow")
                            + "-" + locator.get("endRow") + " 行";
            case "PPT_SLIDE" -> "第 " + locator.get("slideNumber") + " 页";
            case "TEXT_LINES" ->
                    "第 " + locator.get("startLine") + "-"
                            + locator.get("endLine") + " 行";
            default -> locator.toString();
        };
    }

    private static String changeTypeLabel(String value) {
        return switch (value) {
            case "added" -> "新增";
            case "deleted" -> "删除";
            case "modified" -> "修改";
            case "same" -> "一致";
            default -> "待确认";
        };
    }

    private static String severityLabel(String value) {
        return switch (value) {
            case "HIGH" -> "高风险";
            case "MEDIUM" -> "中风险";
            default -> "低风险";
        };
    }

    private static String annotatedFileName(String original, String extension) {
        String name = original == null || original.isBlank() ? "基准文档" : original.trim();
        int index = name.lastIndexOf('.');
        String base = index > 0 ? name.substring(0, index) : name;
        return safeFileName(base) + "_对比标注" + extension;
    }

    private static String safeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .trim();
    }

    private static String extension(String value) {
        if (value == null) return "";
        int index = value.lastIndexOf('.');
        return index < 0 ? "" : value.substring(index).toLowerCase(Locale.ROOT);
    }

    private static String abbreviate(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private record AnnotatedExport(
            String fileName,
            String mediaType,
            byte[] content
    ) {
    }
}
