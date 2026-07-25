package com.aibox.platform.asset;

import com.aibox.feature.spi.DocumentExtractionResult;
import com.aibox.feature.spi.FeatureValidationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetDocumentContentExtractorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesUtf8BomCsvWithQuotedLineBreaks() throws IOException {
        Path path = temporaryDirectory.resolve("data.csv");
        Files.writeString(
                path,
                "\uFEFFname,note\nAlice,\"line 1\nline 2\"\n",
                StandardCharsets.UTF_8
        );
        UUID assetId = UUID.randomUUID();
        AssetDocumentContentExtractor extractor = extractor(
                assetId,
                path,
                "data.csv",
                "text/csv"
        );

        DocumentExtractionResult result = extractor.extract(assetId, 10_000);

        assertThat(result.format()).isEqualTo("csv");
        assertThat(result.text()).contains("第 1 行\tname\tnote");
        assertThat(result.text()).contains("第 2 行\tAlice\tline 1\\nline 2");
    }

    @Test
    void rejectsNonUtf8Csv() throws IOException {
        Path path = temporaryDirectory.resolve("gbk.csv");
        Files.write(path, new byte[]{(byte) 0xC4, (byte) 0xE3, (byte) 0xBA, (byte) 0xC3});
        UUID assetId = UUID.randomUUID();
        AssetDocumentContentExtractor extractor = extractor(
                assetId,
                path,
                "gbk.csv",
                "text/csv"
        );

        assertThatThrownBy(() -> extractor.extract(assetId, 10_000))
                .isInstanceOf(FeatureValidationException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void readsOnlyVisibleWorkbookSheetsAndCachedValues() throws IOException {
        Path path = temporaryDirectory.resolve("report.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream output = Files.newOutputStream(path)) {
            Sheet visible = workbook.createSheet("销售数据");
            Row header = visible.createRow(0);
            header.createCell(0).setCellValue("地区");
            header.createCell(1).setCellValue("销售额");
            Row data = visible.createRow(1);
            data.createCell(0).setCellValue("华东");
            Cell formula = data.createCell(1);
            formula.setCellFormula("20+22");
            formula.setCellValue(42);

            workbook.createSheet("空白表");
            Sheet hidden = workbook.createSheet("内部参数");
            hidden.createRow(0).createCell(0).setCellValue("不得输出");
            workbook.setSheetHidden(workbook.getSheetIndex(hidden), true);
            workbook.write(output);
        }
        UUID assetId = UUID.randomUUID();
        AssetDocumentContentExtractor extractor = extractor(
                assetId,
                path,
                "report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );

        DocumentExtractionResult result = extractor.extract(assetId, 10_000);

        assertThat(result.sheetCount()).isEqualTo(2);
        assertThat(result.text()).contains("## 工作表：销售数据");
        assertThat(result.text()).contains("地区\t销售额");
        assertThat(result.text()).contains("华东\t42");
        assertThat(result.text()).contains("## 工作表：空白表");
        assertThat(result.text()).contains("（无非空单元格）");
        assertThat(result.text()).doesNotContain("不得输出");
        assertThat(result.text()).doesNotContain("20+22");
    }

    @Test
    void rendersBlankPdfPagesForSelectedModelOcr() throws IOException {
        Path path = temporaryDirectory.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(path.toFile());
        }
        UUID assetId = UUID.randomUUID();
        AssetDocumentContentExtractor extractor = extractor(
                assetId,
                path,
                "scan.pdf",
                "application/pdf"
        );

        DocumentExtractionResult result = extractor.extract(assetId, 10_000);

        assertThat(result.requiresOcr()).isTrue();
        assertThat(result.ocrPageNumbers()).containsExactly(1);
        assertThat(result.ocrPageImages()).singleElement().satisfies(image -> {
            assertThat(image.mediaType()).isEqualTo("image/jpeg");
            assertThat(image.content()).isNotEmpty();
        });
    }

    @Test
    void rejectsNormalizedContentBeyondFeatureLimit() throws IOException {
        Path path = temporaryDirectory.resolve("large.csv");
        Files.writeString(path, "header\n1234567890\n", StandardCharsets.UTF_8);
        UUID assetId = UUID.randomUUID();
        AssetDocumentContentExtractor extractor = extractor(
                assetId,
                path,
                "large.csv",
                "text/csv"
        );

        assertThatThrownBy(() -> extractor.extract(assetId, 5))
                .isInstanceOf(FeatureValidationException.class)
                .hasMessageContaining("15 万字符");
    }

    @Test
    void readsUtf8MarkdownAndNormalizesJson() throws IOException {
        UUID markdownId = UUID.randomUUID();
        Path markdown = temporaryDirectory.resolve("notes.md");
        Files.writeString(
                markdown,
                "\uFEFF# 项目记录\n\n- 已完成联调\n",
                StandardCharsets.UTF_8
        );
        DocumentExtractionResult markdownResult = extractor(
                markdownId,
                markdown,
                "notes.md",
                "text/markdown"
        ).extract(markdownId, 10_000);

        UUID jsonId = UUID.randomUUID();
        Path json = temporaryDirectory.resolve("data.json");
        Files.writeString(
                json,
                "{\"project\":\"元作\",\"done\":true}",
                StandardCharsets.UTF_8
        );
        DocumentExtractionResult jsonResult = extractor(
                jsonId,
                json,
                "data.json",
                "application/json"
        ).extract(jsonId, 10_000);

        assertThat(markdownResult.format()).isEqualTo("md");
        assertThat(markdownResult.text()).startsWith("# 项目记录");
        assertThat(jsonResult.format()).isEqualTo("json");
        assertThat(jsonResult.text()).contains("\"project\" : \"元作\"");
        assertThat(jsonResult.text()).contains("\"done\" : true");
    }

    @Test
    void extractsPowerPointSlidesInDocumentOrder() throws IOException {
        Path path = temporaryDirectory.resolve("review.pptx");
        try (XMLSlideShow presentation = new XMLSlideShow();
             OutputStream output = Files.newOutputStream(path)) {
            XSLFSlide first = presentation.createSlide();
            first.createTextBox().setText("第一部分 项目背景");
            XSLFSlide second = presentation.createSlide();
            second.createTextBox().setText("第二部分 后续行动");
            presentation.write(output);
        }
        UUID assetId = UUID.randomUUID();

        DocumentExtractionResult result = extractor(
                assetId,
                path,
                "review.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        ).extract(assetId, 10_000);

        assertThat(result.format()).isEqualTo("pptx");
        assertThat(result.text()).contains("第一部分 项目背景");
        assertThat(result.text()).contains("第二部分 后续行动");
        assertThat(result.text().indexOf("第一部分"))
                .isLessThan(result.text().indexOf("第二部分"));
    }

    private static AssetDocumentContentExtractor extractor(
            UUID assetId,
            Path path,
            String name,
            String mediaType
    ) {
        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = new AssetService.AssetView(
                assetId,
                name,
                mediaType,
                path.toFile().length(),
                "sha256",
                Instant.parse("2026-07-25T00:00:00Z")
        );
        when(assetService.openForPreview(assetId)).thenReturn(
                new AssetService.AssetStoredFile(asset, path)
        );
        return new AssetDocumentContentExtractor(assetService);
    }
}
