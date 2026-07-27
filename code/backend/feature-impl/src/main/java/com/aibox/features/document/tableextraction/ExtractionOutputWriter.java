package com.aibox.features.document.tableextraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ExtractionOutputWriter {

    private final ObjectMapper objectMapper;

    ExtractionOutputWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    byte[] writeJson(
            StructuredExtractionResult result,
            String sourceAssetId,
            String sourceFileName,
            int pageCount,
            String extractionMode
    ) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("source", Map.of(
                "assetId", sourceAssetId,
                "fileName", sourceFileName,
                "pageCount", pageCount
        ));
        root.put("extractionMode", extractionMode);
        root.put("tables", result.tables().stream().map(this::tableMap).toList());
        root.put("fields", result.fields().stream().map(this::fieldMap).toList());
        root.put("sourcePages", result.sourcePages());
        root.put("confidence", result.confidence());
        root.put("warnings", result.warnings());
        root.put("partial", result.partial());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成 JSON 提取结果", exception);
        }
    }

    byte[] writeExcel(
            StructuredExtractionResult result,
            String sourceFileName,
            int pageCount,
            String extractionMode
    ) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle percentageStyle = workbook.createCellStyle();
            percentageStyle.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));
            writeInformationSheet(
                    workbook,
                    result,
                    sourceFileName,
                    pageCount,
                    extractionMode,
                    headerStyle,
                    percentageStyle
            );
            if (!result.fields().isEmpty()) {
                writeFieldsSheet(workbook, result.fields(), headerStyle, percentageStyle);
            }
            int tableNumber = 0;
            Set<String> usedNames = new LinkedHashSet<>(List.of("提取信息", "字段提取"));
            for (StructuredExtractionResult.ExtractedTable table : result.tables()) {
                tableNumber++;
                writeTableSheet(
                        workbook,
                        table,
                        uniqueSheetName(table.name(), tableNumber, usedNames),
                        headerStyle
                );
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成 Excel 提取结果", exception);
        }
    }

    private void writeInformationSheet(
            XSSFWorkbook workbook,
            StructuredExtractionResult result,
            String sourceFileName,
            int pageCount,
            String extractionMode,
            CellStyle headerStyle,
            CellStyle percentageStyle
    ) {
        Sheet sheet = workbook.createSheet("提取信息");
        String[][] summary = {
                {"源文件", sourceFileName},
                {"提取模式", extractionMode},
                {"文件页数", Integer.toString(pageCount)},
                {"表格数量", Integer.toString(result.tables().size())},
                {"字段数量", Integer.toString(result.fields().size())},
                {"来源页码", joinPages(result.sourcePages())},
                {"是否部分结果", result.partial() ? "是" : "否"},
                {"警告", String.join("；", result.warnings())}
        };
        for (int index = 0; index < summary.length; index++) {
            Row row = sheet.createRow(index);
            Cell key = row.createCell(0);
            key.setCellValue(summary[index][0]);
            key.setCellStyle(headerStyle);
            row.createCell(1).setCellValue(summary[index][1]);
        }
        Row confidence = sheet.createRow(summary.length);
        Cell confidenceKey = confidence.createCell(0);
        confidenceKey.setCellValue("整体置信度");
        confidenceKey.setCellStyle(headerStyle);
        Cell confidenceValue = confidence.createCell(1);
        confidenceValue.setCellValue(result.confidence());
        confidenceValue.setCellStyle(percentageStyle);

        int rowNumber = summary.length + 2;
        Row header = sheet.createRow(rowNumber++);
        writeHeader(header, headerStyle, List.of("类型", "名称", "来源页码", "置信度", "警告"));
        for (StructuredExtractionResult.ExtractedTable table : result.tables()) {
            Row row = sheet.createRow(rowNumber++);
            row.createCell(0).setCellValue("表格");
            row.createCell(1).setCellValue(table.name());
            row.createCell(2).setCellValue(joinPages(table.sourcePages()));
            Cell cell = row.createCell(3);
            cell.setCellValue(table.confidence());
            cell.setCellStyle(percentageStyle);
            row.createCell(4).setCellValue(String.join("；", table.warnings()));
        }
        for (StructuredExtractionResult.ExtractedField field : result.fields()) {
            Row row = sheet.createRow(rowNumber++);
            row.createCell(0).setCellValue("字段");
            row.createCell(1).setCellValue(field.name());
            row.createCell(2).setCellValue(joinPages(field.sourcePages()));
            Cell cell = row.createCell(3);
            cell.setCellValue(field.confidence());
            cell.setCellStyle(percentageStyle);
            row.createCell(4).setCellValue(String.join("；", field.warnings()));
        }
        setWidths(sheet, List.of(16, 36, 18, 14, 64));
        sheet.createFreezePane(0, summary.length + 3);
    }

    private void writeFieldsSheet(
            XSSFWorkbook workbook,
            List<StructuredExtractionResult.ExtractedField> fields,
            CellStyle headerStyle,
            CellStyle percentageStyle
    ) {
        Sheet sheet = workbook.createSheet("字段提取");
        writeHeader(
                sheet.createRow(0),
                headerStyle,
                List.of("字段", "值", "来源页码", "置信度", "警告")
        );
        int rowNumber = 1;
        for (StructuredExtractionResult.ExtractedField field : fields) {
            Row row = sheet.createRow(rowNumber++);
            row.createCell(0).setCellValue(field.name());
            row.createCell(1).setCellValue(field.value());
            row.createCell(2).setCellValue(joinPages(field.sourcePages()));
            Cell confidence = row.createCell(3);
            confidence.setCellValue(field.confidence());
            confidence.setCellStyle(percentageStyle);
            row.createCell(4).setCellValue(String.join("；", field.warnings()));
        }
        setWidths(sheet, List.of(28, 48, 18, 14, 60));
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                0,
                Math.max(0, rowNumber - 1),
                0,
                4
        ));
    }

    private void writeTableSheet(
            XSSFWorkbook workbook,
            StructuredExtractionResult.ExtractedTable table,
            String sheetName,
            CellStyle headerStyle
    ) {
        Sheet sheet = workbook.createSheet(sheetName);
        writeHeader(sheet.createRow(0), headerStyle, table.columns());
        int rowNumber = 1;
        for (List<String> values : table.rows()) {
            Row row = sheet.createRow(rowNumber++);
            for (int column = 0; column < values.size(); column++) {
                row.createCell(column).setCellValue(values.get(column));
            }
        }
        List<Integer> widths = new ArrayList<>();
        for (int index = 0; index < table.columns().size(); index++) widths.add(24);
        setWidths(sheet, widths);
        sheet.createFreezePane(0, 1);
        if (!table.columns().isEmpty()) {
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0,
                    Math.max(0, rowNumber - 1),
                    0,
                    table.columns().size() - 1
            ));
        }
    }

    private Map<String, Object> tableMap(StructuredExtractionResult.ExtractedTable table) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", table.name());
        value.put("columns", table.columns());
        value.put("rows", table.rows());
        value.put("sourcePages", table.sourcePages());
        value.put("confidence", table.confidence());
        value.put("warnings", table.warnings());
        return value;
    }

    private Map<String, Object> fieldMap(StructuredExtractionResult.ExtractedField field) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", field.name());
        value.put("value", field.value());
        value.put("sourcePages", field.sourcePages());
        value.put("confidence", field.confidence());
        value.put("warnings", field.warnings());
        return value;
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static void writeHeader(Row row, CellStyle style, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            Cell cell = row.createCell(index);
            cell.setCellValue(values.get(index));
            cell.setCellStyle(style);
        }
    }

    private static void setWidths(Sheet sheet, List<Integer> widths) {
        for (int index = 0; index < widths.size(); index++) {
            sheet.setColumnWidth(index, Math.min(255, widths.get(index)) * 256);
        }
    }

    private static String uniqueSheetName(String requested, int number, Set<String> usedNames) {
        String fallback = "表" + number;
        String base = WorkbookUtil.createSafeSheetName(
                requested == null || requested.isBlank() ? fallback : requested.trim()
        );
        if (base == null || base.isBlank()) base = fallback;
        if (base.length() > 31) base = base.substring(0, 31);
        String candidate = base;
        int suffix = 2;
        while (!usedNames.add(candidate)) {
            String tail = "-" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), 31 - tail.length())) + tail;
        }
        return candidate;
    }

    private static String joinPages(List<Integer> pages) {
        return pages.isEmpty()
                ? ""
                : pages.stream().map(String::valueOf).reduce((left, right) -> left + "," + right)
                .orElse("");
    }
}
