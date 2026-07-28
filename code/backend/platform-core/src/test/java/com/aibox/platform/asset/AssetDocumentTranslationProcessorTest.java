package com.aibox.platform.asset;

import com.aibox.feature.spi.DocumentTranslationPlan;
import com.aibox.feature.spi.DocumentTranslationUnit;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.TranslatedDocumentOutput;
import com.aibox.feature.spi.VisualPageTranslation;
import com.aibox.feature.spi.VisualTranslationBlock;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetDocumentTranslationProcessorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void translatesDocxBodyTableHeaderFooterAndHyperlink() throws IOException {
        Path path = temporaryDirectory.resolve("source.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(path)) {
            document.createParagraph().createRun().setText("Body text");
            XWPFTable table = document.createTable(1, 1);
            table.getRow(0).getCell(0).setText("Table text");
            XWPFHeader header = document.createHeader(HeaderFooterType.DEFAULT);
            header.createParagraph().createRun().setText("Header text");
            document.createFooter(HeaderFooterType.DEFAULT)
                    .createParagraph()
                    .createRun()
                    .setText("Footer text");
            document.createParagraph()
                    .createRun()
                    .setText("Use @CsvSource for negative inputs.");
            XWPFParagraph linkParagraph = document.createParagraph();
            linkParagraph.createHyperlinkRun("https://example.com").setText("Example link");
            document.write(output);
        }
        UUID assetId = UUID.randomUUID();
        AssetDocumentTranslationProcessor processor = processor(
                assetId,
                path,
                "source.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );

        DocumentTranslationPlan plan = processor.prepare(assetId, 30_000, 20);
        Map<String, String> translations = translationsBySource(plan, Map.of(
                "Body text", "Corps",
                "Table text", "Tableau",
                "Header text", "En-tete",
                "Footer text", "Pied",
                "Use @CsvSource for negative inputs.",
                "Utiliser @CsvSource pour les entrees negatives.",
                "Example link", "Lien"
        ));
        TranslatedDocumentOutput result = processor.render(
                assetId,
                plan,
                translations,
                List.of()
        );

        try (XWPFDocument translated = new XWPFDocument(
                new ByteArrayInputStream(result.content())
        )) {
            assertThat(translated.getParagraphs().get(0).getText()).isEqualTo("Corps");
            assertThat(translated.getTables().get(0).getText()).contains("Tableau");
            assertThat(translated.getHeaderList().get(0).getText()).contains("En-tete");
            assertThat(translated.getFooterList().get(0).getText()).contains("Pied");
            assertThat(translated.getParagraphs().get(1).getText())
                    .isEqualTo("Utiliser @CsvSource pour les entrees negatives.");
            XWPFHyperlinkRun link = (XWPFHyperlinkRun) translated.getParagraphs()
                    .get(2)
                    .getRuns()
                    .get(0);
            assertThat(link.text()).isEqualTo("Lien");
            assertThat(link.getHyperlink(translated).getURL()).isEqualTo("https://example.com");
        }
    }

    @Test
    void translatesDocxTableOfContentsContentControl() throws IOException {
        Path path = temporaryDirectory.resolve("toc.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(path)) {
            CTSdtBlock toc = document.getDocument().getBody().addNewSdt();
            var content = toc.addNewSdtContent();
            XWPFParagraph title = new XWPFParagraph(content.addNewP(), document);
            title.createRun().setText("目");
            title.createRun().setText("  ");
            title.createRun().setText("录");
            XWPFParagraph entry = new XWPFParagraph(content.addNewP(), document);
            entry.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
            entry.createRun().getCTR().addNewInstrText().setStringValue(
                    " HYPERLINK \\l \"_Toc1\" "
            );
            entry.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
            entry.createRun().setText("1.");
            entry.createRun().setText("实验目的及要求");
            entry.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
            entry.createRun().getCTR().addNewInstrText().setStringValue(
                    " PAGEREF _MissingBookmark \\h "
            );
            entry.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
            entry.createRun().setText("3");
            entry.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
            entry.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
            document.write(output);
        }
        UUID assetId = UUID.randomUUID();
        AssetDocumentTranslationProcessor processor = processor(
                assetId,
                path,
                "toc.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );
        DocumentTranslationPlan plan = processor.prepare(assetId, 30_000, 20);
        Map<String, String> translations = translationsBySource(plan, Map.of(
                "目  录", "目次",
                "实验目的及要求", "実験の目的および要件"
        ));

        TranslatedDocumentOutput result = processor.render(
                assetId,
                plan,
                translations,
                List.of()
        );

        try (XWPFDocument translated = new XWPFDocument(
                new ByteArrayInputStream(result.content())
        )) {
            String tocXml = translated.getDocument()
                    .getBody()
                    .getSdtArray(0)
                    .xmlText();
            assertThat(tocXml).contains("目次", "実験の目的および要件");
            assertThat(tocXml).doesNotContain("目录", "实验目的及要求");
            assertThat(tocXml).contains("HYPERLINK", "_Toc1", ">3<");
            assertThat(tocXml).doesNotContain("PAGEREF", "_MissingBookmark");
        }
    }

    @Test
    void translatesTextPdfAndProducesReopenablePdfWithSamePageCount() throws IOException {
        Path path = temporaryDirectory.resolve("text.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("This document contains enough visible text for translation.");
                content.endText();
            }
            document.save(path.toFile());
        }
        UUID assetId = UUID.randomUUID();
        AssetDocumentTranslationProcessor processor = processor(
                assetId,
                path,
                "text.pdf",
                "application/pdf"
        );

        DocumentTranslationPlan plan = processor.prepare(assetId, 30_000, 20);
        assertThat(plan.visualPages()).isEmpty();
        assertThat(plan.textUnits()).isNotEmpty();
        Map<String, String> translations = plan.textUnits().stream().collect(Collectors.toMap(
                DocumentTranslationUnit::id,
                ignored -> "Translated",
                (left, right) -> left,
                LinkedHashMap::new
        ));

        TranslatedDocumentOutput result = processor.render(
                assetId,
                plan,
                translations,
                List.of()
        );

        assertThat(result.mediaType()).isEqualTo("application/pdf");
        try (PDDocument translated = Loader.loadPDF(result.content())) {
            assertThat(translated.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void doesNotCoverImagesBetweenDistantSameLineTextColumns() throws IOException {
        Path path = temporaryDirectory.resolve("columns.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.BLUE);
                content.addRect(280, 620, 100, 80);
                content.fill();
                content.setNonStrokingColor(Color.BLACK);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(72, 660);
                content.showText("A sufficiently long identity description on the left.");
                content.endText();
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(450, 660);
                content.showText("CODE123");
                content.endText();
            }
            document.save(path.toFile());
        }
        UUID assetId = UUID.randomUUID();
        AssetDocumentTranslationProcessor processor = processor(
                assetId,
                path,
                "columns.pdf",
                "application/pdf"
        );
        DocumentTranslationPlan plan = processor.prepare(assetId, 30_000, 20);
        assertThat(plan.textUnits()).hasSizeGreaterThanOrEqualTo(2);
        Map<String, String> translations = plan.textUnits().stream().collect(Collectors.toMap(
                DocumentTranslationUnit::id,
                ignored -> "Translated",
                (left, right) -> left,
                LinkedHashMap::new
        ));

        TranslatedDocumentOutput result = processor.render(
                assetId,
                plan,
                translations,
                List.of()
        );

        try (PDDocument translated = Loader.loadPDF(result.content())) {
            BufferedImage page = new PDFRenderer(translated).renderImageWithDPI(
                    0,
                    72,
                    ImageType.RGB
            );
            try {
                Color protectedImage = new Color(page.getRGB(330, 132));
                assertThat(protectedImage.getBlue()).isGreaterThan(150);
                assertThat(protectedImage.getRed()).isLessThan(100);
            } finally {
                page.flush();
            }
        }
    }

    @Test
    void preservesPhotoInsideACombinedTextBlockEnvelope() throws IOException {
        Path path = temporaryDirectory.resolve("identity-with-photo.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.BLUE);
                content.addRect(420, 590, 90, 100);
                content.fill();
                content.setNonStrokingColor(Color.BLACK);
                writePdfLine(
                        content,
                        72,
                        700,
                        "Identity information heading across the printable page "
                                + "with enough source text to span the photo column."
                );
                writePdfLine(content, 72, 680, "Admission number: 430132261103004");
                writePdfLine(content, 72, 664, "Name: Example Candidate");
                writePdfLine(content, 72, 648, "Gender: Male");
                writePdfLine(content, 72, 632, "School: Example University");
                writePdfLine(content, 72, 616, "Department: Computer Science");
            }
            document.save(path.toFile());
        }
        UUID assetId = UUID.randomUUID();
        AssetDocumentTranslationProcessor processor = processor(
                assetId,
                path,
                "identity-with-photo.pdf",
                "application/pdf"
        );
        DocumentTranslationPlan plan = processor.prepare(assetId, 30_000, 20);
        Map<String, String> translations = plan.textUnits().stream().collect(Collectors.toMap(
                DocumentTranslationUnit::id,
                ignored -> "Translated identity information.",
                (left, right) -> left,
                LinkedHashMap::new
        ));

        TranslatedDocumentOutput result = processor.render(
                assetId,
                plan,
                translations,
                List.of()
        );

        try (PDDocument translated = Loader.loadPDF(result.content())) {
            assertThat(translated.getNumberOfPages()).isEqualTo(1);
            BufferedImage firstPage = new PDFRenderer(translated).renderImageWithDPI(
                    0,
                    72,
                    ImageType.RGB
            );
            try {
                Color protectedPhoto = new Color(firstPage.getRGB(465, 152));
                assertThat(protectedPhoto.getBlue()).isGreaterThan(150);
                assertThat(protectedPhoto.getRed()).isLessThan(100);
            } finally {
                firstPage.flush();
            }
        }
    }

    @Test
    void keepsOriginalPageCountWhenTranslationRequiresInlineCompression()
            throws IOException {
        Path path = temporaryDirectory.resolve("overflow.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.BLUE);
                content.addRect(400, 600, 100, 100);
                content.fill();
                content.setNonStrokingColor(Color.BLACK);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(72, 700);
                content.showText("A compact source line with enough visible characters.");
                content.endText();
            }
            PDPage secondPage = new PDPage();
            document.addPage(secondPage);
            try (PDPageContentStream content = new PDPageContentStream(document, secondPage)) {
                content.setNonStrokingColor(Color.GREEN);
                content.addRect(0, 0, secondPage.getMediaBox().getWidth(),
                        secondPage.getMediaBox().getHeight());
                content.fill();
                content.setNonStrokingColor(Color.BLACK);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("The second original page must remain before supplemental pages.");
                content.endText();
            }
            document.save(path.toFile());
        }
        UUID assetId = UUID.randomUUID();
        AssetDocumentTranslationProcessor processor = processor(
                assetId,
                path,
                "overflow.pdf",
                "application/pdf"
        );
        DocumentTranslationPlan plan = processor.prepare(assetId, 30_000, 20);
        String longTranslation = "This translated paragraph must remain complete "
                .repeat(10);
        Map<String, String> translations = plan.textUnits().stream().collect(Collectors.toMap(
                DocumentTranslationUnit::id,
                unit -> unit.id().contains("p0001")
                        ? longTranslation
                        : "The second page remains in its original position.",
                (left, right) -> left,
                LinkedHashMap::new
        ));

        TranslatedDocumentOutput result = processor.render(
                assetId,
                plan,
                translations,
                List.of()
        );

        try (PDDocument translated = Loader.loadPDF(result.content())) {
            assertThat(translated.getNumberOfPages()).isEqualTo(2);
            BufferedImage firstPage = new PDFRenderer(translated).renderImageWithDPI(
                    0,
                    72,
                    ImageType.RGB
            );
            try {
                Color protectedArea = new Color(firstPage.getRGB(450, 142));
                assertThat(protectedArea.getBlue()).isGreaterThan(150);
                assertThat(protectedArea.getRed()).isLessThan(100);
            } finally {
                firstPage.flush();
            }
            BufferedImage secondOutputPage = new PDFRenderer(translated).renderImageWithDPI(
                    1,
                    72,
                    ImageType.RGB
            );
            try {
                Color originalSecondPage = new Color(secondOutputPage.getRGB(300, 400));
                assertThat(originalSecondPage.getGreen()).isGreaterThan(100);
                assertThat(originalSecondPage.getRed()).isLessThan(100);
            } finally {
                secondOutputPage.flush();
            }
        }
    }

    @Test
    void selectsAnInstalledFontForMixedSupportedLanguageGlyphs() {
        boolean hasBroadCjkFont = List.of(
                        GraphicsEnvironment.getLocalGraphicsEnvironment()
                                .getAvailableFontFamilyNames()
                )
                .stream()
                .anyMatch(name -> name.equals("Malgun Gothic"));
        assumeTrue(hasBroadCjkFont);
        String sample = "\u4e2d\u6587 English \u65e5\u672c\u8a9e "
                + "\ud55c\uad6d\uc5b4 Fran\u00e7ais Deutsch Espa\u00f1ol "
                + "\u0420\u0443\u0441\u0441\u043a\u0438\u0439";

        Font font = AssetDocumentTranslationProcessor.pdfFontForText(10, sample);

        assertThat(font.canDisplayUpTo(sample)).isEqualTo(-1);
    }

    @Test
    void rendersOptionalActualPdfForVisualInspection() throws IOException {
        String inputValue = System.getProperty("document.translation.visualInput");
        String outputValue = System.getProperty("document.translation.visualOutput");
        assumeTrue(inputValue != null && !inputValue.isBlank());
        assumeTrue(outputValue != null && !outputValue.isBlank());
        Path input = Path.of(inputValue);
        Path output = Path.of(outputValue);
        assumeTrue(Files.isRegularFile(input));
        UUID assetId = UUID.randomUUID();
        AssetDocumentTranslationProcessor processor = processor(
                assetId,
                input,
                "visual-source.pdf",
                "application/pdf"
        );
        DocumentTranslationPlan plan = processor.prepare(assetId, 30_000, 20);
        Map<String, String> translations = plan.textUnits().stream().collect(Collectors.toMap(
                DocumentTranslationUnit::id,
                unit -> translatedFixture(unit.text()),
                (left, right) -> left,
                LinkedHashMap::new
        ));
        List<VisualPageTranslation> visualTranslations = plan.visualPages().stream()
                .map(page -> new VisualPageTranslation(page.pageNumber(), List.of()))
                .toList();

        TranslatedDocumentOutput result = processor.render(
                assetId,
                plan,
                translations,
                visualTranslations
        );

        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.write(output, result.content());
        try (PDDocument translated = Loader.loadPDF(result.content())) {
            assertThat(translated.getNumberOfPages()).isEqualTo(plan.pageCount());
        }
    }

    @Test
    void rendersOptionalActualDocxCoverageFixForVisualInspection() throws IOException {
        String inputValue = System.getProperty("document.translation.docxVisualInput");
        String outputValue = System.getProperty("document.translation.docxVisualOutput");
        assumeTrue(inputValue != null && !inputValue.isBlank());
        assumeTrue(outputValue != null && !outputValue.isBlank());
        Path input = Path.of(inputValue);
        Path output = Path.of(outputValue);
        assumeTrue(Files.isRegularFile(input));
        UUID assetId = UUID.randomUUID();
        AssetDocumentTranslationProcessor processor = processor(
                assetId,
                input,
                "visual-source.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );
        DocumentTranslationPlan plan = processor.prepare(assetId, 30_000, 20);
        Map<String, String> replacements = Map.ofEntries(
                Map.entry("目  录", "目次"),
                Map.entry("实验目的及要求", "実験の目的および要件"),
                Map.entry("实验软硬件环境", "実験のソフトウェア・ハードウェア環境"),
                Map.entry("实验原理", "実験原理"),
                Map.entry("实验过程", "実験プロセス"),
                Map.entry("方法", "方法"),
                Map.entry("步骤", "手順"),
                Map.entry("实验结果", "実験結果"),
                Map.entry("测试结果", "テスト結果"),
                Map.entry("总结", "まとめ"),
                Map.entry(
                        "对于输入变量为负数的情况，处理方法为：在测试中使用@CsvSource提供负数输入，"
                                + "预期抛出ArithmeticException异常；",
                        "入力変数が負数の場合の処理方法は、テストで@CsvSourceを使用して負数入力を"
                                + "提供し、ArithmeticException例外がスローされることを期待する；"
                )
        );
        assertThat(plan.textUnits().stream().map(DocumentTranslationUnit::text))
                .contains("目  录")
                .anyMatch(value -> value.contains("@CsvSource提供负数输入"));
        Map<String, String> translations = plan.textUnits().stream().collect(Collectors.toMap(
                DocumentTranslationUnit::id,
                unit -> replacements.getOrDefault(unit.text(), unit.text()),
                (left, right) -> left,
                LinkedHashMap::new
        ));

        TranslatedDocumentOutput result = processor.render(
                assetId,
                plan,
                translations,
                List.of()
        );

        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.write(output, result.content());
        try (XWPFDocument translated = new XWPFDocument(
                new ByteArrayInputStream(result.content())
        )) {
            String documentXml = translated.getDocument().xmlText();
            assertThat(documentXml)
                    .contains("目次", "実験の目的および要件", "@CsvSource")
                    .doesNotContain("实验目的及要求", "对于输入变量为负数的情况");
        }
    }

    @Test
    void rendersScannedPdfPageWithVisualTranslationBlocks() throws IOException {
        Path path = temporaryDirectory.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(path.toFile());
        }
        UUID assetId = UUID.randomUUID();
        AssetDocumentTranslationProcessor processor = processor(
                assetId,
                path,
                "scan.pdf",
                "application/pdf"
        );

        DocumentTranslationPlan plan = processor.prepare(assetId, 30_000, 20);
        assertThat(plan.textUnits()).isEmpty();
        assertThat(plan.visualPages()).singleElement().satisfies(page -> {
            assertThat(page.pageNumber()).isEqualTo(1);
            assertThat(page.image().mediaType()).isEqualTo("image/jpeg");
            assertThat(page.image().content()).isNotEmpty();
        });

        TranslatedDocumentOutput result = processor.render(
                assetId,
                plan,
                Map.of(),
                List.of(new VisualPageTranslation(
                        1,
                        List.of(new VisualTranslationBlock(
                                0.1,
                                0.1,
                                0.6,
                                0.2,
                                "Translated scan"
                        ))
                ))
        );

        try (PDDocument translated = Loader.loadPDF(result.content())) {
            assertThat(translated.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void enforcesCharacterAndScannedPageLimits() throws IOException {
        Path docx = temporaryDirectory.resolve("large.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(docx)) {
            document.createParagraph().createRun().setText("abcdef");
            document.write(output);
        }
        UUID docxId = UUID.randomUUID();
        AssetDocumentTranslationProcessor docxProcessor = processor(
                docxId,
                docx,
                "large.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );

        assertThatThrownBy(() -> docxProcessor.prepare(docxId, 5, 20))
                .isInstanceOf(FeatureValidationException.class)
                .hasMessageContaining("5");

        Path pdf = temporaryDirectory.resolve("two-pages.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        UUID pdfId = UUID.randomUUID();
        AssetDocumentTranslationProcessor pdfProcessor = processor(
                pdfId,
                pdf,
                "two-pages.pdf",
                "application/pdf"
        );

        assertThatThrownBy(() -> pdfProcessor.prepare(pdfId, 30_000, 1))
                .isInstanceOf(FeatureValidationException.class)
                .hasMessageContaining("1");
    }

    @Test
    void rejectsMissingTranslationsAndInvalidDocFiles() throws IOException {
        Path docx = temporaryDirectory.resolve("source.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(docx)) {
            document.createParagraph().createRun().setText("Body text");
            document.write(output);
        }
        UUID docxId = UUID.randomUUID();
        AssetDocumentTranslationProcessor docxProcessor = processor(
                docxId,
                docx,
                "source.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );
        DocumentTranslationPlan plan = docxProcessor.prepare(docxId, 30_000, 20);

        assertThatThrownBy(() -> docxProcessor.render(docxId, plan, Map.of(), List.of()))
                .isInstanceOf(FeatureValidationException.class)
                .hasMessageContaining("完整译文");

        Path invalidDoc = temporaryDirectory.resolve("invalid.doc");
        Files.writeString(invalidDoc, "not a word document", StandardCharsets.UTF_8);
        UUID docId = UUID.randomUUID();
        AssetDocumentTranslationProcessor docProcessor = processor(
                docId,
                invalidDoc,
                "invalid.doc",
                "application/msword"
        );

        assertThatThrownBy(() -> docProcessor.prepare(docId, 30_000, 20))
                .isInstanceOf(FeatureValidationException.class)
                .hasMessageContaining("转换为 DOCX");
    }

    private static Map<String, String> translationsBySource(
            DocumentTranslationPlan plan,
            Map<String, String> bySource
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        for (DocumentTranslationUnit unit : plan.textUnits()) {
            String translated = bySource.get(unit.text());
            assertThat(translated)
                    .as("translation for source unit %s", unit.text())
                    .isNotNull();
            result.put(unit.id(), translated);
        }
        return result;
    }

    private static AssetDocumentTranslationProcessor processor(
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
                Instant.parse("2026-07-27T00:00:00Z")
        );
        when(assetService.openForPreview(assetId)).thenReturn(
                new AssetService.AssetStoredFile(asset, path)
        );
        return new AssetDocumentTranslationProcessor(assetService);
    }

    private static void writePdfLine(
            PDPageContentStream content,
            float x,
            float y,
            String text
    ) throws IOException {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    private static String translatedFixture(String source) {
        String syllables = "\ubc88\uc5ed\ubb38";
        StringBuilder result = new StringBuilder(source.length());
        int letterIndex = 0;
        for (int offset = 0; offset < source.length(); ) {
            int codePoint = source.codePointAt(offset);
            if (Character.isLetter(codePoint)) {
                result.append(syllables.charAt(letterIndex % syllables.length()));
                letterIndex++;
            } else {
                result.appendCodePoint(codePoint);
            }
            offset = source.offsetByCodePoints(offset, 1);
        }
        return result.toString();
    }
}
