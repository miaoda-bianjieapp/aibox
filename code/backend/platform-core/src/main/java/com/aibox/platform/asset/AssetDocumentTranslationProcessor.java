package com.aibox.platform.asset;

import com.aibox.feature.spi.DocumentTranslationPlan;
import com.aibox.feature.spi.DocumentTranslationProcessor;
import com.aibox.feature.spi.DocumentTranslationUnit;
import com.aibox.feature.spi.DocumentVisualPage;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.TranslatedDocumentOutput;
import com.aibox.feature.spi.VisualPageTranslation;
import com.aibox.feature.spi.VisualTranslationBlock;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFootnote;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentBlock;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class AssetDocumentTranslationProcessor implements DocumentTranslationProcessor {

    private static final int PDF_SCAN_TEXT_THRESHOLD = 24;
    private static final float PDF_RENDER_DPI = 144f;
    private static final float MODEL_RENDER_DPI = 120f;
    private static final float MODEL_JPEG_QUALITY = 0.84f;
    private static final float OUTPUT_JPEG_QUALITY = 0.92f;
    private static final float MIN_PDF_FONT_POINTS = 4f;
    private static final int TEXT_BOX_PADDING_PIXELS = 3;
    private static final int VISUAL_BOX_MARGIN_PIXELS = 6;
    private static final int MIN_TEXT_BOX_PIXELS = 12;
    private static final int EXPANDED_TEXT_BOX_MARGIN_PIXELS = 18;
    private static final int PROTECTED_COLOR_CHANNEL_SPREAD = 36;
    private static final double PROTECTED_COLOR_PIXEL_RATIO = 0.03;
    private static final double BACKGROUND_COLOR_PIXEL_RATIO = 0.85;
    private static final long MAX_RENDERED_MODEL_BYTES = 100L * 1024 * 1024;
    private static final List<String> PREFERRED_PDF_FONT_FAMILIES = List.of(
            "Noto Sans SC",
            "Noto Sans CJK SC",
            "Noto Sans CJK JP",
            "Noto Sans CJK KR",
            "Microsoft YaHei UI",
            "Microsoft YaHei",
            "Yu Gothic UI",
            "Yu Gothic",
            "Meiryo UI",
            "Meiryo",
            "Malgun Gothic",
            "Arial Unicode MS",
            "Segoe UI",
            "DejaVu Sans",
            Font.SANS_SERIF
    );
    private static final Set<String> AVAILABLE_FONT_FAMILIES = Set.of(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()
    );
    private static final Map<Integer, List<Font>> PDF_FONTS_BY_PIXEL_SIZE =
            new ConcurrentHashMap<>();
    private static final Set<String> URL_PREFIXES = Set.of(
            "http://", "https://", "mailto:", "ftp://"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"
    );
    private static final Pattern PAGEREF_PATTERN = Pattern.compile(
            "\\bPAGEREF\\s+([^\\s]+)",
            Pattern.CASE_INSENSITIVE
    );

    private final AssetService assetService;

    public AssetDocumentTranslationProcessor(AssetService assetService) {
        this.assetService = assetService;
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentTranslationPlan prepare(
            UUID assetId,
            int maxCharacters,
            int maxScannedPdfPages
    ) {
        if (maxCharacters <= 0 || maxScannedPdfPages <= 0) {
            throw new IllegalArgumentException("Document translation limits must be positive");
        }
        AssetService.AssetStoredFile stored = assetService.openForPreview(assetId);
        String extension = extension(stored.asset().name());
        return switch (extension) {
            case ".docx" -> prepareDocx(stored.path(), maxCharacters);
            case ".doc" -> prepareDoc(stored.path(), maxCharacters);
            case ".pdf" -> preparePdf(assetId, stored.path(), maxCharacters, maxScannedPdfPages);
            default -> throw invalidDocument("仅支持 DOCX、DOC 和 PDF 文档");
        };
    }

    @Override
    @Transactional(readOnly = true)
    public TranslatedDocumentOutput render(
            UUID assetId,
            DocumentTranslationPlan plan,
            Map<String, String> textTranslations,
            List<VisualPageTranslation> visualTranslations
    ) {
        AssetService.AssetStoredFile stored = assetService.openForPreview(assetId);
        Map<String, String> translations = normalizeTranslations(plan, textTranslations);
        List<VisualPageTranslation> pages = visualTranslations == null
                ? List.of()
                : List.copyOf(visualTranslations);
        return switch (plan.format()) {
            case "docx" -> renderDocx(stored.path(), plan, translations, pages);
            case "doc" -> renderDoc(stored.path(), plan, translations, pages);
            case "pdf" -> renderPdf(stored.path(), plan, translations, pages);
            default -> throw invalidDocument("文档翻译格式无效");
        };
    }

    private DocumentTranslationPlan prepareDocx(Path path, int maxCharacters) {
        try (InputStream input = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(input)) {
            List<DocxTextSlot> slots = collectDocxSlots(document);
            return textPlan("docx", 0, slots.stream().map(DocxTextSlot::unit).toList(), maxCharacters);
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidDocument("DOCX 已损坏、受密码保护或无法读取");
        }
    }

    private DocumentTranslationPlan prepareDoc(Path path, int maxCharacters) {
        try (InputStream input = Files.newInputStream(path);
             HWPFDocument document = new HWPFDocument(input)) {
            List<DocTextSlot> slots = collectDocSlots(document);
            return textPlan("doc", 0, slots.stream().map(DocTextSlot::unit).toList(), maxCharacters);
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidDocument("DOC 结构不受支持，请转换为 DOCX 后重试");
        }
    }

    private DocumentTranslationPlan preparePdf(
            UUID assetId,
            Path path,
            int maxCharacters,
            int maxScannedPdfPages
    ) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            validatePdf(document);
            PDFRenderer renderer = new PDFRenderer(document);
            List<DocumentTranslationUnit> units = new ArrayList<>();
            List<DocumentVisualPage> visualPages = new ArrayList<>();
            int characters = 0;
            long renderedBytes = 0;
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                int pageNumber = pageIndex + 1;
                List<PdfTextBlock> blocks = extractPdfBlocks(document, pageNumber);
                int pageCharacters = blocks.stream()
                        .mapToInt(block -> codePointCount(block.text()))
                        .sum();
                if (pageCharacters < PDF_SCAN_TEXT_THRESHOLD) {
                    if (visualPages.size() >= maxScannedPdfPages) {
                        throw invalidDocument(
                                "扫描 PDF 最多支持 " + maxScannedPdfPages + " 页，请拆分后重试"
                        );
                    }
                    BufferedImage image = renderer.renderImageWithDPI(
                            pageIndex,
                            MODEL_RENDER_DPI,
                            ImageType.RGB
                    );
                    byte[] content;
                    try {
                        content = encodeJpeg(image, MODEL_JPEG_QUALITY);
                    } finally {
                        image.flush();
                    }
                    renderedBytes += content.length;
                    if (renderedBytes > MAX_RENDERED_MODEL_BYTES) {
                        throw invalidDocument("扫描 PDF 页面图像过大，请拆分后重试");
                    }
                    visualPages.add(new DocumentVisualPage(
                            pageNumber,
                            new ModelAsset(
                                    UUID.nameUUIDFromBytes(
                                            (assetId + ":translate-pdf-page:" + pageNumber)
                                                    .getBytes(StandardCharsets.UTF_8)
                                    ),
                                    "page-" + String.format(Locale.ROOT, "%04d", pageNumber) + ".jpg",
                                    "image/jpeg",
                                    content
                            )
                    ));
                    continue;
                }
                for (PdfTextBlock block : blocks) {
                    DocumentTranslationUnit unit = new DocumentTranslationUnit(
                            block.id(),
                            block.text(),
                            "PDF 第 " + pageNumber + " 页"
                    );
                    units.add(unit);
                    characters += codePointCount(unit.text());
                    ensureCharacterLimit(characters, maxCharacters);
                }
            }
            return new DocumentTranslationPlan(
                    "pdf",
                    document.getNumberOfPages(),
                    characters,
                    units,
                    visualPages
            );
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidDocument("PDF 已损坏、受密码保护或无法读取");
        }
    }

    private static DocumentTranslationPlan textPlan(
            String format,
            int pageCount,
            List<DocumentTranslationUnit> units,
            int maxCharacters
    ) {
        if (units.isEmpty()) {
            throw invalidDocument("文档没有可翻译的正文内容");
        }
        int characters = units.stream().mapToInt(unit -> codePointCount(unit.text())).sum();
        ensureCharacterLimit(characters, maxCharacters);
        return new DocumentTranslationPlan(
                format,
                pageCount,
                characters,
                units,
                List.of()
        );
    }

    private TranslatedDocumentOutput renderDocx(
            Path path,
            DocumentTranslationPlan plan,
            Map<String, String> translations,
            List<VisualPageTranslation> visualTranslations
    ) {
        if (!visualTranslations.isEmpty()) {
            throw invalidDocument("Word 文档不能包含扫描页翻译结果");
        }
        try (InputStream input = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            List<DocxTextSlot> slots = collectDocxSlots(document);
            verifyUnits(plan.textUnits(), slots.stream().map(DocxTextSlot::unit).toList());
            for (DocxTextSlot slot : slots) {
                slot.writeTranslation(translations.get(slot.unit().id()));
            }
            flattenBrokenTocPageReferences(document);
            document.write(output);
            byte[] bytes = output.toByteArray();
            try (XWPFDocument ignored = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                return new TranslatedDocumentOutput(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        bytes
                );
            }
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidDocument("DOCX 译文写回失败，未生成结果文件");
        }
    }

    private TranslatedDocumentOutput renderDoc(
            Path path,
            DocumentTranslationPlan plan,
            Map<String, String> translations,
            List<VisualPageTranslation> visualTranslations
    ) {
        if (!visualTranslations.isEmpty()) {
            throw invalidDocument("Word 文档不能包含扫描页翻译结果");
        }
        try (InputStream input = Files.newInputStream(path);
             HWPFDocument document = new HWPFDocument(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            List<DocTextSlot> slots = collectDocSlots(document);
            verifyUnits(plan.textUnits(), slots.stream().map(DocTextSlot::unit).toList());
            slots.stream()
                    .sorted(Comparator.comparingInt(DocTextSlot::startOffset).reversed())
                    .forEach(slot -> slot.run().replaceText(
                            slot.originalVisibleText(),
                            slot.prefix() + translations.get(slot.unit().id()) + slot.suffix(),
                            0
                    ));
            document.write(output);
            byte[] bytes = output.toByteArray();
            try (HWPFDocument ignored = new HWPFDocument(new ByteArrayInputStream(bytes))) {
                return new TranslatedDocumentOutput("application/msword", bytes);
            }
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidDocument("DOC 结构不受支持，请转换为 DOCX 后重试");
        }
    }

    private TranslatedDocumentOutput renderPdf(
            Path path,
            DocumentTranslationPlan plan,
            Map<String, String> translations,
            List<VisualPageTranslation> visualTranslations
    ) {
        try (PDDocument source = Loader.loadPDF(path.toFile());
             PDDocument output = new PDDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            validatePdf(source);
            if (source.getNumberOfPages() != plan.pageCount()) {
                throw invalidDocument("PDF 页面数量发生变化，请重新上传后重试");
            }
            Map<Integer, VisualPageTranslation> visualByPage = visualTranslationsByPage(
                    plan,
                    visualTranslations
            );
            Set<Integer> scannedPages = plan.visualPages().stream()
                    .map(DocumentVisualPage::pageNumber)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            PDFRenderer renderer = new PDFRenderer(source);
            for (int pageIndex = 0; pageIndex < source.getNumberOfPages(); pageIndex++) {
                int pageNumber = pageIndex + 1;
                BufferedImage image = renderer.renderImageWithDPI(
                        pageIndex,
                        PDF_RENDER_DPI,
                        ImageType.RGB
                );
                try {
                    Graphics2D graphics = image.createGraphics();
                    try {
                        configureGraphics(graphics);
                        if (scannedPages.contains(pageNumber)) {
                            VisualPageTranslation pageTranslation = visualByPage.get(pageNumber);
                            for (VisualTranslationBlock block : pageTranslation.blocks()) {
                                if (!drawTranslatedBlock(
                                        graphics,
                                        image,
                                        block.x() * image.getWidth(),
                                        block.y() * image.getHeight(),
                                        block.width() * image.getWidth(),
                                        block.height() * image.getHeight(),
                                        block.text(),
                                        14f,
                                        false
                                )) {
                                    throw invalidDocument(
                                            "PDF 第 " + pageNumber
                                                    + " 页译文无法在原页完整排版，请减少原文内容后重试"
                                    );
                                }
                            }
                        } else {
                            for (PdfTextBlock block : extractPdfBlocks(source, pageNumber)) {
                                String translated = translations.get(block.id());
                                if (translated == null) {
                                    throw invalidDocument("PDF 译文缺少原文区域：" + block.id());
                                }
                                if (!drawTranslatedBlock(
                                        graphics,
                                        image,
                                        block.x() / block.pageWidth() * image.getWidth(),
                                        block.y() / block.pageHeight() * image.getHeight(),
                                        block.width() / block.pageWidth() * image.getWidth(),
                                        block.height() / block.pageHeight() * image.getHeight(),
                                        translated,
                                        block.fontSize(),
                                        true
                                )) {
                                    throw invalidDocument(
                                            "PDF 第 " + pageNumber
                                                    + " 页译文无法在原页完整排版，请减少原文内容后重试"
                                    );
                                }
                            }
                        }
                    } finally {
                        graphics.dispose();
                    }
                    addRasterPage(output, image);
                } finally {
                    image.flush();
                }
            }
            output.save(bytes);
            byte[] content = bytes.toByteArray();
            try (PDDocument validation = Loader.loadPDF(content)) {
                if (validation.getNumberOfPages() != plan.pageCount()) {
                    throw invalidDocument("PDF 译文输出页面数量不正确");
                }
            }
            return new TranslatedDocumentOutput("application/pdf", content);
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidDocument("PDF 译文重建失败，未生成结果文件");
        }
    }

    private static List<DocxTextSlot> collectDocxSlots(XWPFDocument document) {
        List<DocxTextSlot> slots = new ArrayList<>();
        collectDocxBody(document, "body", slots);
        collectDocxSdtBlocks(
                document.getDocument().getBody().getSdtList(),
                document,
                "body:sdt",
                slots
        );
        for (int index = 0; index < document.getHeaderList().size(); index++) {
            XWPFHeader header = document.getHeaderList().get(index);
            String path = "header:" + index;
            collectDocxBody(header, path, slots);
            collectDocxSdtBlocks(
                    header._getHdrFtr().getSdtList(),
                    header,
                    path + ":sdt",
                    slots
            );
        }
        for (int index = 0; index < document.getFooterList().size(); index++) {
            XWPFFooter footer = document.getFooterList().get(index);
            String path = "footer:" + index;
            collectDocxBody(footer, path, slots);
            collectDocxSdtBlocks(
                    footer._getHdrFtr().getSdtList(),
                    footer,
                    path + ":sdt",
                    slots
            );
        }
        for (XWPFFootnote footnote : document.getFootnotes()) {
            collectDocxBody(footnote, "footnote:" + footnote.getId(), slots);
        }
        document.getEndnotes().forEach(endnote ->
                collectDocxBody(endnote, "endnote:" + endnote.getId(), slots)
        );
        return List.copyOf(slots);
    }

    private static void collectDocxSdtBlocks(
            List<CTSdtBlock> blocks,
            IBody body,
            String path,
            List<DocxTextSlot> slots
    ) {
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            CTSdtBlock block = blocks.get(blockIndex);
            if (!block.isSetSdtContent()) continue;
            collectDocxSdtContent(
                    block.getSdtContent(),
                    body,
                    path + blockIndex,
                    slots
            );
        }
    }

    private static void collectDocxSdtContent(
            CTSdtContentBlock content,
            IBody body,
            String path,
            List<DocxTextSlot> slots
    ) {
        for (int paragraphIndex = 0;
             paragraphIndex < content.getPList().size();
             paragraphIndex++) {
            collectDocxParagraph(
                    new XWPFParagraph(content.getPList().get(paragraphIndex), body),
                    path + ":p" + paragraphIndex,
                    slots,
                    true
            );
        }
        for (int tableIndex = 0;
             tableIndex < content.getTblList().size();
             tableIndex++) {
            collectDocxTable(
                    new XWPFTable(content.getTblList().get(tableIndex), body),
                    path + ":t" + tableIndex,
                    slots
            );
        }
        collectDocxSdtBlocks(content.getSdtList(), body, path + ":sdt", slots);
    }

    private static void flattenBrokenTocPageReferences(XWPFDocument document) {
        Set<String> bookmarkNames = new java.util.HashSet<>();
        collectDocxBookmarkNames(document, bookmarkNames);
        collectDocxSdtBookmarkNames(
                document.getDocument().getBody().getSdtList(),
                bookmarkNames
        );
        flattenBrokenTocPageReferences(
                document.getDocument().getBody().getSdtList(),
                bookmarkNames
        );
    }

    private static void collectDocxBookmarkNames(
            IBody body,
            Set<String> bookmarkNames
    ) {
        for (IBodyElement element : body.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                paragraph.getCTP().getBookmarkStartList().forEach(bookmark ->
                        bookmarkNames.add(bookmark.getName())
                );
            } else if (element instanceof XWPFTable table) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        collectDocxBookmarkNames(cell, bookmarkNames);
                    }
                }
            }
        }
    }

    private static void collectDocxSdtBookmarkNames(
            List<CTSdtBlock> blocks,
            Set<String> bookmarkNames
    ) {
        for (CTSdtBlock block : blocks) {
            if (!block.isSetSdtContent()) continue;
            CTSdtContentBlock content = block.getSdtContent();
            content.getPList().forEach(paragraph ->
                    paragraph.getBookmarkStartList().forEach(bookmark ->
                            bookmarkNames.add(bookmark.getName())
                    )
            );
            collectDocxSdtBookmarkNames(content.getSdtList(), bookmarkNames);
        }
    }

    private static void flattenBrokenTocPageReferences(
            List<CTSdtBlock> blocks,
            Set<String> bookmarkNames
    ) {
        for (CTSdtBlock block : blocks) {
            if (!block.isSetSdtContent()) continue;
            CTSdtContentBlock content = block.getSdtContent();
            content.getPList().forEach(paragraph ->
                    flattenBrokenPageReferences(paragraph.getRList(), bookmarkNames)
            );
            flattenBrokenTocPageReferences(content.getSdtList(), bookmarkNames);
        }
    }

    private static void flattenBrokenPageReferences(
            List<CTR> runs,
            Set<String> bookmarkNames
    ) {
        for (int instructionIndex = 0;
             instructionIndex < runs.size();
             instructionIndex++) {
            CTR instructionRun = runs.get(instructionIndex);
            String instruction = String.join(
                    "",
                    instructionRun.getInstrTextList().stream()
                            .map(value -> value.getStringValue())
                            .toList()
            );
            var matcher = PAGEREF_PATTERN.matcher(instruction);
            if (!matcher.find() || bookmarkNames.contains(matcher.group(1))) continue;

            int beginIndex = findFieldCharacter(
                    runs,
                    instructionIndex - 1,
                    -1,
                    STFldCharType.BEGIN
            );
            int separateIndex = findFieldCharacter(
                    runs,
                    instructionIndex + 1,
                    1,
                    STFldCharType.SEPARATE
            );
            int endIndex = separateIndex < 0
                    ? -1
                    : findFieldCharacter(
                            runs,
                            separateIndex + 1,
                            1,
                            STFldCharType.END
                    );
            if (beginIndex < 0 || separateIndex < 0 || endIndex < 0) continue;
            runs.remove(endIndex);
            runs.remove(separateIndex);
            runs.remove(instructionIndex);
            runs.remove(beginIndex);
            instructionIndex = Math.max(-1, beginIndex - 1);
        }
    }

    private static int findFieldCharacter(
            List<CTR> runs,
            int start,
            int step,
            STFldCharType.Enum expected
    ) {
        for (int index = start; index >= 0 && index < runs.size(); index += step) {
            CTR run = runs.get(index);
            if (run.sizeOfFldCharArray() == 0) continue;
            if (expected.equals(run.getFldCharArray(0).getFldCharType())) return index;
        }
        return -1;
    }

    private static void collectDocxBody(
            IBody body,
            String path,
            List<DocxTextSlot> slots
    ) {
        List<IBodyElement> elements = body.getBodyElements();
        for (int index = 0; index < elements.size(); index++) {
            IBodyElement element = elements.get(index);
            if (element instanceof XWPFParagraph paragraph) {
                collectDocxParagraph(paragraph, path + ":p" + index, slots);
            } else if (element instanceof XWPFTable table) {
                collectDocxTable(table, path + ":t" + index, slots);
            }
        }
    }

    private static void collectDocxTable(
            XWPFTable table,
            String path,
            List<DocxTextSlot> slots
    ) {
        for (int rowIndex = 0; rowIndex < table.getRows().size(); rowIndex++) {
            XWPFTableRow row = table.getRows().get(rowIndex);
            for (int cellIndex = 0; cellIndex < row.getTableCells().size(); cellIndex++) {
                XWPFTableCell cell = row.getTableCells().get(cellIndex);
                collectDocxBody(cell, path + ":r" + rowIndex + ":c" + cellIndex, slots);
            }
        }
    }

    private static void collectDocxParagraph(
            XWPFParagraph paragraph,
            String path,
            List<DocxTextSlot> slots
    ) {
        collectDocxParagraph(paragraph, path, slots, false);
    }

    private static void collectDocxParagraph(
            XWPFParagraph paragraph,
            String path,
            List<DocxTextSlot> slots,
            boolean groupVisibleRuns
    ) {
        if (groupVisibleRuns && collectDocxParagraphAsSingleSlot(paragraph, path, slots)) {
            return;
        }
        for (int runIndex = 0; runIndex < paragraph.getRuns().size(); runIndex++) {
            XWPFRun run = paragraph.getRuns().get(runIndex);
            if (run.isVanish()
                    || run.getCTR().sizeOfFldCharArray() > 0
                    || run.getCTR().sizeOfInstrTextArray() > 0) {
                continue;
            }
            for (int textIndex = 0; textIndex < run.getNumberOfTexts(); textIndex++) {
                String raw = run.getText(textIndex);
                TextFragment fragment = TextFragment.from(raw);
                if (fragment == null) continue;
                String id = path + ":r" + runIndex + ":x" + textIndex;
                int targetTextIndex = textIndex;
                slots.add(new DocxTextSlot(
                        new DocumentTranslationUnit(id, fragment.core(), path),
                        value -> run.setText(value, targetTextIndex),
                        fragment.prefix(),
                        fragment.suffix()
                ));
            }
        }
    }

    private static boolean collectDocxParagraphAsSingleSlot(
            XWPFParagraph paragraph,
            String path,
            List<DocxTextSlot> slots
    ) {
        List<DocxRunText> texts = new ArrayList<>();
        for (XWPFRun run : paragraph.getRuns()) {
            if (run.isVanish()) continue;
            if (run.getCTR().sizeOfFldCharArray() > 0
                    || run.getCTR().sizeOfInstrTextArray() > 0) {
                return false;
            }
            for (int textIndex = 0; textIndex < run.getNumberOfTexts(); textIndex++) {
                String raw = run.getText(textIndex);
                if (raw != null) texts.add(new DocxRunText(run, textIndex, raw));
            }
        }
        if (texts.isEmpty()) return false;
        String combined = String.join("", texts.stream().map(DocxRunText::text).toList());
        TextFragment fragment = TextFragment.from(combined);
        if (fragment == null) return false;
        DocxRunText target = texts.stream()
                .filter(value -> !value.text().isBlank())
                .findFirst()
                .orElse(texts.get(0));
        List<DocxRunText> immutableTexts = List.copyOf(texts);
        slots.add(new DocxTextSlot(
                new DocumentTranslationUnit(path + ":group", fragment.core(), path),
                value -> {
                    target.run().setText(value, target.textIndex());
                    for (DocxRunText text : immutableTexts) {
                        if (text != target) text.run().setText("", text.textIndex());
                    }
                },
                fragment.prefix(),
                fragment.suffix()
        ));
        return true;
    }

    private static List<DocTextSlot> collectDocSlots(HWPFDocument document) {
        List<DocTextSlot> slots = new ArrayList<>();
        collectDocRange(document.getRange(), "body", slots);
        collectDocRange(document.getHeaderStoryRange(), "header-footer", slots);
        collectDocRange(document.getFootnoteRange(), "footnote", slots);
        collectDocRange(document.getEndnoteRange(), "endnote", slots);
        return List.copyOf(slots);
    }

    private static void collectDocRange(
            Range range,
            String story,
            List<DocTextSlot> slots
    ) {
        if (range == null) return;
        for (int index = 0; index < range.numCharacterRuns(); index++) {
            CharacterRun run = range.getCharacterRun(index);
            if (run.isVanished()
                    || run.isFldVanished()
                    || run.isSpecialCharacter()
                    || run.isObj()
                    || run.isData()
                    || run.isOle2()) {
                continue;
            }
            String visible = run.text()
                    .replace("\r", "")
                    .replace("\u0007", "")
                    .replace("\u0000", "");
            TextFragment fragment = TextFragment.from(visible);
            if (fragment == null) continue;
            String id = "doc:" + story + ":" + String.format(Locale.ROOT, "%06d", index);
            slots.add(new DocTextSlot(
                    new DocumentTranslationUnit(id, fragment.core(), story),
                    run,
                    visible,
                    fragment.prefix(),
                    fragment.suffix(),
                    run.getStartOffset()
            ));
        }
    }

    private static List<PdfTextBlock> extractPdfBlocks(
            PDDocument document,
            int pageNumber
    ) throws IOException {
        PdfPositionCollector collector = new PdfPositionCollector();
        collector.setSortByPosition(true);
        collector.setStartPage(pageNumber);
        collector.setEndPage(pageNumber);
        collector.getText(document);
        return buildPdfBlocks(pageNumber, collector.positions());
    }

    private static List<PdfTextBlock> buildPdfBlocks(
            int pageNumber,
            List<TextPosition> sourcePositions
    ) {
        List<TextPosition> positions = sourcePositions.stream()
                .filter(position -> position.getUnicode() != null)
                .filter(position -> !position.getUnicode().isBlank())
                .sorted(Comparator.comparingDouble(TextPosition::getYDirAdj)
                        .thenComparingDouble(TextPosition::getXDirAdj))
                .toList();
        if (positions.isEmpty()) return List.of();

        List<PdfLine> lines = new ArrayList<>();
        PdfLine current = null;
        for (TextPosition position : positions) {
            if (current == null || !current.accepts(position)) {
                current = new PdfLine();
                lines.add(current);
            }
            current.add(position);
        }
        List<PdfBlockBuilder> blocks = new ArrayList<>();
        PdfBlockBuilder block = null;
        for (PdfLine line : lines) {
            if (line.text().isBlank()) continue;
            if (block == null || !block.accepts(line)) {
                block = new PdfBlockBuilder(line);
                blocks.add(block);
            } else {
                block.add(line);
            }
        }
        List<PdfTextBlock> result = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            PdfBlockBuilder value = blocks.get(index);
            String text = value.text().trim();
            if (!isTranslatable(text)) continue;
            result.add(new PdfTextBlock(
                    "pdf:p" + String.format(Locale.ROOT, "%04d", pageNumber)
                            + ":b" + String.format(Locale.ROOT, "%04d", index + 1),
                    text,
                    value.x(),
                    value.y(),
                    value.width(),
                    value.height(),
                    value.fontSize(),
                    value.pageWidth(),
                    value.pageHeight()
            ));
        }
        return List.copyOf(result);
    }

    private static boolean drawTranslatedBlock(
            Graphics2D graphics,
            BufferedImage image,
            double x,
            double y,
            double width,
            double height,
            String text,
            float sourceFontPoints,
            boolean protectExistingVisuals
    ) {
        TextBox original = new TextBox(
                Math.max(0, (int) Math.floor(x) - TEXT_BOX_PADDING_PIXELS),
                Math.max(0, (int) Math.floor(y) - TEXT_BOX_PADDING_PIXELS),
                Math.min(
                        image.getWidth(),
                        (int) Math.ceil(x + width) + TEXT_BOX_PADDING_PIXELS
                ),
                Math.min(
                        image.getHeight(),
                        (int) Math.ceil(y + height) + TEXT_BOX_PADDING_PIXELS
                )
        );
        if (original.width() <= 4 || original.height() <= 4) return false;

        float maximumPoints = Math.max(
                MIN_PDF_FONT_POINTS,
                Math.min(24f, Math.max(sourceFontPoints, 10f))
        );
        PlacementCandidate selected = findBestPlacement(
                graphics,
                text,
                maximumPoints,
                placementBoxes(
                        image,
                        original,
                        protectExistingVisuals
                )
        );
        if (selected == null) {
            TextBox expanded = expandTextBox(image, original);
            selected = findBestPlacement(
                    graphics,
                    text,
                    maximumPoints,
                    placementBoxes(
                            image,
                            expanded,
                            protectExistingVisuals
                    )
            );
        }
        if (selected == null) return false;
        paintTranslatedBlock(graphics, selected.box(), selected.placement());
        return true;
    }

    private static PlacementCandidate findBestPlacement(
            Graphics2D graphics,
            String text,
            float maximumPoints,
            List<TextBox> candidates
    ) {
        PlacementCandidate selected = null;
        for (TextBox candidate : candidates) {
            TextPlacement placement = findPlacement(
                    graphics,
                    text,
                    maximumPoints,
                    candidate.width() - 6,
                    candidate.height() - 4
            );
            if (placement == null) continue;
            if (selected == null
                    || placement.points() > selected.placement().points()
                    || (placement.points() == selected.placement().points()
                    && candidate.area() > selected.box().area())) {
                selected = new PlacementCandidate(candidate, placement);
            }
        }
        return selected;
    }

    private static TextBox expandTextBox(BufferedImage image, TextBox original) {
        int horizontalPadding = Math.max(
                EXPANDED_TEXT_BOX_MARGIN_PIXELS,
                original.width() / 3
        );
        int verticalPadding = Math.max(
                EXPANDED_TEXT_BOX_MARGIN_PIXELS,
                original.height() * 2
        );
        return new TextBox(
                Math.max(0, original.left() - horizontalPadding),
                Math.max(0, original.top() - verticalPadding),
                Math.min(image.getWidth(), original.right() + horizontalPadding),
                Math.min(image.getHeight(), original.bottom() + verticalPadding)
        );
    }

    private static List<TextBox> placementBoxes(
            BufferedImage image,
            TextBox original,
            boolean protectExistingVisuals
    ) {
        if (!protectExistingVisuals) return List.of(original);
        TextBox visual = protectedVisualBounds(image, original);
        if (visual == null) return List.of(original);

        List<TextBox> candidates = new ArrayList<>();
        addCandidate(candidates, new TextBox(
                original.left(),
                original.top(),
                Math.max(original.left(), visual.left() - VISUAL_BOX_MARGIN_PIXELS),
                original.bottom()
        ));
        addCandidate(candidates, new TextBox(
                Math.min(original.right(), visual.right() + VISUAL_BOX_MARGIN_PIXELS),
                original.top(),
                original.right(),
                original.bottom()
        ));
        addCandidate(candidates, new TextBox(
                original.left(),
                original.top(),
                original.right(),
                Math.max(original.top(), visual.top() - VISUAL_BOX_MARGIN_PIXELS)
        ));
        addCandidate(candidates, new TextBox(
                original.left(),
                Math.min(original.bottom(), visual.bottom() + VISUAL_BOX_MARGIN_PIXELS),
                original.right(),
                original.bottom()
        ));
        return candidates.stream()
                .sorted(Comparator.comparingLong(TextBox::area).reversed())
                .toList();
    }

    private static void addCandidate(List<TextBox> candidates, TextBox candidate) {
        if (candidate.width() >= MIN_TEXT_BOX_PIXELS
                && candidate.height() >= MIN_TEXT_BOX_PIXELS) {
            candidates.add(candidate);
        }
    }

    private static TextBox protectedVisualBounds(
            BufferedImage image,
            TextBox box
    ) {
        int sampleStep = Math.max(1, Math.min(box.width(), box.height()) / 240);
        long sampledPixels = 0;
        long protectedPixels = 0;
        int minimumX = box.right();
        int minimumY = box.bottom();
        int maximumX = box.left();
        int maximumY = box.top();
        for (int pixelY = box.top(); pixelY < box.bottom(); pixelY += sampleStep) {
            for (int pixelX = box.left(); pixelX < box.right(); pixelX += sampleStep) {
                int rgb = image.getRGB(pixelX, pixelY);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                int maximum = Math.max(
                        red,
                        Math.max(green, blue)
                );
                int minimum = Math.min(
                        red,
                        Math.min(green, blue)
                );
                sampledPixels++;
                if (maximum - minimum >= PROTECTED_COLOR_CHANNEL_SPREAD
                        && maximum >= 80
                        && minimum <= 230) {
                    protectedPixels++;
                    minimumX = Math.min(minimumX, pixelX);
                    minimumY = Math.min(minimumY, pixelY);
                    maximumX = Math.max(maximumX, pixelX);
                    maximumY = Math.max(maximumY, pixelY);
                }
            }
        }
        long requiredPixels = Math.max(
                64,
                (long) Math.ceil(sampledPixels * PROTECTED_COLOR_PIXEL_RATIO)
        );
        if (protectedPixels < requiredPixels
                || protectedPixels >= sampledPixels * BACKGROUND_COLOR_PIXEL_RATIO) {
            return null;
        }
        return new TextBox(
                Math.max(box.left(), minimumX - sampleStep),
                Math.max(box.top(), minimumY - sampleStep),
                Math.min(box.right(), maximumX + sampleStep + 1),
                Math.min(box.bottom(), maximumY + sampleStep + 1)
        );
    }

    private static TextPlacement findPlacement(
            Graphics2D graphics,
            String text,
            float maximumPoints,
            int maximumWidth,
            int maximumHeight
    ) {
        if (maximumWidth <= 0 || maximumHeight <= 0) return null;
        for (float points = maximumPoints; points >= MIN_PDF_FONT_POINTS; points -= 0.5f) {
            List<String> lines = wrapText(graphics, text, points, maximumWidth);
            List<TextLine> layouts = layoutLines(graphics, lines, points);
            double totalHeight = layouts.stream().mapToDouble(TextLine::height).sum();
            if (!layouts.isEmpty() && totalHeight <= maximumHeight) {
                return new TextPlacement(points, layouts);
            }
        }
        return null;
    }

    private static void paintTranslatedBlock(
            Graphics2D graphics,
            TextBox box,
            TextPlacement placement
    ) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(box.left(), box.top(), box.width(), box.height());
        graphics.setColor(Color.BLACK);
        float top = box.top() + 2;
        for (TextLine line : placement.lines()) {
            float baseline = top + line.ascent();
            if (line.layout() != null) {
                line.layout().draw(graphics, box.left() + 3, baseline);
            }
            top += line.height();
        }
    }

    static Font pdfFontForText(float points, String text) {
        List<Font> candidates = fontsForPoints(points);
        Font best = candidates.get(candidates.size() - 1);
        int fewestMissing = missingGlyphCount(best, text);
        for (Font candidate : candidates) {
            int missing = missingGlyphCount(candidate, text);
            if (missing == 0) return candidate;
            if (missing < fewestMissing) {
                best = candidate;
                fewestMissing = missing;
            }
        }
        return best;
    }

    private static Font fontForCodePoint(float points, int codePoint) {
        for (Font font : fontsForPoints(points)) {
            if (font.canDisplay(codePoint)) return font;
        }
        return fontsForPoints(points).get(fontsForPoints(points).size() - 1);
    }

    private static List<Font> fontsForPoints(float points) {
        int pixelSize = Math.max(
                1,
                Math.round(points * PDF_RENDER_DPI / 72f)
        );
        return PDF_FONTS_BY_PIXEL_SIZE.computeIfAbsent(pixelSize, ignored -> {
            List<Font> fonts = new ArrayList<>();
            for (String family : PREFERRED_PDF_FONT_FAMILIES) {
                if (!AVAILABLE_FONT_FAMILIES.contains(family)) continue;
                Font font = new Font(family, Font.PLAIN, pixelSize);
                if (!fonts.contains(font)) fonts.add(font);
            }
            Font fallback = new Font(Font.SANS_SERIF, Font.PLAIN, pixelSize);
            if (!fonts.contains(fallback)) fonts.add(fallback);
            return List.copyOf(fonts);
        });
    }

    private static int missingGlyphCount(Font font, String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) text.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .filter(codePoint -> !font.canDisplay(codePoint))
                .count();
    }

    private static void addRasterPage(PDDocument output, BufferedImage image) throws IOException {
        float widthPoints = image.getWidth() * 72f / PDF_RENDER_DPI;
        float heightPoints = image.getHeight() * 72f / PDF_RENDER_DPI;
        PDPage outputPage = new PDPage(new PDRectangle(widthPoints, heightPoints));
        output.addPage(outputPage);
        PDImageXObject pageImage = JPEGFactory.createFromImage(
                output,
                image,
                OUTPUT_JPEG_QUALITY,
                Math.round(PDF_RENDER_DPI)
        );
        try (PDPageContentStream content = new PDPageContentStream(output, outputPage)) {
            content.drawImage(pageImage, 0, 0, widthPoints, heightPoints);
        }
    }

    private static List<String> wrapText(
            Graphics2D graphics,
            String text,
            float points,
            int maximumWidth
    ) {
        if (maximumWidth <= 0) return List.of();
        List<String> result = new ArrayList<>();
        for (String paragraph : text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\t", "    ")
                .split("\n", -1)) {
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            BreakIterator iterator = BreakIterator.getLineInstance(Locale.ROOT);
            iterator.setText(paragraph);
            StringBuilder line = new StringBuilder();
            int start = iterator.first();
            for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
                String token = paragraph.substring(start, end);
                String candidate = line + token;
                if (!line.isEmpty() && textWidth(graphics, candidate, points) > maximumWidth) {
                    result.add(line.toString().stripTrailing());
                    line.setLength(0);
                }
                if (textWidth(graphics, token, points) <= maximumWidth) {
                    line.append(token);
                    continue;
                }
                for (int offset = 0; offset < token.length(); ) {
                    int next = token.offsetByCodePoints(offset, 1);
                    String character = token.substring(offset, next);
                    if (!line.isEmpty()
                            && textWidth(graphics, line + character, points) > maximumWidth) {
                        result.add(line.toString());
                        line.setLength(0);
                    }
                    line.append(character);
                    offset = next;
                }
            }
            if (!line.isEmpty()) result.add(line.toString().stripTrailing());
        }
        return result;
    }

    private static double textWidth(Graphics2D graphics, String text, float points) {
        TextLine line = layoutLine(graphics, text, points);
        return line.layout() == null ? 0 : line.layout().getAdvance();
    }

    private static List<TextLine> layoutLines(
            Graphics2D graphics,
            List<String> lines,
            float points
    ) {
        return lines.stream().map(line -> layoutLine(graphics, line, points)).toList();
    }

    private static TextLine layoutLine(
            Graphics2D graphics,
            String text,
            float points
    ) {
        if (text == null || text.isEmpty()) {
            int height = maximumLineHeight(graphics, points);
            FontMetrics metrics = graphics.getFontMetrics(fontsForPoints(points).get(0));
            return new TextLine("", null, metrics.getAscent(),
                    Math.max(1, height - metrics.getAscent()), 0);
        }
        AttributedString attributed = new AttributedString(text);
        int runStart = 0;
        int offset = 0;
        Font runFont = null;
        while (offset < text.length()) {
            int codePoint = text.codePointAt(offset);
            int next = text.offsetByCodePoints(offset, 1);
            Font font = fontForCodePoint(points, codePoint);
            if (runFont != null && !runFont.equals(font)) {
                attributed.addAttribute(TextAttribute.FONT, runFont, runStart, offset);
                runStart = offset;
            }
            runFont = font;
            offset = next;
        }
        attributed.addAttribute(TextAttribute.FONT, runFont, runStart, text.length());
        TextLayout layout = new TextLayout(
                attributed.getIterator(),
                graphics.getFontRenderContext()
        );
        return new TextLine(
                text,
                layout,
                layout.getAscent(),
                layout.getDescent(),
                layout.getLeading()
        );
    }

    private static int maximumLineHeight(Graphics2D graphics, float points) {
        return fontsForPoints(points).stream()
                .map(graphics::getFontMetrics)
                .mapToInt(FontMetrics::getHeight)
                .max()
                .orElse(1);
    }

    private static Map<String, String> normalizeTranslations(
            DocumentTranslationPlan plan,
            Map<String, String> values
    ) {
        Map<String, String> source = values == null ? Map.of() : values;
        Map<String, String> normalized = new LinkedHashMap<>();
        for (DocumentTranslationUnit unit : plan.textUnits()) {
            String translated = source.get(unit.id());
            if (translated == null || translated.isBlank()) {
                throw invalidDocument("模型没有返回完整译文：" + unit.id());
            }
            normalized.put(unit.id(), translated.strip());
        }
        if (!source.keySet().equals(normalized.keySet())) {
            throw invalidDocument("模型返回了无法匹配的文档片段");
        }
        return Map.copyOf(normalized);
    }

    private static Map<Integer, VisualPageTranslation> visualTranslationsByPage(
            DocumentTranslationPlan plan,
            List<VisualPageTranslation> translations
    ) {
        Set<Integer> expected = plan.visualPages().stream()
                .map(DocumentVisualPage::pageNumber)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<Integer, VisualPageTranslation> result = new HashMap<>();
        for (VisualPageTranslation translation : translations) {
            if (!expected.contains(translation.pageNumber())
                    || result.put(translation.pageNumber(), translation) != null) {
                throw invalidDocument("扫描 PDF 返回了无效页码");
            }
        }
        if (!result.keySet().equals(expected)) {
            throw invalidDocument("扫描 PDF 译文缺少页面");
        }
        return Map.copyOf(result);
    }

    private static void verifyUnits(
            List<DocumentTranslationUnit> expected,
            List<DocumentTranslationUnit> actual
    ) {
        List<String> expectedIds = expected.stream().map(DocumentTranslationUnit::id).toList();
        List<String> actualIds = actual.stream().map(DocumentTranslationUnit::id).toList();
        if (!expectedIds.equals(actualIds)) {
            throw invalidDocument("文档结构在翻译过程中发生变化，请重新上传后重试");
        }
    }

    private static void validatePdf(PDDocument document) {
        if (document.getNumberOfPages() <= 0) {
            throw invalidDocument("PDF 没有可读取的页面");
        }
        if (!document.getSignatureDictionaries().isEmpty()) {
            throw invalidDocument("暂不支持带数字签名的 PDF");
        }
    }

    private static void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        );
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
        );
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void ensureCharacterLimit(int characters, int maximum) {
        if (characters > maximum) {
            throw invalidDocument(
                    "可翻译正文超过 " + maximum + " 字符，请拆分文档后重试"
            );
        }
    }

    private static boolean isTranslatable(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) return false;
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (URL_PREFIXES.stream().anyMatch(lower::startsWith)
                || EMAIL_PATTERN.matcher(normalized).matches()) {
            return false;
        }
        return normalized.codePoints().anyMatch(Character::isLetter);
    }

    private static int codePointCount(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private static String extension(String name) {
        if (name == null) return "";
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }

    private static FeatureValidationException invalidDocument(String message) {
        return new FeatureValidationException("document", message);
    }

    private record DocxTextSlot(
            DocumentTranslationUnit unit,
            DocxTextWriter writer,
            String prefix,
            String suffix
    ) {
        private void writeTranslation(String translation) {
            writer.write(prefix + translation + suffix);
        }
    }

    @FunctionalInterface
    private interface DocxTextWriter {
        void write(String value);
    }

    private record DocxRunText(XWPFRun run, int textIndex, String text) {
    }

    private record DocTextSlot(
            DocumentTranslationUnit unit,
            CharacterRun run,
            String originalVisibleText,
            String prefix,
            String suffix,
            int startOffset
    ) {
    }

    private record TextFragment(String prefix, String core, String suffix) {
        private static TextFragment from(String raw) {
            if (raw == null || raw.isBlank()) return null;
            int start = 0;
            while (start < raw.length()) {
                int codePoint = raw.codePointAt(start);
                if (!Character.isWhitespace(codePoint)) break;
                start += Character.charCount(codePoint);
            }
            int end = raw.length();
            while (end > start) {
                int codePoint = raw.codePointBefore(end);
                if (!Character.isWhitespace(codePoint)) break;
                end -= Character.charCount(codePoint);
            }
            String core = raw.substring(start, end);
            if (!isTranslatable(core)) return null;
            return new TextFragment(
                    raw.substring(0, start),
                    core,
                    raw.substring(end)
            );
        }
    }

    private record PdfTextBlock(
            String id,
            String text,
            double x,
            double y,
            double width,
            double height,
            float fontSize,
            double pageWidth,
            double pageHeight
    ) {
    }

    private record TextPlacement(float points, List<TextLine> lines) {
    }

    private record PlacementCandidate(TextBox box, TextPlacement placement) {
    }

    private record TextLine(
            String text,
            TextLayout layout,
            float ascent,
            float descent,
            float leading
    ) {
        private float height() {
            return ascent + descent + leading;
        }
    }

    private record TextBox(int left, int top, int right, int bottom) {
        private int width() {
            return right - left;
        }

        private int height() {
            return bottom - top;
        }

        private long area() {
            return (long) width() * height();
        }
    }

    private static final class PdfPositionCollector extends PDFTextStripper {
        private final List<TextPosition> positions = new ArrayList<>();

        private PdfPositionCollector() throws IOException {
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            positions.add(text);
            super.processTextPosition(text);
        }

        private List<TextPosition> positions() {
            return List.copyOf(positions);
        }
    }

    private static final class PdfLine {
        private final List<TextPosition> positions = new ArrayList<>();
        private float baseline;

        private boolean accepts(TextPosition position) {
            if (positions.isEmpty()) return true;
            if (Math.abs(position.getYDirAdj() - baseline)
                    > Math.max(2f, position.getHeightDir() * 0.55f)) {
                return false;
            }
            double previousRight = positions.stream()
                    .mapToDouble(value -> value.getXDirAdj() + value.getWidthDirAdj())
                    .max()
                    .orElse(position.getXDirAdj());
            double horizontalGap = position.getXDirAdj() - previousRight;
            double maximumGap = Math.max(
                    24f,
                    Math.max(
                            position.getFontSizeInPt() * 4f,
                            position.getWidthOfSpace() * 8f
                    )
            );
            return horizontalGap <= maximumGap;
        }

        private void add(TextPosition position) {
            positions.add(position);
            baseline = positions.stream()
                    .map(TextPosition::getYDirAdj)
                    .reduce(0f, Float::sum) / positions.size();
        }

        private String text() {
            List<TextPosition> ordered = positions.stream()
                    .sorted(Comparator.comparingDouble(TextPosition::getXDirAdj))
                    .toList();
            StringBuilder text = new StringBuilder();
            float previousEnd = -1;
            for (TextPosition position : ordered) {
                if (previousEnd >= 0) {
                    float gap = position.getXDirAdj() - previousEnd;
                    if (gap > Math.max(1.5f, position.getWidthOfSpace() * 0.45f)) {
                        text.append(' ');
                    }
                }
                text.append(position.getUnicode());
                previousEnd = Math.max(previousEnd, position.getXDirAdj() + position.getWidthDirAdj());
            }
            return text.toString().replace("\u0000", "").strip();
        }

        private double x() {
            return positions.stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(0);
        }

        private double y() {
            return positions.stream()
                    .mapToDouble(position -> position.getYDirAdj()
                            - Math.max(position.getHeightDir(), position.getFontSizeInPt()))
                    .min()
                    .orElse(0);
        }

        private double right() {
            return positions.stream()
                    .mapToDouble(position -> position.getXDirAdj() + position.getWidthDirAdj())
                    .max()
                    .orElse(x());
        }

        private double bottom() {
            return positions.stream()
                    .mapToDouble(position -> position.getYDirAdj()
                            + Math.max(1f, position.getFontSizeInPt() * 0.25f))
                    .max()
                    .orElse(y());
        }

        private float fontSize() {
            return (float) positions.stream()
                    .mapToDouble(TextPosition::getFontSizeInPt)
                    .max()
                    .orElse(10);
        }

        private double pageWidth() {
            return positions.get(0).getPageWidth();
        }

        private double pageHeight() {
            return positions.get(0).getPageHeight();
        }
    }

    private static final class PdfBlockBuilder {
        private final List<PdfLine> lines = new ArrayList<>();

        private PdfBlockBuilder(PdfLine line) {
            lines.add(line);
        }

        private boolean accepts(PdfLine line) {
            PdfLine previous = lines.get(lines.size() - 1);
            double gap = line.y() - previous.bottom();
            double overlap = Math.max(
                    0,
                    Math.min(previous.right(), line.right()) - Math.max(previous.x(), line.x())
            );
            double narrower = Math.min(previous.right() - previous.x(), line.right() - line.x());
            return gap >= -2
                    && gap <= Math.max(8, previous.fontSize() * 1.15)
                    && (overlap >= narrower * 0.2
                    || Math.abs(previous.x() - line.x()) <= previous.fontSize() * 2);
        }

        private void add(PdfLine line) {
            lines.add(line);
        }

        private String text() {
            return String.join("\n", lines.stream().map(PdfLine::text).toList());
        }

        private double x() {
            return lines.stream().mapToDouble(PdfLine::x).min().orElse(0);
        }

        private double y() {
            return lines.stream().mapToDouble(PdfLine::y).min().orElse(0);
        }

        private double width() {
            double right = lines.stream().mapToDouble(PdfLine::right).max().orElse(x());
            return Math.max(1, right - x());
        }

        private double height() {
            double bottom = lines.stream().mapToDouble(PdfLine::bottom).max().orElse(y());
            return Math.max(1, bottom - y());
        }

        private float fontSize() {
            return (float) lines.stream().mapToDouble(PdfLine::fontSize).max().orElse(10);
        }

        private double pageWidth() {
            return lines.get(0).pageWidth();
        }

        private double pageHeight() {
            return lines.get(0).pageHeight();
        }
    }
}
