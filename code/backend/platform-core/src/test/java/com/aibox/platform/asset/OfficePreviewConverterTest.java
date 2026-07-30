package com.aibox.platform.asset;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OfficePreviewConverterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void canBeCreatedBySpringWithConfiguredProperties() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource(
                            "officePreviewTest",
                            Map.of(
                                    "yuanzuo.asset.storage-path", tempDirectory.toString(),
                                    "yuanzuo.asset.office-preview.libreoffice-path", "",
                                    "yuanzuo.asset.office-preview.conversion-timeout-ms", "5000"
                            )
                    )
            );
            context.register(OfficePreviewConverter.class);

            context.refresh();

            assertThat(context.getBean(OfficePreviewConverter.class)).isNotNull();
        }
    }

    @ParameterizedTest
    @CsvSource({
            ".doc, writer_pdf_Export",
            ".docx, writer_pdf_Export",
            ".xls, calc_pdf_Export",
            ".xlsx, calc_pdf_Export",
            ".ppt, impress_pdf_Export",
            ".pptx, impress_pdf_Export"
    })
    void convertsSupportedOfficeFormatsWithMatchingLibreOfficeFilter(
            String extension,
            String expectedFilter
    ) throws Exception {
        Path source = tempDirectory.resolve("source" + extension);
        Files.writeString(source, "office");
        AtomicReference<List<String>> executedCommand = new AtomicReference<>();
        OfficePreviewConverter converter = new OfficePreviewConverter(
                tempDirectory,
                "soffice",
                Duration.ofSeconds(5),
                (command, workingDirectory, timeout) -> {
                    executedCommand.set(List.copyOf(command));
                    Path output = workingDirectory.resolve("output").resolve("document.pdf");
                    Files.write(output, "%PDF-1.7\npreview".getBytes());
                    return true;
                }
        );

        Path converted = converter
                .convert(source, extension, "a".repeat(64))
                .orElseThrow();

        assertThat(Files.readString(converted)).startsWith("%PDF-");
        assertThat(executedCommand.get()).contains("pdf:" + expectedFilter);
    }

    @Test
    void cachesConvertedPdfByAssetSha256AndExtension() throws Exception {
        Path source = tempDirectory.resolve("document.docx");
        Files.writeString(source, "document");
        AtomicInteger invocations = new AtomicInteger();
        OfficePreviewConverter converter = new OfficePreviewConverter(
                tempDirectory,
                "soffice",
                Duration.ofSeconds(5),
                (command, workingDirectory, timeout) -> {
                    invocations.incrementAndGet();
                    Path output = workingDirectory.resolve("output").resolve("document.pdf");
                    Files.write(output, "%PDF-1.7\npreview".getBytes());
                    return true;
                }
        );
        String sha256 = "c".repeat(64);

        Path first = converter.convert(source, ".docx", sha256).orElseThrow();
        Path second = converter.convert(source, ".docx", sha256).orElseThrow();

        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(second)).startsWith("%PDF-");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void sharesAnInProgressConversionForTheSameAssetFormatAndSha256() throws Exception {
        Path source = tempDirectory.resolve("concurrent.xlsx");
        Files.writeString(source, "workbook");
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch conversionStarted = new CountDownLatch(1);
        CountDownLatch releaseConversion = new CountDownLatch(1);
        OfficePreviewConverter converter = new OfficePreviewConverter(
                tempDirectory,
                "soffice",
                Duration.ofSeconds(5),
                (command, workingDirectory, timeout) -> {
                    invocations.incrementAndGet();
                    conversionStarted.countDown();
                    assertThat(releaseConversion.await(5, TimeUnit.SECONDS)).isTrue();
                    Path output = workingDirectory.resolve("output").resolve("document.pdf");
                    Files.write(output, "%PDF-1.7\npreview".getBytes());
                    return true;
                }
        );
        String sha256 = "d".repeat(64);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> converter.convert(source, ".xlsx", sha256));
            assertThat(conversionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> converter.convert(source, ".xlsx", sha256));
            releaseConversion.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(second.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            executor.shutdownNow();
        }
        assertThat(invocations).hasValue(1);
    }

    @Test
    void rejectsUnsupportedFormatsAndInvalidHashesWithoutStartingLibreOffice() throws Exception {
        Path source = tempDirectory.resolve("notes.txt");
        Files.writeString(source, "notes");
        AtomicInteger invocations = new AtomicInteger();
        OfficePreviewConverter converter = new OfficePreviewConverter(
                tempDirectory,
                "soffice",
                Duration.ofSeconds(5),
                (command, workingDirectory, timeout) -> {
                    invocations.incrementAndGet();
                    return true;
                }
        );

        assertThat(converter.convert(source, ".txt", "e".repeat(64))).isEmpty();
        assertThat(converter.convert(source, ".docx", "not-a-sha")).isEmpty();
        assertThat(invocations).hasValue(0);
    }

    @Test
    void returnsEmptyWhenLibreOfficeRunnerFailsUnexpectedly() throws Exception {
        Path source = tempDirectory.resolve("failure.pptx");
        Files.writeString(source, "presentation");
        OfficePreviewConverter converter = new OfficePreviewConverter(
                tempDirectory,
                "soffice",
                Duration.ofSeconds(5),
                (command, workingDirectory, timeout) -> {
                    throw new IllegalStateException("conversion failed");
                }
        );

        assertThat(converter.convert(source, ".pptx", "f".repeat(64))).isEmpty();
    }

    @Test
    void installedLibreOfficeRendersWordAndExcelTablesAndImagesToPdf() throws Exception {
        Path executable = installedLibreOffice();
        assumeTrue(executable != null, "LibreOffice is not installed");
        byte[] image = previewImage();
        Path word = createWordFixture(image);
        Path excel = createExcelFixture(image);
        OfficePreviewConverter converter = new OfficePreviewConverter(
                tempDirectory.resolve("real-conversion").toString(),
                executable.toString(),
                Duration.ofSeconds(30).toMillis()
        );

        Path wordPdf = converter
                .convert(word, ".docx", sha256(word))
                .orElseThrow();
        Path excelPdf = converter
                .convert(excel, ".xlsx", sha256(excel))
                .orElseThrow();

        assertRenderedPdf(wordPdf);
        assertRenderedPdf(excelPdf);
    }

    private Path createWordFixture(byte[] image) throws Exception {
        Path path = tempDirectory.resolve("table-and-image.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Product");
            table.getRow(0).getCell(1).setText("Amount");
            table.getRow(1).getCell(0).setText("Preview");
            table.getRow(1).getCell(1).setText("42");
            document.createParagraph()
                    .createRun()
                    .addPicture(
                            new ByteArrayInputStream(image),
                            Workbook.PICTURE_TYPE_PNG,
                            "preview.png",
                            Units.toEMU(120),
                            Units.toEMU(60)
                    );
            try (var output = Files.newOutputStream(path)) {
                document.write(output);
            }
        }
        return path;
    }

    private Path createExcelFixture(byte[] image) throws Exception {
        Path path = tempDirectory.resolve("table-and-image.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Preview");
            var heading = sheet.createRow(0);
            heading.createCell(0).setCellValue("Product");
            heading.createCell(1).setCellValue("Amount");
            var value = sheet.createRow(1);
            value.createCell(0).setCellValue("Preview");
            value.createCell(1).setCellValue(42);
            int pictureIndex = workbook.addPicture(image, Workbook.PICTURE_TYPE_PNG);
            var drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(0);
            anchor.setRow1(3);
            anchor.setCol2(3);
            anchor.setRow2(10);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
            drawing.createPicture(anchor, pictureIndex);
            try (var output = Files.newOutputStream(path)) {
                workbook.write(output);
            }
        }
        return path;
    }

    private static byte[] previewImage() throws Exception {
        BufferedImage image = new BufferedImage(240, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(15, 138, 112));
            graphics.fillRect(16, 16, 208, 88);
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private static void assertRenderedPdf(Path path) throws Exception {
        try (var document = Loader.loadPDF(path.toFile())) {
            assertThat(document.getNumberOfPages()).isPositive();
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Product", "Amount", "Preview", "42");
            int nonWhitePixels = 0;
            int accentPixels = 0;
            PDFRenderer renderer = new PDFRenderer(document);
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                BufferedImage page = renderer.renderImageWithDPI(pageIndex, 72);
                for (int y = 0; y < page.getHeight(); y += 2) {
                    for (int x = 0; x < page.getWidth(); x += 2) {
                        int rgb = page.getRGB(x, y);
                        if ((rgb & 0x00FFFFFF) != 0x00FFFFFF) {
                            nonWhitePixels++;
                        }
                        Color color = new Color(rgb);
                        if (color.getGreen() > color.getRed() + 40
                                && color.getGreen() > color.getBlue() + 10) {
                            accentPixels++;
                        }
                    }
                }
            }
            assertThat(nonWhitePixels).isGreaterThan(100);
            assertThat(accentPixels).isGreaterThan(100);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path installedLibreOffice() {
        for (String candidate : List.of(
                "C:/Program Files/LibreOffice/program/soffice.exe",
                "C:/Program Files (x86)/LibreOffice/program/soffice.exe",
                "/usr/bin/soffice",
                "/usr/local/bin/soffice"
        )) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }
}
