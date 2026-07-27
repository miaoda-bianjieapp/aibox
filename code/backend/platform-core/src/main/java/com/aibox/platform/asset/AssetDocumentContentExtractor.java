package com.aibox.platform.asset;

import com.aibox.feature.spi.DocumentContentExtractor;
import com.aibox.feature.spi.DocumentExtractionOptions;
import com.aibox.feature.spi.DocumentExtractionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.ModelAsset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.apache.poi.sl.usermodel.GroupShape;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.SlideShowFactory;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AssetDocumentContentExtractor implements DocumentContentExtractor {

    private static final int OCR_PAGE_TEXT_THRESHOLD = 24;
    private static final int MAX_VISUAL_PRESENTATION_SLIDES = 30;
    private static final float OCR_RENDER_DPI = 120f;
    private static final float LAYOUT_RENDER_DPI = 144f;
    private static final float OCR_JPEG_QUALITY = 0.82f;
    private static final long MAX_RENDERED_VISUAL_BYTES = 100L * 1024 * 1024;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final AssetService assetService;

    public AssetDocumentContentExtractor(AssetService assetService) {
        this.assetService = assetService;
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentExtractionResult extract(UUID assetId, int maxCharacters) {
        return extract(assetId, DocumentExtractionOptions.textAndOcr(maxCharacters));
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentExtractionResult extract(
            UUID assetId,
            DocumentExtractionOptions options
    ) {
        AssetService.AssetStoredFile stored = assetService.openForPreview(assetId);
        String extension = extension(stored.asset().name());
        return switch (extension) {
            case ".pdf" -> extractPdf(assetId, stored.path(), options);
            case ".doc", ".docx" ->
                    extractWord(stored.path(), extension, options.maxCharacters());
            case ".xls", ".xlsx" ->
                    extractWorkbook(stored.path(), extension, options.maxCharacters());
            case ".csv" -> extractCsv(stored.path(), options.maxCharacters());
            case ".md", ".markdown", ".txt" ->
                    extractUtf8Text(stored.path(), extension, options.maxCharacters());
            case ".json" -> extractJson(stored.path(), options.maxCharacters());
            case ".ppt", ".pptx" ->
                    extractPresentation(
                            assetId,
                            stored.path(),
                            extension,
                            options.maxCharacters()
                    );
            default -> throw invalidDocument(
                    "不支持该文档格式，请上传 PDF、Office、Markdown、TXT、JSON 或 CSV"
            );
        };
    }

    private DocumentExtractionResult extractPdf(
            UUID assetId,
            Path path,
            DocumentExtractionOptions options
    ) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            if (document.getNumberOfPages() <= 0) {
                throw invalidDocument("PDF 没有可读取的页面");
            }
            if (document.getNumberOfPages() > options.maxPdfPages()) {
                throw invalidDocument("PDF 不能超过 " + options.maxPdfPages() + " 页");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            LimitedTextBuilder extracted = new LimitedTextBuilder(options.maxCharacters());
            List<Integer> ocrPageNumbers = new ArrayList<>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                stripper.setStartPage(index + 1);
                stripper.setEndPage(index + 1);
                String pageText = normalizeText(stripper.getText(document));
                if (visibleCharacterCount(pageText) < OCR_PAGE_TEXT_THRESHOLD) {
                    ocrPageNumbers.add(index + 1);
                }
                if (!pageText.isBlank()) {
                    extracted.appendSection("第 " + (index + 1) + " 页", pageText);
                }
            }

            List<Integer> visualPageNumbers;
            float renderDpi;
            if (options.pdfVisualMode() == DocumentExtractionOptions.PdfVisualMode.ALL_PAGES) {
                visualPageNumbers = new ArrayList<>(document.getNumberOfPages());
                for (int pageNumber = 1; pageNumber <= document.getNumberOfPages(); pageNumber++) {
                    visualPageNumbers.add(pageNumber);
                }
                renderDpi = LAYOUT_RENDER_DPI;
            } else {
                visualPageNumbers = ocrPageNumbers;
                renderDpi = OCR_RENDER_DPI;
            }
            List<ModelAsset> visualImages = renderPdfPages(
                    assetId,
                    document,
                    visualPageNumbers,
                    renderDpi
            );
            return new DocumentExtractionResult(
                    extracted.value(),
                    "pdf",
                    document.getNumberOfPages(),
                    0,
                    visualImages,
                    visualPageNumbers
            );
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidDocument("PDF 已损坏、受密码保护或无法读取");
        }
    }

    private List<ModelAsset> renderPdfPages(
            UUID assetId,
            PDDocument document,
            List<Integer> pageNumbers,
            float renderDpi
    ) throws IOException {
        if (pageNumbers.isEmpty()) return List.of();
        PDFRenderer renderer = new PDFRenderer(document);
        List<ModelAsset> images = new ArrayList<>();
        long totalBytes = 0;
        for (int pageNumber : pageNumbers) {
            BufferedImage image = renderer.renderImageWithDPI(
                    pageNumber - 1,
                    renderDpi,
                    ImageType.RGB
            );
            byte[] content;
            try {
                content = encodeJpeg(image);
            } finally {
                image.flush();
            }
            totalBytes += content.length;
            if (totalBytes > MAX_RENDERED_VISUAL_BYTES) {
                throw invalidDocument("扫描页过多或图像过大，请拆分 PDF 后重试");
            }
            UUID pageAssetId = UUID.nameUUIDFromBytes(
                    (assetId + ":pdf-page:" + pageNumber).getBytes(StandardCharsets.UTF_8)
            );
            images.add(new ModelAsset(
                    pageAssetId,
                    "page-" + String.format(Locale.ROOT, "%04d", pageNumber) + ".jpg",
                    "image/jpeg",
                    content
            ));
        }
        return List.copyOf(images);
    }

    private DocumentExtractionResult extractWord(
            Path path,
            String extension,
            int maxCharacters
    ) {
        try (POITextExtractor extractor = ExtractorFactory.createExtractor(path.toFile())) {
            String text = normalizeText(extractor.getText());
            ensureNotEmpty(text);
            ensureWithinLimit(text, maxCharacters);
            return new DocumentExtractionResult(
                    text,
                    extension.substring(1),
                    0,
                    0,
                    List.of(),
                    List.of()
            );
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidDocument("Word 文档已损坏、受密码保护或无法读取");
        }
    }

    private DocumentExtractionResult extractPresentation(
            UUID assetId,
            Path path,
            String extension,
            int maxCharacters
    ) {
        try (SlideShow<?, ?> presentation = SlideShowFactory.create(path.toFile())) {
            List<? extends Slide<?, ?>> visibleSlides = presentation.getSlides().stream()
                    .filter(slide -> !slide.isHidden())
                    .toList();
            if (visibleSlides.isEmpty()) {
                throw invalidDocument("PowerPoint 没有可读取的可见幻灯片");
            }
            String text = extractPresentationText(visibleSlides);
            ensureWithinLimit(text, maxCharacters);
            int minimumExpectedText = Math.max(32, visibleSlides.size() * 10);
            boolean requiresVision = visibleCharacterCount(text) < minimumExpectedText;
            if (!requiresVision) {
                return new DocumentExtractionResult(
                        text,
                        extension.substring(1),
                        visibleSlides.size(),
                        0,
                        List.of(),
                        List.of()
                );
            }
            if (visibleSlides.size() > MAX_VISUAL_PRESENTATION_SLIDES) {
                throw invalidDocument(
                        "图片型 PPT/PPTX 最多支持 30 页，请拆分演示文稿后重试"
                );
            }
            RenderedSlides rendered = renderPresentationSlides(
                    assetId,
                    presentation,
                    visibleSlides
            );
            return new DocumentExtractionResult(
                    text,
                    extension.substring(1),
                    visibleSlides.size(),
                    0,
                    rendered.images(),
                    rendered.slideNumbers()
            );
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidDocument("PowerPoint 文档已损坏、受密码保护或无法读取");
        }
    }

    private static String extractPresentationText(List<? extends Slide<?, ?>> slides) {
        StringBuilder text = new StringBuilder();
        for (Slide<?, ?> slide : slides) {
            StringBuilder slideText = new StringBuilder();
            for (Shape<?, ?> shape : slide.getShapes()) {
                appendPresentationShapeText(shape, slideText);
            }
            String normalizedSlideText = normalizeText(slideText.toString());
            if (normalizedSlideText.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(normalizedSlideText);
        }
        return text.toString();
    }

    private static void appendPresentationShapeText(
            Shape<?, ?> shape,
            StringBuilder text
    ) {
        if (shape instanceof TextShape<?, ?> textShape) {
            appendPresentationText(text, textShape.getText());
            return;
        }
        if (shape instanceof TableShape<?, ?> table) {
            for (int row = 0; row < table.getNumberOfRows(); row++) {
                for (int column = 0; column < table.getNumberOfColumns(); column++) {
                    appendPresentationText(text, table.getCell(row, column).getText());
                }
            }
            return;
        }
        if (shape instanceof GroupShape<?, ?> group) {
            for (Shape<?, ?> child : group.getShapes()) {
                appendPresentationShapeText(child, text);
            }
        }
    }

    private static void appendPresentationText(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(value);
    }

    private RenderedSlides renderPresentationSlides(
            UUID assetId,
            SlideShow<?, ?> presentation,
            List<? extends Slide<?, ?>> slides
    ) throws IOException {
        Dimension pageSize = presentation.getPageSize();
        if (pageSize.width <= 0 || pageSize.height <= 0) {
            throw invalidDocument("PowerPoint 页面尺寸无效");
        }
        double scale = Math.min(2.0, 1600.0 / Math.max(pageSize.width, pageSize.height));
        scale = Math.max(1.0, scale);
        int width = Math.max(1, (int) Math.ceil(pageSize.width * scale));
        int height = Math.max(1, (int) Math.ceil(pageSize.height * scale));
        List<ModelAsset> images = new ArrayList<>();
        List<Integer> slideNumbers = new ArrayList<>();
        long totalBytes = 0;
        for (Slide<?, ?> slide : slides) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
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
                graphics.scale(scale, scale);
                slide.draw(graphics);
            } finally {
                graphics.dispose();
            }
            byte[] content;
            try {
                content = encodeJpeg(image);
            } finally {
                image.flush();
            }
            totalBytes += content.length;
            if (totalBytes > MAX_RENDERED_VISUAL_BYTES) {
                throw invalidDocument("幻灯片渲染结果过大，请拆分演示文稿后重试");
            }
            int slideNumber = slide.getSlideNumber();
            UUID slideAssetId = UUID.nameUUIDFromBytes(
                    (assetId + ":presentation-slide:" + slideNumber)
                            .getBytes(StandardCharsets.UTF_8)
            );
            images.add(new ModelAsset(
                    slideAssetId,
                    "slide-" + String.format(Locale.ROOT, "%04d", slideNumber) + ".jpg",
                    "image/jpeg",
                    content
            ));
            slideNumbers.add(slideNumber);
        }
        return new RenderedSlides(List.copyOf(images), List.copyOf(slideNumbers));
    }

    private DocumentExtractionResult extractWorkbook(
            Path path,
            String extension,
            int maxCharacters
    ) {
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            LimitedTextBuilder extracted = new LimitedTextBuilder(maxCharacters);
            int visibleSheets = 0;
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                if (workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex)) {
                    continue;
                }
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                String sheetText = extractSheet(sheet, formatter, maxCharacters);
                visibleSheets++;
                extracted.appendSection(
                        "工作表：" + sheet.getSheetName(),
                        sheetText.isBlank() ? "（无非空单元格）" : sheetText
                );
            }
            ensureNotEmpty(extracted.value());
            return new DocumentExtractionResult(
                    extracted.value(),
                    extension.substring(1),
                    0,
                    visibleSheets,
                    List.of(),
                    List.of()
            );
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidDocument("Excel 文档已损坏、受密码保护或无法读取");
        }
    }

    private String extractSheet(Sheet sheet, DataFormatter formatter, int maxCharacters) {
        LimitedTextBuilder rows = new LimitedTextBuilder(maxCharacters);
        for (Row row : sheet) {
            int lastCell = row.getLastCellNum();
            if (lastCell < 0) continue;
            List<String> values = new ArrayList<>(lastCell);
            boolean hasContent = false;
            for (int column = 0; column < lastCell; column++) {
                Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String value = cell == null ? "" : normalizeCell(formatter.formatCellValue(cell));
                values.add(value);
                if (!value.isBlank()) hasContent = true;
            }
            if (!hasContent) continue;
            int lastNonEmpty = values.size() - 1;
            while (lastNonEmpty >= 0 && values.get(lastNonEmpty).isBlank()) lastNonEmpty--;
            rows.append("第 " + (row.getRowNum() + 1) + " 行\t");
            rows.append(String.join("\t", values.subList(0, lastNonEmpty + 1)));
            rows.append("\n");
        }
        return rows.value().strip();
    }

    private DocumentExtractionResult extractCsv(Path path, int maxCharacters) {
        try (InputStream input = Files.newInputStream(path);
             Reader decoded = new InputStreamReader(
                     input,
                     StandardCharsets.UTF_8.newDecoder()
                             .onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT)
             );
             PushbackReader reader = withoutUtf8Bom(decoded);
             CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {
            LimitedTextBuilder extracted = new LimitedTextBuilder(maxCharacters);
            for (CSVRecord record : parser) {
                List<String> values = new ArrayList<>(record.size());
                boolean hasContent = false;
                for (String raw : record) {
                    String value = normalizeCell(raw);
                    values.add(value);
                    if (!value.isBlank()) hasContent = true;
                }
                if (!hasContent) continue;
                int lastNonEmpty = values.size() - 1;
                while (lastNonEmpty >= 0 && values.get(lastNonEmpty).isBlank()) lastNonEmpty--;
                extracted.append("第 " + record.getRecordNumber() + " 行\t");
                extracted.append(String.join("\t", values.subList(0, lastNonEmpty + 1)));
                extracted.append("\n");
            }
            ensureNotEmpty(extracted.value());
            return new DocumentExtractionResult(
                    extracted.value().strip(),
                    "csv",
                    0,
                    1,
                    List.of(),
                    List.of()
            );
        } catch (CharacterCodingException exception) {
            throw invalidDocument("CSV 不是有效的 UTF-8 编码，请转换为 UTF-8 后重试");
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            if (hasCharacterCodingCause(exception)) {
                throw invalidDocument("CSV 不是有效的 UTF-8 编码，请转换为 UTF-8 后重试");
            }
            throw invalidDocument("CSV 已损坏或无法解析");
        }
    }

    private DocumentExtractionResult extractUtf8Text(
            Path path,
            String extension,
            int maxCharacters
    ) {
        String text = normalizeText(readUtf8(path));
        ensureNotEmpty(text);
        ensureWithinLimit(text, maxCharacters);
        return new DocumentExtractionResult(
                text,
                extension.substring(1),
                0,
                0,
                List.of(),
                List.of()
        );
    }

    private DocumentExtractionResult extractJson(Path path, int maxCharacters) {
        try {
            JsonNode document = JSON_MAPPER.readTree(readUtf8(path));
            if (document == null) {
                throw invalidDocument("JSON 文档内容为空");
            }
            String text = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document);
            ensureWithinLimit(text, maxCharacters);
            return new DocumentExtractionResult(
                    text,
                    "json",
                    0,
                    0,
                    List.of(),
                    List.of()
            );
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            if (hasCharacterCodingCause(exception)) {
                throw invalidDocument("JSON 不是有效的 UTF-8 编码，请转换为 UTF-8 后重试");
            }
            throw invalidDocument("JSON 格式无效或无法解析");
        }
    }

    private static String readUtf8(Path path) {
        try {
            byte[] content = Files.readAllBytes(path);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString()
                    .replaceFirst("^\\uFEFF", "");
        } catch (CharacterCodingException exception) {
            throw invalidDocument("文本文件不是有效的 UTF-8 编码，请转换为 UTF-8 后重试");
        } catch (IOException exception) {
            throw invalidDocument("文本文件无法读取");
        }
    }

    private static PushbackReader withoutUtf8Bom(Reader source) throws IOException {
        PushbackReader reader = new PushbackReader(source, 1);
        int first = reader.read();
        if (first >= 0 && first != '\uFEFF') reader.unread(first);
        return reader;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer is unavailable");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(OCR_JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static String normalizeText(String value) {
        if (value == null) return "";
        return value
                .replace("\u0000", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("(?m)[ \\u00A0]+$", "")
                .replaceAll("\\n{4,}", "\n\n\n")
                .strip();
    }

    private static String normalizeCell(String value) {
        if (value == null) return "";
        return value
                .replace("\u0000", "")
                .replace("\r\n", "\\n")
                .replace("\r", "\\n")
                .replace("\n", "\\n")
                .replace("\t", " ")
                .trim();
    }

    private static int visibleCharacterCount(String value) {
        if (value == null) return 0;
        String visible = value.replaceAll("\\s+", "");
        return visible.codePointCount(0, visible.length());
    }

    private static void ensureNotEmpty(String text) {
        if (text == null || text.isBlank()) {
            throw invalidDocument("文档没有可读取的正文内容");
        }
    }

    private static void ensureWithinLimit(String value, int maxCharacters) {
        if (value.codePointCount(0, value.length()) > maxCharacters) {
            throw tooLong();
        }
    }

    private static boolean hasCharacterCodingCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof CharacterCodingException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }

    private static FeatureValidationException invalidDocument(String message) {
        return new FeatureValidationException("document", message);
    }

    private static FeatureValidationException tooLong() {
        return new FeatureValidationException(
                "document",
                "文档抽取并规范化后的正文超过 15 万字符，请拆分文档后重试"
        );
    }

    private static final class LimitedTextBuilder {
        private final int maximum;
        private final StringBuilder value = new StringBuilder();
        private int characters;

        private LimitedTextBuilder(int maximum) {
            this.maximum = maximum;
        }

        private void appendSection(String title, String content) {
            if (!value.isEmpty()) append("\n\n");
            append("## " + title + "\n\n");
            append(content);
        }

        private void append(String text) {
            if (text == null || text.isEmpty()) return;
            characters += text.codePointCount(0, text.length());
            if (characters > maximum) throw tooLong();
            value.append(text);
        }

        private String value() {
            return value.toString();
        }
    }

    private record RenderedSlides(List<ModelAsset> images, List<Integer> slideNumbers) {
    }
}
