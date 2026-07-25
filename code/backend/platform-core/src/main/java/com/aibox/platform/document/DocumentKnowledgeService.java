package com.aibox.platform.document;

import com.aibox.feature.spi.DocumentQuestionRequest;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.common.JsonCodec;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFPictureShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hssf.usermodel.HSSFChart;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressBase;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentKnowledgeService {

    private static final int PARSER_VERSION = 1;
    private static final int MAX_CANDIDATES = 40;
    private static final int MAX_VISUALS_PER_DOCUMENT = 40;
    private static final int MAX_NON_EMPTY_ROWS = 200_000;
    private static final int MAX_CHART_POINTS_PER_SERIES = 200;
    private static final String NO_VISUAL_CONTENT = "NO_VISUAL_CONTENT";
    private static final Set<String> PDF_VISUAL_OPERATORS = Set.of(
            "m", "l", "c", "v", "y", "h", "re",
            "S", "s", "f", "F", "f*", "B", "B*", "b", "b*",
            "Do", "sh"
    );
    private static final Pattern CELL_ROW_PATTERN =
            Pattern.compile("\\$?[A-Za-z]{1,3}\\$?(\\d+)");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".txt", ".md", ".csv", ".json"
    );

    private final AssetService assetService;
    private final JdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;
    private final Clock clock;
    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private final Map<UUID, Directory> luceneCache = new ConcurrentHashMap<>();
    private final Map<String, Object> indexLocks = new ConcurrentHashMap<>();

    public DocumentKnowledgeService(
            AssetService assetService,
            JdbcTemplate jdbcTemplate,
            JsonCodec jsonCodec,
            Clock clock
    ) {
        this.assetService = assetService;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
        this.clock = clock;
    }

    public PreparedSearch prepareAndSearch(
            DocumentQuestionRequest request,
            VisionAnalyzer visionAnalyzer
    ) {
        List<IndexReference> indexes = new ArrayList<>();
        for (UUID assetId : request.inputAssetIds()) {
            ModelAsset asset = assetService.readForModel(assetId);
            indexes.add(ensureIndex(
                    request, asset, request.visionDeploymentCode(), visionAnalyzer
            ));
        }
        List<ChunkCandidate> candidates = search(indexes, retrievalQuery(request));
        Map<String, Object> metadata = Map.of(
                "retrievalMode", "LUCENE_BM25",
                "indexedDocumentCount", indexes.size(),
                "candidateCount", candidates.size()
        );
        return new PreparedSearch(candidates, metadata);
    }

    private static String retrievalQuery(DocumentQuestionRequest request) {
        StringBuilder query = new StringBuilder();
        int start = Math.max(0, request.conversation().size() - 3);
        for (int index = start; index < request.conversation().size(); index++) {
            String question = request.conversation().get(index).question();
            if (!question.isBlank()) query.append(question).append('\n');
        }
        query.append(request.question());
        return query.toString().trim();
    }

    private IndexReference ensureIndex(
            DocumentQuestionRequest request,
            ModelAsset asset,
            String visionDeploymentCode,
            VisionAnalyzer visionAnalyzer
    ) {
        String hash = sha256(asset.content());
        String lockKey = asset.id() + ":" + visionDeploymentCode + ":" + PARSER_VERSION;
        Object lock = indexLocks.computeIfAbsent(lockKey, ignored -> new Object());
        try {
            synchronized (lock) {
                IndexReference existing = findIndex(asset.id(), visionDeploymentCode);
                if (existing != null && "READY".equals(existing.status())
                        && hash.equals(existing.contentHash())) {
                    return existing;
                }
                UUID indexId = existing == null ? UUID.randomUUID() : existing.id();
                Instant now = clock.instant();
                jdbcTemplate.update("""
                        insert into document_index (
                            id, tenant_id, user_id, asset_id, vision_deployment_code,
                            parser_version, status, content_hash, statistics_json,
                            error_code, error_message, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, 'PROCESSING', ?, '{}'::jsonb,
                                  null, null, ?, ?)
                        on conflict (asset_id, vision_deployment_code, parser_version) do update
                        set status = 'PROCESSING',
                            content_hash = excluded.content_hash,
                            statistics_json = '{}'::jsonb,
                            error_code = null,
                            error_message = null,
                            updated_at = excluded.updated_at
                        """,
                        indexId, request.tenantId(), request.userId(),
                        asset.id(), visionDeploymentCode, PARSER_VERSION, hash,
                        Timestamp.from(now), Timestamp.from(now)
                );
                IndexReference processing = findIndex(asset.id(), visionDeploymentCode);
                if (processing == null) {
                    throw new ModelProviderException(
                            "DOCUMENT_INDEX_FAILED", "Document index could not be created", true
                    );
                }
                indexId = processing.id();
                try {
                    ParseResult parsed = parse(asset, request, visionAnalyzer);
                    if (parsed.chunks().isEmpty()) {
                        throw new ModelProviderException(
                                "DOCUMENT_TEXT_EMPTY",
                                "No readable content was found in " + asset.fileName(),
                                false
                        );
                    }
                    jdbcTemplate.update("""
                            delete from document_chunk
                            where document_index_id = ?
                            """, indexId
                    );
                    Instant createdAt = clock.instant();
                    for (int ordinal = 0; ordinal < parsed.chunks().size(); ordinal++) {
                        ParsedChunk chunk = parsed.chunks().get(ordinal);
                        jdbcTemplate.update("""
                                insert into document_chunk (
                                    id, document_index_id, asset_id, ordinal,
                                    text_content, locator_json, search_metadata_json, created_at
                                ) values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
                                """,
                                UUID.randomUUID(), indexId, asset.id(), ordinal,
                                chunk.text(), jsonCodec.write(chunk.locator()),
                                jsonCodec.write(chunk.metadata()), Timestamp.from(createdAt)
                        );
                    }
                    Map<String, Object> statistics = new LinkedHashMap<>();
                    statistics.put("chunkCount", parsed.chunks().size());
                    statistics.put("visualCallCount", parsed.visualCallCount());
                    statistics.put("extension", extension(asset.fileName()));
                    jdbcTemplate.update("""
                            update document_index
                            set status = 'READY',
                                statistics_json = cast(? as jsonb),
                                error_code = null,
                                error_message = null,
                                updated_at = ?
                            where id = ?
                            """, jsonCodec.write(statistics), Timestamp.from(clock.instant()), indexId);
                    invalidate(indexId);
                    return new IndexReference(
                            indexId, asset.id(), asset.fileName(), "READY", hash
                    );
                } catch (ModelProviderException exception) {
                    markFailed(indexId, exception.code(), exception.getMessage());
                    throw exception;
                } catch (RuntimeException | IOException exception) {
                    markFailed(indexId, "DOCUMENT_PARSE_FAILED", "Document could not be parsed");
                    throw new ModelProviderException(
                            "DOCUMENT_PARSE_FAILED",
                            "Document could not be parsed: " + asset.fileName(),
                            false,
                            exception
                    );
                }
            }
        } finally {
            indexLocks.remove(lockKey, lock);
        }
    }

    private ParseResult parse(
            ModelAsset asset,
            DocumentQuestionRequest request,
            VisionAnalyzer visionAnalyzer
    ) throws IOException {
        String extension = extension(asset.fileName());
        if (".pdf".equals(extension)) {
            return parsePdf(asset, request, visionAnalyzer);
        }
        if (".docx".equals(extension)) {
            return parseDocx(asset, request, visionAnalyzer);
        }
        if (".doc".equals(extension)) {
            return parseDoc(asset);
        }
        if (".xlsx".equals(extension) || ".xls".equals(extension)) {
            return parseWorkbook(asset);
        }
        if (".pptx".equals(extension)) {
            return parsePptx(asset, request, visionAnalyzer);
        }
        if (".ppt".equals(extension)) {
            return parsePpt(asset, request, visionAnalyzer);
        }
        if (TEXT_EXTENSIONS.contains(extension)) {
            return new ParseResult(parseText(asset), 0);
        }
        throw new ModelProviderException(
                "DOCUMENT_TYPE_UNSUPPORTED",
                "Document type is not supported: " + asset.fileName(),
                false
        );
    }

    private ParseResult parsePdf(
            ModelAsset asset,
            DocumentQuestionRequest request,
            VisionAnalyzer visionAnalyzer
    ) throws IOException {
        List<ParsedChunk> chunks = new ArrayList<>();
        int visualCalls = 0;
        try (PDDocument document = Loader.loadPDF(asset.content())) {
            if (document.isEncrypted()) {
                throw new ModelProviderException(
                        "DOCUMENT_PASSWORD_PROTECTED",
                        "Password-protected PDF files are not supported",
                        false
                );
            }
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                int pageNumber = pageIndex + 1;
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String text = normalizeText(stripper.getText(document));
                Map<String, Object> locator = Map.of(
                        "type", "PDF_PAGE",
                        "pageNumber", pageNumber
                );
                if (!text.isBlank()) {
                    chunks.addAll(splitText(text, locator, Map.of("pageNumber", pageNumber)));
                }
                PDPage page = document.getPage(pageIndex);
                boolean needsVision = text.length() < 40 || containsVisualContent(page);
                if (needsVision && visualCalls < MAX_VISUALS_PER_DOCUMENT) {
                    BufferedImage image = renderer.renderImageWithDPI(pageIndex, 160, ImageType.RGB);
                    String visualText = analyzeVisual(
                            request,
                            visionAnalyzer,
                            asset.fileName() + "-page-" + pageNumber + ".png",
                            toPng(image),
                            "识别这一页扫描文档中的全部文字、表格和图表。"
                                    + "图表需说明标题、图例、趋势和清晰可见的数据标签。"
                                    + "不要猜测无法看清的数值。若没有需要补充的视觉内容，仅返回 "
                                    + NO_VISUAL_CONTENT + "。"
                    );
                    visualCalls++;
                    if (meaningfulVisualText(visualText)) {
                        Map<String, Object> metadata = Map.of(
                                "pageNumber", pageNumber,
                                "source", "VISION"
                        );
                        chunks.addAll(splitText(visualText, locator, metadata));
                    }
                }
            }
        }
        return new ParseResult(List.copyOf(chunks), visualCalls);
    }

    private ParseResult parseDocx(
            ModelAsset asset,
            DocumentQuestionRequest request,
            VisionAnalyzer visionAnalyzer
    ) throws IOException {
        List<ParsedChunk> chunks = new ArrayList<>();
        int visualCalls = 0;
        Set<String> imageHashes = new LinkedHashSet<>();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(asset.content()))) {
            String heading = "";
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            for (int index = 0; index < paragraphs.size(); index++) {
                XWPFParagraph paragraph = paragraphs.get(index);
                String text = normalizeText(paragraph.getText());
                if (!text.isBlank() && isHeading(document, paragraph)) {
                    heading = text;
                }
                if (!text.isBlank()) {
                    Map<String, Object> locator = new LinkedHashMap<>();
                    locator.put("type", "WORD_PARAGRAPH");
                    locator.put("paragraphStart", index + 1);
                    locator.put("paragraphEnd", index + 1);
                    if (!heading.isBlank()) locator.put("heading", heading);
                    chunks.add(new ParsedChunk(text, Map.copyOf(locator), Map.of()));
                }
                for (XWPFRun run : paragraph.getRuns()) {
                    for (XWPFPicture picture : run.getEmbeddedPictures()) {
                        if (visualCalls >= MAX_VISUALS_PER_DOCUMENT) break;
                        byte[] bytes = picture.getPictureData().getData();
                        if (!imageHashes.add(sha256(bytes))) continue;
                        String visual = analyzeVisual(
                                request,
                                visionAnalyzer,
                                picture.getPictureData().getFileName(),
                                bytes,
                                "识别这张Word内嵌图片中的文字和图表。"
                                        + "图表需说明标题、图例、趋势和清晰数据标签；"
                                        + "不要猜测无法看清的数值。若没有有效内容，仅返回 "
                                        + NO_VISUAL_CONTENT + "。"
                        );
                        visualCalls++;
                        if (meaningfulVisualText(visual)) {
                            Map<String, Object> locator = new LinkedHashMap<>();
                            locator.put("type", "WORD_PARAGRAPH");
                            locator.put("paragraphStart", index + 1);
                            locator.put("paragraphEnd", index + 1);
                            if (!heading.isBlank()) locator.put("heading", heading);
                            locator.put("visual", true);
                            chunks.add(new ParsedChunk(
                                    visual, Map.copyOf(locator), Map.of("source", "VISION")
                            ));
                        }
                    }
                }
            }
        }
        return new ParseResult(List.copyOf(chunks), visualCalls);
    }

    private static boolean isHeading(
            XWPFDocument document,
            XWPFParagraph paragraph
    ) {
        String styleId = paragraph.getStyle();
        if (styleId == null || styleId.isBlank()) return false;
        String normalizedId = styleId.toLowerCase(Locale.ROOT);
        if (normalizedId.contains("heading") || normalizedId.contains("标题")) {
            return true;
        }
        if (document.getStyles() == null) return false;
        XWPFStyle style = document.getStyles().getStyle(styleId);
        String name = style == null ? null : style.getName();
        if (name == null) return false;
        String normalizedName = name.toLowerCase(Locale.ROOT);
        return normalizedName.contains("heading") || normalizedName.contains("标题");
    }

    private ParseResult parseDoc(ModelAsset asset) throws IOException {
        List<ParsedChunk> chunks = new ArrayList<>();
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(asset.content()))) {
            Range range = document.getRange();
            for (int index = 0; index < range.numParagraphs(); index++) {
                Paragraph paragraph = range.getParagraph(index);
                String text = normalizeText(paragraph.text());
                if (text.isBlank()) continue;
                chunks.add(new ParsedChunk(
                        text,
                        Map.of(
                                "type", "WORD_PARAGRAPH",
                                "paragraphStart", index + 1,
                                "paragraphEnd", index + 1
                        ),
                        Map.of()
                ));
            }
        }
        return new ParseResult(List.copyOf(chunks), 0);
    }

    private ParseResult parseWorkbook(ModelAsset asset) throws IOException {
        List<ParsedChunk> chunks = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        int nonEmptyRows = 0;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(asset.content()))) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                List<String> pending = new ArrayList<>();
                int startRow = -1;
                int endRow = -1;
                for (Row row : sheet) {
                    String rowText = formatRow(row, formatter, evaluator);
                    if (rowText.isBlank()) continue;
                    nonEmptyRows++;
                    if (nonEmptyRows > MAX_NON_EMPTY_ROWS) {
                        throw new ModelProviderException(
                                "DOCUMENT_ROW_LIMIT_EXCEEDED",
                                "Spreadsheet contains too many non-empty rows",
                                false
                        );
                    }
                    if (startRow < 0) startRow = row.getRowNum() + 1;
                    endRow = row.getRowNum() + 1;
                    pending.add("第" + endRow + "行：" + rowText);
                    if (pending.size() >= 25) {
                        chunks.add(workbookChunk(sheet.getSheetName(), startRow, endRow, pending));
                        pending.clear();
                        startRow = -1;
                    }
                }
                if (!pending.isEmpty()) {
                    chunks.add(workbookChunk(sheet.getSheetName(), startRow, endRow, pending));
                }
                chunks.addAll(workbookChartChunks(sheet, formatter, evaluator));
            }
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Spreadsheet could not be parsed", exception);
        }
        return new ParseResult(List.copyOf(chunks), 0);
    }

    private static List<ParsedChunk> workbookChartChunks(
            Sheet sheet,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        if (sheet instanceof XSSFSheet xssfSheet) {
            return xssfChartChunks(xssfSheet);
        }
        if (sheet instanceof HSSFSheet hssfSheet) {
            return hssfChartChunks(hssfSheet, formatter, evaluator);
        }
        return List.of();
    }

    private static List<ParsedChunk> xssfChartChunks(XSSFSheet sheet) {
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing == null || drawing.getCharts().isEmpty()) return List.of();
        List<ParsedChunk> chunks = new ArrayList<>();
        List<XSSFChart> charts = drawing.getCharts();
        for (int chartIndex = 0; chartIndex < charts.size(); chartIndex++) {
            XSSFChart chart = charts.get(chartIndex);
            StringBuilder text = new StringBuilder();
            appendLine(text, "Excel图表：" + normalizedChartTitle(chart.getTitleText()));
            int startRow = Integer.MAX_VALUE;
            int endRow = 0;
            int seriesNumber = 0;
            for (XDDFChartData chartData : chart.getChartSeries()) {
                appendLine(text, "图表类型：" + chartType(chartData));
                for (int index = 0; index < chartData.getSeriesCount(); index++) {
                    XDDFChartData.Series series = chartData.getSeries(index);
                    XDDFDataSource<?> categories = series.getCategoryData();
                    XDDFNumericalDataSource<? extends Number> values =
                            series.getValuesData();
                    RangeRows rows = mergeRows(
                            sourceRows(categories),
                            sourceRows(values)
                    );
                    if (rows != null) {
                        startRow = Math.min(startRow, rows.start());
                        endRow = Math.max(endRow, rows.end());
                    }
                    appendLine(text, chartSeriesText(
                            ++seriesNumber,
                            categories,
                            values
                    ));
                }
            }
            if (seriesNumber == 0) continue;
            if (startRow == Integer.MAX_VALUE) {
                startRow = Math.max(1, sheet.getFirstRowNum() + 1);
                endRow = Math.max(startRow, sheet.getLastRowNum() + 1);
            }
            Map<String, Object> locator = new LinkedHashMap<>();
            locator.put("type", "EXCEL_ROWS");
            locator.put("sheetName", sheet.getSheetName());
            locator.put("startRow", startRow);
            locator.put("endRow", endRow);
            locator.put("chartIndex", chartIndex + 1);
            chunks.addAll(splitText(
                    text.toString(),
                    Map.copyOf(locator),
                    Map.of("source", "CHART_DATA")
            ));
        }
        return List.copyOf(chunks);
    }

    private static List<ParsedChunk> hssfChartChunks(
            HSSFSheet sheet,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        HSSFChart[] charts = HSSFChart.getSheetCharts(sheet);
        if (charts.length == 0) return List.of();
        List<ParsedChunk> chunks = new ArrayList<>();
        for (int chartIndex = 0; chartIndex < charts.length; chartIndex++) {
            HSSFChart chart = charts[chartIndex];
            StringBuilder text = new StringBuilder();
            appendLine(text, "Excel图表：" + normalizedChartTitle(chart.getChartTitle()));
            appendLine(text, "图表类型：" + chart.getType());
            int startRow = Integer.MAX_VALUE;
            int endRow = 0;
            HSSFChart.HSSFSeries[] seriesValues = chart.getSeries();
            for (int index = 0; index < seriesValues.length; index++) {
                HSSFChart.HSSFSeries series = seriesValues[index];
                CellRangeAddressBase categories = series.getCategoryLabelsCellRange();
                CellRangeAddressBase values = series.getValuesCellRange();
                RangeRows rows = mergeRows(sourceRows(categories), sourceRows(values));
                if (rows != null) {
                    startRow = Math.min(startRow, rows.start());
                    endRow = Math.max(endRow, rows.end());
                }
                appendLine(text, hssfChartSeriesText(
                        index + 1,
                        series.getSeriesTitle(),
                        sheet,
                        categories,
                        values,
                        formatter,
                        evaluator
                ));
            }
            if (seriesValues.length == 0) continue;
            if (startRow == Integer.MAX_VALUE) {
                startRow = Math.max(1, sheet.getFirstRowNum() + 1);
                endRow = Math.max(startRow, sheet.getLastRowNum() + 1);
            }
            Map<String, Object> locator = new LinkedHashMap<>();
            locator.put("type", "EXCEL_ROWS");
            locator.put("sheetName", sheet.getSheetName());
            locator.put("startRow", startRow);
            locator.put("endRow", endRow);
            locator.put("chartIndex", chartIndex + 1);
            chunks.addAll(splitText(
                    text.toString(),
                    Map.copyOf(locator),
                    Map.of("source", "CHART_DATA")
            ));
        }
        return List.copyOf(chunks);
    }

    private static String chartSeriesText(
            int seriesNumber,
            XDDFDataSource<?> categories,
            XDDFNumericalDataSource<? extends Number> values
    ) {
        StringBuilder text = new StringBuilder("系列").append(seriesNumber);
        appendRange(text, "分类范围", categories == null
                ? null : categories.getDataRangeReference());
        appendRange(text, "数值范围", values == null
                ? null : values.getDataRangeReference());
        int categoryCount = categories == null ? 0 : categories.getPointCount();
        int valueCount = values == null ? 0 : values.getPointCount();
        int pointCount = Math.max(categoryCount, valueCount);
        for (int index : sampledIndexes(pointCount)) {
            Object category = pointAt(categories, index);
            Object value = pointAt(values, index);
            text.append("\n点").append(index + 1)
                    .append("：分类=").append(chartValue(category))
                    .append("，值=").append(chartValue(value));
        }
        return text.toString();
    }

    private static String hssfChartSeriesText(
            int seriesNumber,
            String title,
            HSSFSheet sheet,
            CellRangeAddressBase categories,
            CellRangeAddressBase values,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        List<String> categoryValues = rangeValues(
                sheet, categories, formatter, evaluator
        );
        List<String> numericValues = rangeValues(
                sheet, values, formatter, evaluator
        );
        int pointCount = Math.max(categoryValues.size(), numericValues.size());
        StringBuilder text = new StringBuilder("系列").append(seriesNumber);
        if (title != null && !title.isBlank()) {
            text.append("（").append(normalizeText(title)).append("）");
        }
        for (int index : sampledIndexes(pointCount)) {
            text.append("\n点").append(index + 1)
                    .append("：分类=")
                    .append(index < categoryValues.size() ? categoryValues.get(index) : "")
                    .append("，值=")
                    .append(index < numericValues.size() ? numericValues.get(index) : "");
        }
        return text.toString();
    }

    private static List<String> rangeValues(
            Sheet sheet,
            CellRangeAddressBase range,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        if (range == null) return List.of();
        List<String> values = new ArrayList<>();
        for (int rowIndex = range.getFirstRow(); rowIndex <= range.getLastRow(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            for (int column = range.getFirstColumn();
                 column <= range.getLastColumn();
                 column++) {
                Cell cell = row == null ? null : row.getCell(column);
                values.add(cell == null
                        ? ""
                        : normalizeText(formatCell(cell, formatter, evaluator)));
            }
        }
        return List.copyOf(values);
    }

    private static Object pointAt(XDDFDataSource<?> source, int index) {
        if (source == null || index < 0 || index >= source.getPointCount()) return "";
        try {
            return source.getPointAt(index);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String chartValue(Object value) {
        if (value == null) return "";
        String normalized = normalizeText(value.toString());
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }

    private static List<Integer> sampledIndexes(int pointCount) {
        if (pointCount <= 0) return List.of();
        int sampleCount = Math.min(pointCount, MAX_CHART_POINTS_PER_SERIES);
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        if (sampleCount == 1) {
            indexes.add(0);
        } else {
            for (int index = 0; index < sampleCount; index++) {
                indexes.add((int) Math.round(
                        (double) index * (pointCount - 1) / (sampleCount - 1)
                ));
            }
        }
        return List.copyOf(indexes);
    }

    private static void appendRange(
            StringBuilder target,
            String label,
            String range
    ) {
        if (range == null || range.isBlank()) return;
        target.append("，").append(label).append("=").append(range);
    }

    private static String normalizedChartTitle(Object value) {
        String normalized = normalizeText(value == null ? "" : value.toString());
        return normalized.isBlank() ? "未命名图表" : normalized;
    }

    private static String chartType(XDDFChartData data) {
        return data.getClass().getSimpleName()
                .replace("XDDF", "")
                .replace("ChartData", "");
    }

    private static RangeRows sourceRows(XDDFDataSource<?> source) {
        return source == null ? null : sourceRows(source.getDataRangeReference());
    }

    private static RangeRows sourceRows(String range) {
        if (range == null || range.isBlank()) return null;
        Matcher matcher = CELL_ROW_PATTERN.matcher(range);
        int start = Integer.MAX_VALUE;
        int end = 0;
        while (matcher.find()) {
            int row = Integer.parseInt(matcher.group(1));
            start = Math.min(start, row);
            end = Math.max(end, row);
        }
        return start == Integer.MAX_VALUE ? null : new RangeRows(start, end);
    }

    private static RangeRows sourceRows(CellRangeAddressBase range) {
        return range == null
                ? null
                : new RangeRows(range.getFirstRow() + 1, range.getLastRow() + 1);
    }

    private static RangeRows mergeRows(RangeRows left, RangeRows right) {
        if (left == null) return right;
        if (right == null) return left;
        return new RangeRows(
                Math.min(left.start(), right.start()),
                Math.max(left.end(), right.end())
        );
    }

    private ParseResult parsePptx(
            ModelAsset asset,
            DocumentQuestionRequest request,
            VisionAnalyzer visionAnalyzer
    ) throws IOException {
        List<ParsedChunk> chunks = new ArrayList<>();
        int visualCalls = 0;
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(asset.content()))) {
            List<XSLFSlide> slides = slideShow.getSlides();
            for (int index = 0; index < slides.size(); index++) {
                XSLFSlide slide = slides.get(index);
                int slideNumber = index + 1;
                StringBuilder text = new StringBuilder();
                boolean hasVisual = false;
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        appendLine(text, textShape.getText());
                    }
                    if (shape instanceof XSLFPictureShape || shape instanceof XSLFGraphicFrame) {
                        hasVisual = true;
                    }
                }
                Map<String, Object> locator = Map.of(
                        "type", "PPT_SLIDE",
                        "slideNumber", slideNumber
                );
                if (!text.isEmpty()) {
                    chunks.addAll(splitText(text.toString(), locator, Map.of()));
                }
                if (hasVisual && visualCalls < MAX_VISUALS_PER_DOCUMENT) {
                    String visual = analyzeVisual(
                            request,
                            visionAnalyzer,
                            asset.fileName() + "-slide-" + slideNumber + ".png",
                            renderSlide(slide, slideShow.getPageSize()),
                            "识别这页PPT中的图片、表格和图表。"
                                    + "图表需说明标题、图例、趋势和清晰数据标签；"
                                    + "不要猜测无法看清的数值。若没有需要补充的视觉内容，仅返回 "
                                    + NO_VISUAL_CONTENT + "。"
                    );
                    visualCalls++;
                    if (meaningfulVisualText(visual)) {
                        chunks.add(new ParsedChunk(
                                visual, locator, Map.of("source", "VISION")
                        ));
                    }
                }
            }
        }
        return new ParseResult(List.copyOf(chunks), visualCalls);
    }

    private ParseResult parsePpt(
            ModelAsset asset,
            DocumentQuestionRequest request,
            VisionAnalyzer visionAnalyzer
    ) throws IOException {
        List<ParsedChunk> chunks = new ArrayList<>();
        int visualCalls = 0;
        try (HSLFSlideShow slideShow = new HSLFSlideShow(new ByteArrayInputStream(asset.content()))) {
            List<HSLFSlide> slides = slideShow.getSlides();
            for (int index = 0; index < slides.size(); index++) {
                HSLFSlide slide = slides.get(index);
                int slideNumber = index + 1;
                StringBuilder text = new StringBuilder();
                boolean hasVisual = false;
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        appendLine(text, textShape.getText());
                    }
                    if (shape instanceof HSLFPictureShape) hasVisual = true;
                }
                Map<String, Object> locator = Map.of(
                        "type", "PPT_SLIDE",
                        "slideNumber", slideNumber
                );
                if (!text.isEmpty()) {
                    chunks.addAll(splitText(text.toString(), locator, Map.of()));
                }
                if (hasVisual && visualCalls < MAX_VISUALS_PER_DOCUMENT) {
                    String visual = analyzeVisual(
                            request,
                            visionAnalyzer,
                            asset.fileName() + "-slide-" + slideNumber + ".png",
                            renderSlide(slide, slideShow.getPageSize()),
                            "识别这页PPT中的图片、表格和图表。"
                                    + "不要猜测无法看清的数值。若没有需要补充的视觉内容，仅返回 "
                                    + NO_VISUAL_CONTENT + "。"
                    );
                    visualCalls++;
                    if (meaningfulVisualText(visual)) {
                        chunks.add(new ParsedChunk(
                                visual, locator, Map.of("source", "VISION")
                        ));
                    }
                }
            }
        }
        return new ParseResult(List.copyOf(chunks), visualCalls);
    }

    private List<ParsedChunk> parseText(ModelAsset asset) {
        String text = decodeText(asset.content());
        String[] lines = text.split("\\R", -1);
        List<ParsedChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int startLine = 1;
        for (int index = 0; index < lines.length; index++) {
            if (current.length() > 0) current.append('\n');
            current.append(lines[index]);
            boolean flush = current.length() >= 3_000 || index - startLine + 1 >= 39;
            if (flush || index == lines.length - 1) {
                String value = normalizeText(current.toString());
                if (!value.isBlank()) {
                    chunks.add(new ParsedChunk(
                            value,
                            Map.of(
                                    "type", "TEXT_LINES",
                                    "startLine", startLine,
                                    "endLine", index + 1
                            ),
                            Map.of()
                    ));
                }
                current.setLength(0);
                startLine = index + 2;
            }
        }
        return List.copyOf(chunks);
    }

    private List<ChunkCandidate> search(List<IndexReference> indexes, String question) {
        List<IndexReader> readers = new ArrayList<>();
        Map<String, ChunkRow> chunksById = new HashMap<>();
        try {
            for (IndexReference index : indexes) {
                List<ChunkRow> chunks = loadChunks(index);
                for (ChunkRow chunk : chunks) chunksById.put(chunk.id().toString(), chunk);
                Directory directory = luceneCache.computeIfAbsent(index.id(), ignored -> buildIndex(chunks));
                readers.add(DirectoryReader.open(directory));
            }
            if (readers.isEmpty()) return List.of();
            try (MultiReader reader = new MultiReader(readers.toArray(IndexReader[]::new), false)) {
                QueryParser parser = new QueryParser("content", analyzer);
                TopDocs topDocs = new IndexSearcher(reader).search(
                        parser.parse(QueryParser.escape(question)),
                        MAX_CANDIDATES
                );
                List<ChunkCandidate> result = new ArrayList<>();
                IndexSearcher searcher = new IndexSearcher(reader);
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    Document document = searcher.storedFields().document(scoreDoc.doc);
                    ChunkRow chunk = chunksById.get(document.get("chunkId"));
                    if (chunk == null) continue;
                    result.add(candidate(chunk, scoreDoc.score));
                }
                if (!result.isEmpty()) return List.copyOf(result);
            }
        } catch (Exception ignored) {
            // Deterministic fallback below still allows the selected GPT model to rerank.
        } finally {
            for (IndexReader reader : readers) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return chunksById.values().stream()
                .sorted(Comparator.comparing(ChunkRow::fileName).thenComparingInt(ChunkRow::ordinal))
                .limit(MAX_CANDIDATES)
                .map(chunk -> candidate(chunk, 0))
                .toList();
    }

    private Directory buildIndex(List<ChunkRow> chunks) {
        Directory directory = new ByteBuffersDirectory();
        try (IndexWriter writer = new IndexWriter(
                directory,
                new IndexWriterConfig(analyzer)
        )) {
            for (ChunkRow chunk : chunks) {
                Document document = new Document();
                document.add(new StringField(
                        "chunkId", chunk.id().toString(), Field.Store.YES
                ));
                document.add(new TextField(
                        "content", chunk.text(), Field.Store.NO
                ));
                writer.addDocument(document);
            }
            writer.commit();
            return directory;
        } catch (IOException exception) {
            try {
                directory.close();
            } catch (IOException ignored) {
            }
            throw new IllegalStateException("Lucene index could not be built", exception);
        }
    }

    private List<ChunkRow> loadChunks(IndexReference index) {
        return jdbcTemplate.query("""
                select chunk.id, chunk.asset_id, chunk.ordinal, chunk.text_content,
                       chunk.locator_json::text, asset.original_name
                from document_chunk chunk
                join asset on asset.id = chunk.asset_id
                where chunk.document_index_id = ?
                order by chunk.ordinal
                """, (resultSet, rowNumber) -> new ChunkRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("asset_id", UUID.class),
                resultSet.getString("original_name"),
                resultSet.getInt("ordinal"),
                resultSet.getString("text_content"),
                jsonCodec.readMap(resultSet.getString("locator_json"))
        ), index.id());
    }

    private IndexReference findIndex(UUID assetId, String visionDeploymentCode) {
        List<IndexReference> values = jdbcTemplate.query("""
                select document.id, document.asset_id, asset.original_name,
                       document.status, document.content_hash
                from document_index document
                join asset on asset.id = document.asset_id
                where document.asset_id = ?
                  and document.vision_deployment_code = ?
                  and document.parser_version = ?
                """, (resultSet, rowNumber) -> new IndexReference(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("asset_id", UUID.class),
                resultSet.getString("original_name"),
                resultSet.getString("status"),
                resultSet.getString("content_hash")
        ), assetId, visionDeploymentCode, PARSER_VERSION);
        return values.isEmpty() ? null : values.get(0);
    }

    private void markFailed(UUID indexId, String code, String message) {
        jdbcTemplate.update("""
                update document_index
                set status = 'FAILED',
                    error_code = ?,
                    error_message = ?,
                    updated_at = ?
                where id = ?
                """, code, abbreviate(message), Timestamp.from(clock.instant()), indexId);
        invalidate(indexId);
    }

    private void invalidate(UUID indexId) {
        Directory directory = luceneCache.remove(indexId);
        if (directory == null) return;
        try {
            directory.close();
        } catch (IOException ignored) {
        }
    }

    private static String analyzeVisual(
            DocumentQuestionRequest request,
            VisionAnalyzer analyzer,
            String fileName,
            byte[] content,
            String prompt
    ) {
        return normalizeText(analyzer.analyze(new VisualRequest(
                request.tenantId(),
                request.runId(),
                request.visionModelAlias(),
                request.visionDeploymentCode(),
                fileName,
                "image/png",
                content,
                prompt
        )));
    }

    private static byte[] renderSlide(
            XSLFSlide slide,
            Dimension pageSize
    ) throws IOException {
        return render(pageSize, slide::draw);
    }

    private static byte[] renderSlide(
            HSLFSlide slide,
            Dimension pageSize
    ) throws IOException {
        return render(pageSize, slide::draw);
    }

    private static byte[] render(
            Dimension pageSize,
            SlidePainter painter
    ) throws IOException {
        int width = 1_600;
        int height = Math.max(1, (int) Math.round(width * pageSize.getHeight() / pageSize.getWidth()));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.scale(width / pageSize.getWidth(), height / pageSize.getHeight());
            painter.draw(graphics);
        } finally {
            graphics.dispose();
        }
        return toPng(image);
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG image writer is unavailable");
        }
        return output.toByteArray();
    }

    private static boolean containsVisualContent(PDPage page) {
        try {
            for (var name : page.getResources().getXObjectNames()) {
                PDXObject object = page.getResources().getXObject(name);
                if (object instanceof PDImageXObject) return true;
            }
        } catch (IOException ignored) {
        }
        PDFStreamParser parser = null;
        try {
            parser = new PDFStreamParser(page);
            for (Object token : parser.parse()) {
                if (token instanceof Operator operator
                        && PDF_VISUAL_OPERATORS.contains(operator.getName())) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (parser != null) {
                try {
                    parser.close();
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    private static ParsedChunk workbookChunk(
            String sheetName,
            int startRow,
            int endRow,
            List<String> lines
    ) {
        return new ParsedChunk(
                String.join("\n", lines),
                Map.of(
                        "type", "EXCEL_ROWS",
                        "sheetName", sheetName,
                        "startRow", startRow,
                        "endRow", endRow
                ),
                Map.of("sheetName", sheetName)
        );
    }

    private static String formatRow(
            Row row,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        List<String> values = new ArrayList<>();
        short last = row.getLastCellNum();
        if (last < 0) return "";
        for (int index = 0; index < last; index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) continue;
            String value = normalizeText(formatCell(cell, formatter, evaluator));
            if (!value.isBlank()) {
                values.add(columnLabel(index) + "=" + value);
            }
        }
        return String.join(" | ", values);
    }

    private static String formatCell(
            Cell cell,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException ignored) {
            return formatter.formatCellValue(cell);
        }
    }

    private static String columnLabel(int index) {
        StringBuilder result = new StringBuilder();
        int value = index + 1;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            result.append((char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.reverse().toString();
    }

    private static List<ParsedChunk> splitText(
            String value,
            Map<String, Object> locator,
            Map<String, Object> metadata
    ) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) return List.of();
        List<ParsedChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + 3_500);
            if (end < normalized.length()) {
                int breakAt = normalized.lastIndexOf('\n', end);
                if (breakAt > start + 1_500) end = breakAt;
            }
            chunks.add(new ParsedChunk(
                    normalized.substring(start, end).trim(),
                    locator,
                    metadata
            ));
            start = end;
        }
        return List.copyOf(chunks);
    }

    private static ChunkCandidate candidate(ChunkRow chunk, double score) {
        return new ChunkCandidate(
                chunk.id(), chunk.assetId(), chunk.fileName(),
                chunk.text(), chunk.locator(), score
        );
    }

    private static String decodeText(byte[] bytes) {
        List<Charset> charsets = List.of(
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_16,
                Charset.forName("GB18030")
        );
        for (Charset charset : charsets) {
            try {
                return charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException ignored) {
            }
        }
        throw new ModelProviderException(
                "DOCUMENT_ENCODING_UNSUPPORTED",
                "Text document encoding is not supported",
                false
        );
    }

    private static String normalizeText(String value) {
        if (value == null) return "";
        return value
                .replace('\u0000', ' ')
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static boolean meaningfulVisualText(String value) {
        return value != null
                && !value.isBlank()
                && !value.toUpperCase(Locale.ROOT).contains(NO_VISUAL_CONTENT);
    }

    private static String extension(String name) {
        int index = name == null ? -1 : name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) return "Document processing failed";
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    private static void appendLine(StringBuilder target, String value) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) return;
        if (!target.isEmpty()) target.append('\n');
        target.append(normalized);
    }

    @PreDestroy
    void close() {
        luceneCache.values().forEach(directory -> {
            try {
                directory.close();
            } catch (IOException ignored) {
            }
        });
        luceneCache.clear();
        analyzer.close();
    }

    @FunctionalInterface
    public interface VisionAnalyzer {
        String analyze(VisualRequest request);
    }

    @FunctionalInterface
    private interface SlidePainter {
        void draw(Graphics2D graphics);
    }

    public record VisualRequest(
            UUID tenantId,
            UUID runId,
            String modelAlias,
            String deploymentCode,
            String fileName,
            String mediaType,
            byte[] content,
            String prompt
    ) {
        public VisualRequest {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record ChunkCandidate(
            UUID chunkId,
            UUID assetId,
            String fileName,
            String text,
            Map<String, Object> locator,
            double score
    ) {
        public ChunkCandidate {
            locator = locator == null ? Map.of() : Map.copyOf(locator);
        }
    }

    public record PreparedSearch(
            List<ChunkCandidate> candidates,
            Map<String, Object> metadata
    ) {
        public PreparedSearch {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    private record IndexReference(
            UUID id,
            UUID assetId,
            String fileName,
            String status,
            String contentHash
    ) {
    }

    private record ParsedChunk(
            String text,
            Map<String, Object> locator,
            Map<String, Object> metadata
    ) {
        private ParsedChunk {
            locator = locator == null ? Map.of() : Map.copyOf(locator);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    private record ParseResult(
            List<ParsedChunk> chunks,
            int visualCallCount
    ) {
    }

    private record RangeRows(int start, int end) {
    }

    private record ChunkRow(
            UUID id,
            UUID assetId,
            String fileName,
            int ordinal,
            String text,
            Map<String, Object> locator
    ) {
    }
}
