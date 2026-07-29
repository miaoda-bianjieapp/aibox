package com.aibox.features.document.dataanalysis;

import com.aibox.feature.spi.TabularAnalysisDataset;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

final class DataAnalysisOutputWriter {

    byte[] writeReport(
            String sourceFileName,
            String focus,
            TabularAnalysisDataset dataset,
            DataAnalysisModelResult modelResult,
            List<DataAnalysisChartRenderer.RenderedChart> charts
    ) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            writeOverview(workbook, sourceFileName, focus, dataset, modelResult, header);
            writeColumns(workbook, dataset, header);
            writeAnomalies(workbook, dataset, modelResult, header);
            writeChartData(workbook, charts, header);
            writeCharts(workbook, charts, header);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成 XLSX 分析报告", exception);
        }
    }

    private void writeOverview(
            XSSFWorkbook workbook,
            String sourceFileName,
            String focus,
            TabularAnalysisDataset dataset,
            DataAnalysisModelResult modelResult,
            CellStyle header
    ) {
        Sheet sheet = workbook.createSheet("分析概览");
        String[][] values = {
                {"源文件", sourceFileName},
                {"文件格式", dataset.format().toUpperCase()},
                {"可见工作表", Integer.toString(dataset.sheets().size())},
                {"数据行数", Integer.toString(dataset.totalRows())},
                {"非空单元格", Integer.toString(dataset.totalNonEmptyCells())},
                {"异常数量", Integer.toString(dataset.anomalies().size())},
                {"关注重点", focus == null || focus.isBlank() ? "自动分析" : focus}
        };
        int rowNumber = 0;
        for (String[] value : values) {
            Row row = sheet.createRow(rowNumber++);
            writeCell(row, 0, value[0], header);
            writeCell(row, 1, value[1], null);
        }
        rowNumber++;
        writeCell(sheet.createRow(rowNumber++), 0, "分析摘要", header);
        Row summary = sheet.createRow(rowNumber++);
        writeCell(summary, 0, modelResult.summaryMarkdown(), null);
        sheet.addMergedRegion(new CellRangeAddress(rowNumber - 1, rowNumber + 2, 0, 5));
        rowNumber += 3;
        writeHeader(
                sheet.createRow(rowNumber++),
                header,
                List.of("结论", "说明", "证据")
        );
        for (DataAnalysisModelResult.Conclusion conclusion : modelResult.conclusions()) {
            Row row = sheet.createRow(rowNumber++);
            writeCell(row, 0, conclusion.title(), null);
            writeCell(row, 1, conclusion.detail(), null);
            writeCell(row, 2, String.join("；", conclusion.evidence()), null);
        }
        setWidths(sheet, List.of(24, 68, 68, 16, 16, 16));
        sheet.createFreezePane(0, 1);
    }

    private void writeColumns(
            XSSFWorkbook workbook,
            TabularAnalysisDataset dataset,
            CellStyle header
    ) {
        Sheet sheet = workbook.createSheet("字段统计");
        writeHeader(
                sheet.createRow(0),
                header,
                List.of(
                        "工作表", "字段", "类型", "非空数", "缺失数", "不同值",
                        "最小值", "最大值", "平均值", "中位数", "Q1", "Q3", "高频值"
                )
        );
        int rowNumber = 1;
        for (TabularAnalysisDataset.SheetProfile profile : dataset.sheets()) {
            for (TabularAnalysisDataset.ColumnProfile column : profile.columns()) {
                Row row = sheet.createRow(rowNumber++);
                int cell = 0;
                writeCell(row, cell++, profile.name(), null);
                writeCell(row, cell++, column.name(), null);
                writeCell(row, cell++, column.type(), null);
                writeNumber(row, cell++, column.nonEmptyCount());
                writeNumber(row, cell++, column.missingCount());
                writeNumber(row, cell++, column.distinctCount());
                writeNullableNumber(row, cell++, column.minimum());
                writeNullableNumber(row, cell++, column.maximum());
                writeNullableNumber(row, cell++, column.mean());
                writeNullableNumber(row, cell++, column.median());
                writeNullableNumber(row, cell++, column.firstQuartile());
                writeNullableNumber(row, cell++, column.thirdQuartile());
                writeCell(
                        row,
                        cell,
                        column.topValues().stream()
                                .map(value -> value.value() + " (" + value.count() + ")")
                                .reduce((left, right) -> left + "；" + right)
                                .orElse(""),
                        null
                );
            }
        }
        setWidths(sheet, List.of(24, 24, 14, 12, 12, 12, 16, 16, 16, 16, 16, 16, 48));
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowNumber - 1), 0, 12));
    }

    private void writeAnomalies(
            XSSFWorkbook workbook,
            TabularAnalysisDataset dataset,
            DataAnalysisModelResult modelResult,
            CellStyle header
    ) {
        Sheet sheet = workbook.createSheet("异常明细");
        writeHeader(
                sheet.createRow(0),
                header,
                List.of(
                        "ID", "级别", "类型", "工作表", "字段", "行号",
                        "异常", "证据", "解释", "建议"
                )
        );
        int rowNumber = 1;
        for (TabularAnalysisDataset.Anomaly anomaly : dataset.anomalies()) {
            DataAnalysisModelResult.AnomalyNote note =
                    modelResult.anomalyNotes().get(anomaly.id());
            Row row = sheet.createRow(rowNumber++);
            writeCell(row, 0, anomaly.id(), null);
            writeCell(row, 1, anomaly.severity(), null);
            writeCell(row, 2, anomaly.type(), null);
            writeCell(row, 3, anomaly.sheetName(), null);
            writeCell(row, 4, anomaly.columnName(), null);
            if (anomaly.rowNumber() != null) writeNumber(row, 5, anomaly.rowNumber());
            writeCell(row, 6, anomaly.description(), null);
            writeCell(row, 7, anomaly.evidence(), null);
            writeCell(row, 8, note == null ? "" : note.interpretation(), null);
            writeCell(row, 9, note == null ? "" : note.suggestion(), null);
        }
        setWidths(sheet, List.of(10, 12, 22, 22, 22, 10, 42, 48, 48, 48));
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowNumber - 1), 0, 9));
    }

    private void writeChartData(
            XSSFWorkbook workbook,
            List<DataAnalysisChartRenderer.RenderedChart> charts,
            CellStyle header
    ) {
        Sheet sheet = workbook.createSheet("图表数据");
        int rowNumber = 0;
        for (DataAnalysisChartRenderer.RenderedChart rendered : charts) {
            TabularAnalysisDataset.ChartCandidate chart = rendered.candidate();
            writeCell(sheet.createRow(rowNumber++), 0, chart.title(), header);
            writeHeader(
                    sheet.createRow(rowNumber++),
                    header,
                    List.of(chart.categoryLabel(), chart.valueLabel())
            );
            for (int index = 0; index < chart.categories().size(); index++) {
                Row row = sheet.createRow(rowNumber++);
                writeCell(row, 0, chart.categories().get(index), null);
                writeNullableNumber(row, 1, chart.values().get(index));
            }
            rowNumber += 2;
        }
        setWidths(sheet, List.of(36, 22));
    }

    private void writeCharts(
            XSSFWorkbook workbook,
            List<DataAnalysisChartRenderer.RenderedChart> charts,
            CellStyle header
    ) {
        Sheet sheet = workbook.createSheet("图表");
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        CreationHelper helper = workbook.getCreationHelper();
        int rowNumber = 0;
        for (DataAnalysisChartRenderer.RenderedChart rendered : charts) {
            TabularAnalysisDataset.ChartCandidate chart = rendered.candidate();
            writeCell(sheet.createRow(rowNumber), 0, chart.title(), header);
            writeCell(sheet.createRow(rowNumber + 1), 0, chart.aggregation(), null);
            int picture = workbook.addPicture(
                    rendered.content(),
                    XSSFWorkbook.PICTURE_TYPE_PNG
            );
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setCol1(0);
            anchor.setRow1(rowNumber + 2);
            anchor.setCol2(10);
            anchor.setRow2(rowNumber + 28);
            drawing.createPicture(anchor, picture);
            rowNumber += 30;
        }
        setWidths(sheet, List.of(22, 16, 16, 16, 16, 16, 16, 16, 16, 16));
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
            writeCell(row, index, values.get(index), style);
        }
    }

    private static void writeCell(Row row, int index, String value, CellStyle style) {
        Cell cell = row.createCell(index);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) cell.setCellStyle(style);
    }

    private static void writeNumber(Row row, int index, Number value) {
        row.createCell(index).setCellValue(value.doubleValue());
    }

    private static void writeNullableNumber(Row row, int index, Double value) {
        if (value != null && Double.isFinite(value)) {
            row.createCell(index).setCellValue(value);
        }
    }

    private static void setWidths(Sheet sheet, List<Integer> widths) {
        for (int index = 0; index < widths.size(); index++) {
            sheet.setColumnWidth(index, Math.min(255, widths.get(index)) * 256);
        }
    }
}
