package com.aibox.platform.asset;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SpreadsheetPreviewReaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsCsvAsColumnsAndRowsWithoutFlatteningQuotedCells() throws Exception {
        Path csv = temporaryDirectory.resolve("sales.csv");
        Files.writeString(
                csv,
                "\uFEFFname,note,amount\n"
                        + "Alice,\"north, region\",42\n"
                        + "Bob,\"line 1\nline 2\",18\n",
                StandardCharsets.UTF_8
        );

        SpreadsheetPreviewReader.SpreadsheetPreview preview =
                new SpreadsheetPreviewReader().read(csv, ".csv");

        assertThat(preview.sheets()).hasSize(1);
        SpreadsheetPreviewReader.SheetPreview sheet = preview.sheets().get(0);
        assertThat(sheet.name()).isEqualTo("CSV");
        assertThat(sheet.headerRowNumber()).isEqualTo(1);
        assertThat(sheet.columns()).containsExactly("name", "note", "amount");
        assertThat(sheet.rows()).extracting(SpreadsheetPreviewReader.RowPreview::rowNumber)
                .containsExactly(2, 3);
        assertThat(sheet.rows().get(0).cells())
                .containsExactly("Alice", "north, region", "42");
        assertThat(sheet.rows().get(1).cells())
                .containsExactly("Bob", "line 1\nline 2", "18");
        assertThat(preview.truncated()).isFalse();
    }

    @Test
    void readsVisibleExcelSheetsWithSourceRowsAndCachedFormulaValues() throws Exception {
        Path workbookPath = temporaryDirectory.resolve("sales.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream output = Files.newOutputStream(workbookPath)) {
            Sheet sales = workbook.createSheet("销售");
            Row header = sales.createRow(1);
            header.createCell(0).setCellValue("地区");
            header.createCell(1).setCellValue("销售额");
            Row data = sales.createRow(3);
            data.createCell(0).setCellValue("华东");
            Cell formula = data.createCell(1);
            formula.setCellFormula("20+22");
            formula.setCellValue(42);

            Sheet hidden = workbook.createSheet("内部参数");
            hidden.createRow(0).createCell(0).setCellValue("不得展示");
            workbook.setSheetHidden(workbook.getSheetIndex(hidden), true);
            workbook.write(output);
        }

        SpreadsheetPreviewReader.SpreadsheetPreview preview =
                new SpreadsheetPreviewReader().read(workbookPath, ".xlsx");

        assertThat(preview.sheets()).hasSize(1);
        SpreadsheetPreviewReader.SheetPreview sheet = preview.sheets().get(0);
        assertThat(sheet.name()).isEqualTo("销售");
        assertThat(sheet.headerRowNumber()).isEqualTo(2);
        assertThat(sheet.columns()).containsExactly("地区", "销售额");
        assertThat(sheet.rows()).hasSize(1);
        assertThat(sheet.rows().get(0).rowNumber()).isEqualTo(4);
        assertThat(sheet.rows().get(0).cells()).containsExactly("华东", "42");
        assertThat(preview.truncated()).isFalse();
    }

    @Test
    void readsGb18030CsvAndDetectsSemicolonDelimiter() throws Exception {
        Path csv = temporaryDirectory.resolve("customers.csv");
        Files.write(
                csv,
                "姓名;城市;备注\n张三;上海;重点客户\n".getBytes(Charset.forName("GB18030"))
        );

        SpreadsheetPreviewReader.SpreadsheetPreview preview =
                new SpreadsheetPreviewReader().read(csv, ".csv");

        SpreadsheetPreviewReader.SheetPreview sheet = preview.sheets().get(0);
        assertThat(sheet.columns()).containsExactly("姓名", "城市", "备注");
        assertThat(sheet.rows()).hasSize(1);
        assertThat(sheet.rows().get(0).cells())
                .containsExactly("张三", "上海", "重点客户");
    }

    @Test
    void marksPreviewAsTruncatedWhenACellExceedsTheDisplayLimit() throws Exception {
        Path csv = temporaryDirectory.resolve("long-cell.csv");
        Files.writeString(
                csv,
                "name,note\nAlice," + "x".repeat(
                        SpreadsheetPreviewReader.MAX_CELL_CHARACTERS + 20
                ) + "\n",
                StandardCharsets.UTF_8
        );

        SpreadsheetPreviewReader.SpreadsheetPreview preview =
                new SpreadsheetPreviewReader().read(csv, ".csv");

        assertThat(preview.truncated()).isTrue();
        assertThat(preview.sheets().get(0).truncated()).isTrue();
        assertThat(preview.sheets().get(0).rows().get(0).cells().get(1))
                .endsWith("...");
    }
}
