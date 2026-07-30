package com.aibox.platform.asset;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssetPreviewServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void previewsGb18030TextFiles() throws Exception {
        UUID assetId = UUID.randomUUID();
        String expected = "微观尽头\r\n这是一份中文文本。";
        Path file = tempDirectory.resolve("novel.txt");
        Files.write(file, expected.getBytes(Charset.forName("GB18030")));

        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = new AssetService.AssetView(
                assetId,
                "novel.txt",
                "text/plain",
                Files.size(file),
                "sha256",
                Instant.now(),
                AssetOrigin.USER_UPLOAD.name(),
                AssetMediaCategory.DOCUMENT.name(),
                "READY",
                true,
                0,
                null
        );
        when(assetService.openForPreview(assetId))
                .thenReturn(new AssetService.AssetStoredFile(asset, file));

        AssetPreviewService.PreviewDescriptor preview =
                new AssetPreviewService(
                        assetService,
                        mock(OfficePreviewConverter.class),
                        mock(SpreadsheetPreviewReader.class)
                ).preview(assetId);

        assertThat(preview.kind()).isEqualTo("TEXT");
        assertThat(preview.text()).isEqualTo(expected);
        assertThat(preview.truncated()).isFalse();
        assertThat(preview.fallback()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {".doc", ".docx", ".ppt", ".pptx"})
    void returnsGeneratedPdfDescriptorForOfficeFiles(String extension) throws Exception {
        UUID assetId = UUID.randomUUID();
        String sha256 = "a".repeat(64);
        Path source = tempDirectory.resolve("document" + extension);
        Files.writeString(source, "office");
        Path converted = tempDirectory.resolve("document.pdf");
        Files.write(converted, new byte[]{1, 2, 3});

        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = documentAsset(
                assetId,
                "document" + extension,
                sha256,
                source
        );
        when(assetService.openForPreview(assetId))
                .thenReturn(new AssetService.AssetStoredFile(asset, source));
        OfficePreviewConverter converter = mock(OfficePreviewConverter.class);
        when(converter.convert(source, extension, sha256)).thenReturn(Optional.of(converted));

        AssetPreviewService.PreviewDescriptor preview =
                new AssetPreviewService(
                        assetService,
                        converter,
                        mock(SpreadsheetPreviewReader.class)
                ).preview(assetId);

        assertThat(preview.kind()).isEqualTo("PDF");
        assertThat(preview.mediaType()).isEqualTo("application/pdf");
        assertThat(preview.contentUrl())
                .isEqualTo("/api/v1/assets/" + assetId + "/preview/content");
        assertThat(preview.text()).isNull();
        assertThat(preview.fallback()).isFalse();
    }

    @Test
    void returnsGeneratedPreviewContentForWordFiles() throws Exception {
        UUID assetId = UUID.randomUUID();
        String sha256 = "b".repeat(64);
        Path source = tempDirectory.resolve("report.docx");
        Files.writeString(source, "word");
        Path converted = tempDirectory.resolve("report.pdf");
        Files.write(converted, "%PDF-1.7\npreview".getBytes());

        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = documentAsset(assetId, "report.docx", sha256, source);
        when(assetService.openForPreview(assetId))
                .thenReturn(new AssetService.AssetStoredFile(asset, source));
        OfficePreviewConverter converter = mock(OfficePreviewConverter.class);
        when(converter.convert(source, ".docx", sha256)).thenReturn(Optional.of(converted));

        AssetPreviewService.PreviewContent content =
                new AssetPreviewService(
                        assetService,
                        converter,
                        mock(SpreadsheetPreviewReader.class)
                ).previewContent(assetId);

        assertThat(content.mediaType()).isEqualTo("application/pdf");
        assertThat(content.fileName()).isEqualTo("report.pdf");
        assertThat(content.sizeBytes()).isEqualTo(Files.size(converted));
        assertThat(content.resource().getFile()).isEqualTo(converted.toFile());
    }

    @Test
    void fallsBackToExtractedTextWhenWordConversionFails() throws Exception {
        UUID assetId = UUID.randomUUID();
        String sha256 = "c".repeat(64);
        Path source = tempDirectory.resolve("fallback.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Fallback Word text");
            try (var output = Files.newOutputStream(source)) {
                document.write(output);
            }
        }

        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = documentAsset(assetId, "fallback.docx", sha256, source);
        when(assetService.openForPreview(assetId))
                .thenReturn(new AssetService.AssetStoredFile(asset, source));
        OfficePreviewConverter converter = mock(OfficePreviewConverter.class);
        when(converter.convert(source, ".docx", sha256)).thenReturn(Optional.empty());

        AssetPreviewService.PreviewDescriptor preview =
                new AssetPreviewService(
                        assetService,
                        converter,
                        mock(SpreadsheetPreviewReader.class)
                ).preview(assetId);

        assertThat(preview.kind()).isEqualTo("TEXT");
        assertThat(preview.text()).contains("Fallback Word text");
        assertThat(preview.fallback()).isTrue();
    }

    @Test
    void returnsStructuredExcelPreviewWithoutEagerLayoutConversion() throws Exception {
        UUID assetId = UUID.randomUUID();
        String sha256 = "d".repeat(64);
        Path source = tempDirectory.resolve("sales.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("销售");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("地区");
            header.createCell(1).setCellValue("销售额");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("华东");
            row.createCell(1).setCellValue(42);
            try (var output = Files.newOutputStream(source)) {
                workbook.write(output);
            }
        }
        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = documentAsset(assetId, "sales.xlsx", sha256, source);
        when(assetService.openForPreview(assetId))
                .thenReturn(new AssetService.AssetStoredFile(asset, source));
        OfficePreviewConverter converter = mock(OfficePreviewConverter.class);

        AssetPreviewService.PreviewDescriptor preview =
                new AssetPreviewService(
                        assetService,
                        converter,
                        new SpreadsheetPreviewReader()
                ).preview(assetId);

        assertThat(preview.kind()).isEqualTo("SPREADSHEET");
        assertThat(preview.contentUrl())
                .isEqualTo("/api/v1/assets/" + assetId + "/preview/content");
        assertThat(preview.spreadsheet().sheets()).hasSize(1);
        assertThat(preview.spreadsheet().sheets().get(0).columns())
                .containsExactly("地区", "销售额");
        assertThat(preview.spreadsheet().sheets().get(0).rows().get(0).cells())
                .containsExactly("华东", "42");
        assertThat(preview.fallback()).isFalse();
        verifyNoInteractions(converter);
    }

    @Test
    void returnsStructuredCsvPreviewInsteadOfFlattenedText() throws Exception {
        UUID assetId = UUID.randomUUID();
        Path source = tempDirectory.resolve("customers.csv");
        Files.writeString(
                source,
                "name,note\nAlice,\"north, region\"\n",
                StandardCharsets.UTF_8
        );

        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = documentAsset(
                assetId,
                "customers.csv",
                "f".repeat(64),
                source
        );
        when(assetService.openForPreview(assetId))
                .thenReturn(new AssetService.AssetStoredFile(asset, source));

        AssetPreviewService.PreviewDescriptor preview = new AssetPreviewService(
                assetService,
                mock(OfficePreviewConverter.class),
                new SpreadsheetPreviewReader()
        ).preview(assetId);

        assertThat(preview.kind()).isEqualTo("SPREADSHEET");
        assertThat(preview.contentUrl()).isNull();
        assertThat(preview.spreadsheet().sheets().get(0).columns())
                .containsExactly("name", "note");
        assertThat(preview.spreadsheet().sheets().get(0).rows().get(0).cells())
                .containsExactly("Alice", "north, region");
    }

    @Test
    void fallsBackToExtractedTextWhenPowerPointConversionFails() throws Exception {
        UUID assetId = UUID.randomUUID();
        String sha256 = "e".repeat(64);
        Path source = tempDirectory.resolve("fallback.pptx");
        try (XMLSlideShow slideshow = new XMLSlideShow()) {
            XSLFTextBox textBox = slideshow.createSlide().createTextBox();
            textBox.setText("Fallback slide text");
            try (var output = Files.newOutputStream(source)) {
                slideshow.write(output);
            }
        }

        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = documentAsset(assetId, "fallback.pptx", sha256, source);
        when(assetService.openForPreview(assetId))
                .thenReturn(new AssetService.AssetStoredFile(asset, source));
        OfficePreviewConverter converter = mock(OfficePreviewConverter.class);
        when(converter.convert(source, ".pptx", sha256)).thenReturn(Optional.empty());

        AssetPreviewService.PreviewDescriptor preview =
                new AssetPreviewService(
                        assetService,
                        converter,
                        mock(SpreadsheetPreviewReader.class)
                ).preview(assetId);

        assertThat(preview.kind()).isEqualTo("TEXT");
        assertThat(preview.text()).contains("Fallback slide text");
        assertThat(preview.fallback()).isTrue();
    }

    private static AssetService.AssetView documentAsset(
            UUID assetId,
            String name,
            String sha256,
            Path file
    ) throws Exception {
        return new AssetService.AssetView(
                assetId,
                name,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                Files.size(file),
                sha256,
                Instant.now(),
                AssetOrigin.USER_UPLOAD.name(),
                AssetMediaCategory.DOCUMENT.name(),
                "READY",
                true,
                0,
                null
        );
    }
}
