package com.aibox.platform.document;

import com.aibox.feature.spi.DocumentCitation;
import com.aibox.feature.spi.DocumentComparisonExportRequest;
import com.aibox.feature.spi.DocumentComparisonExportResult;
import com.aibox.feature.spi.DocumentComparisonResponse;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.platform.asset.AssetService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformDocumentComparisonExporterTest {

    @Test
    void createsExcelAndAnnotatedDocxExports() throws Exception {
        UUID baselineId = UUID.randomUUID();
        UUID comparisonId = UUID.randomUUID();
        byte[] source;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("提前三十日通知");
            document.write(output);
            source = output.toByteArray();
        }
        AssetService assetService = mock(AssetService.class);
        when(assetService.readForModel(baselineId)).thenReturn(new ModelAsset(
                baselineId,
                "baseline.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                source
        ));
        PlatformDocumentComparisonExporter exporter =
                new PlatformDocumentComparisonExporter(assetService);

        DocumentComparisonExportResult result = exporter.export(
                new DocumentComparisonExportRequest(
                        baselineId,
                        "baseline.docx",
                        "contract",
                        response(baselineId, comparisonId)
                )
        );

        assertThat(result.warnings()).isEmpty();
        assertThat(result.exports()).extracting("contentField")
                .containsExactly("excelAssetId", "annotatedBaselineAssetId");
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(result.exports().get(0).content())
        )) {
            assertWrappedBody(workbook.getSheet("对比概览"), 6, 1);
            assertThat(workbook.getSheet("对比概览").getRow(3).getCell(1)
                    .getStringCellValue()).isEqualTo("可比");
            assertWrappedBody(workbook.getSheet("差异-comparison.pdf"), 1, 2);
            assertWrappedBody(workbook.getSheet("来源"), 1, 3);
        }
        byte[] annotated = result.exports().get(1).content();
        try (XWPFDocument document = new XWPFDocument(
                new ByteArrayInputStream(annotated)
        )) {
            assertThat(document.getComments()).hasSize(1);
            assertThat(document.getComments()[0].getText())
                    .contains("终止条款");
            assertThat(
                    document.getParagraphs().get(0).getRuns().get(0)
                            .getTextHighlightColor().toString()
            ).isEqualTo("yellow");
        }
    }

    @Test
    void createsSeparatePdfAnnotationsForManyNotesOnTheSamePage() throws Exception {
        UUID baselineId = UUID.randomUUID();
        UUID comparisonId = UUID.randomUUID();
        byte[] source;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(output);
            source = output.toByteArray();
        }
        AssetService assetService = mock(AssetService.class);
        when(assetService.readForModel(baselineId)).thenReturn(new ModelAsset(
                baselineId,
                "baseline.pdf",
                "application/pdf",
                source
        ));
        PlatformDocumentComparisonExporter exporter =
                new PlatformDocumentComparisonExporter(assetService);

        DocumentComparisonExportResult result = exporter.export(
                new DocumentComparisonExportRequest(
                        baselineId,
                        "baseline.pdf",
                        "contract",
                        pdfResponse(baselineId, comparisonId)
                )
        );

        byte[] annotated = result.exports().stream()
                .filter(item -> "annotatedBaselineAssetId".equals(item.contentField()))
                .findFirst()
                .orElseThrow()
                .content();
        try (PDDocument document = Loader.loadPDF(annotated)) {
            List<PDAnnotationText> annotations = document.getPage(0).getAnnotations()
                    .stream()
                    .filter(PDAnnotationText.class::isInstance)
                    .map(PDAnnotationText.class::cast)
                    .toList();
            assertThat(annotations).hasSize(30);
            assertThat(annotations)
                    .extracting(PDAnnotationText::getContents)
                    .contains(
                            "差异：Clause 1\n影响：Clause 1 impact",
                            "差异：Clause 30\n影响：Clause 30 impact"
                    );
            assertThat(annotations)
                    .extracting(annotation -> annotation.getRectangle().getLowerLeftY())
                    .doesNotHaveDuplicates();
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            BufferedImage visibleNotes =
                    new PDFRenderer(document).renderImageWithDPI(1, 72);
            assertThat(nonWhitePixelCount(visibleNotes)).isGreaterThan(5_000);
        }
    }

    @Test
    void skipsAnnotatedBaselineWhenThereAreNoDifferenceNotes() {
        UUID baselineId = UUID.randomUUID();
        UUID comparisonId = UUID.randomUUID();
        AssetService assetService = mock(AssetService.class);
        PlatformDocumentComparisonExporter exporter =
                new PlatformDocumentComparisonExporter(assetService);

        DocumentComparisonExportResult result = exporter.export(
                new DocumentComparisonExportRequest(
                        baselineId,
                        "baseline.pdf",
                        "contract",
                        terminalResponse(baselineId, comparisonId)
                )
        );

        assertThat(result.warnings()).isEmpty();
        assertThat(result.exports()).extracting("contentField")
                .containsExactly("excelAssetId");
        verify(assetService, never()).readForModel(baselineId);
    }

    private static long nonWhitePixelCount(BufferedImage image) {
        long count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0x00ffffff) != 0x00ffffff) count++;
            }
        }
        return count;
    }

    private static void assertWrappedBody(
            Sheet sheet,
            int rowIndex,
            int columnIndex
    ) {
        assertThat(sheet.getRow(rowIndex).getCell(columnIndex).getCellStyle()
                .getWrapText()).isTrue();
    }

    private static DocumentComparisonResponse response(
            UUID baselineId,
            UUID comparisonId
    ) {
        List<String> markers = List.of("S1", "S2");
        DocumentComparisonResponse.Difference difference =
                new DocumentComparisonResponse.Difference(
                        "终止条款",
                        "三十日",
                        "七日",
                        "准备时间缩短",
                        "modified",
                        markers
                );
        return new DocumentComparisonResponse(
                "contract",
                "期限变化",
                comparable(markers),
                "# 对比结论\n期限变化",
                List.of(new DocumentComparisonResponse.PairwiseComparison(
                        comparisonId,
                        "comparison.pdf",
                        "期限变化",
                        comparable(markers),
                        List.of(difference)
                )),
                new DocumentComparisonResponse.CrossDocumentConclusion(
                        "期限不同",
                        List.of()
                ),
                List.of(),
                List.of(
                        new DocumentCitation(
                                "S1",
                                baselineId,
                                "baseline.docx",
                                "提前三十日通知",
                                Map.of(
                                        "type", "WORD_PARAGRAPH",
                                        "paragraphStart", 1,
                                        "paragraphEnd", 1
                                )
                        ),
                        new DocumentCitation(
                                "S2",
                                comparisonId,
                                "comparison.pdf",
                                "提前七日通知",
                                Map.of("type", "PDF_PAGE", "pageNumber", 1)
                        )
                ),
                List.of(),
                Map.of()
        );
    }

    private static DocumentComparisonResponse pdfResponse(
            UUID baselineId,
            UUID comparisonId
    ) {
        List<String> markers = List.of("S1", "S2");
        List<DocumentComparisonResponse.Difference> differences =
                IntStream.rangeClosed(1, 30)
                        .mapToObj(index -> pdfDifference("Clause " + index, markers))
                        .toList();
        return new DocumentComparisonResponse(
                "contract",
                "Thirty changes",
                comparable(markers),
                "# Comparison\nThirty changes",
                List.of(new DocumentComparisonResponse.PairwiseComparison(
                        comparisonId,
                        "comparison.txt",
                        "Thirty changes",
                        comparable(markers),
                        differences
                )),
                new DocumentComparisonResponse.CrossDocumentConclusion(
                        "Thirty changes",
                        List.of()
                ),
                List.of(),
                List.of(
                        new DocumentCitation(
                                "S1",
                                baselineId,
                                "baseline.pdf",
                                "Baseline clauses",
                                Map.of("type", "PDF_PAGE", "pageNumber", 1)
                        ),
                        new DocumentCitation(
                                "S2",
                                comparisonId,
                                "comparison.txt",
                                "Comparison clauses",
                                Map.of(
                                        "type", "TEXT_LINES",
                                        "startLine", 1,
                                        "endLine", 3
                                )
                        )
                ),
                List.of(),
                Map.of()
        );
    }

    private static DocumentComparisonResponse.Difference pdfDifference(
            String topic,
            List<String> markers
    ) {
        return new DocumentComparisonResponse.Difference(
                topic,
                "Baseline " + topic,
                "Comparison " + topic,
                topic + " impact",
                "modified",
                markers
        );
    }

    private static DocumentComparisonResponse terminalResponse(
            UUID baselineId,
            UUID comparisonId
    ) {
        List<String> markers = List.of("S1", "S2");
        DocumentComparisonResponse.Comparability comparability =
                new DocumentComparisonResponse.Comparability(
                        "IDENTICAL",
                        "两份文档在已提取证据范围内实质一致",
                        List.of("全部实质内容"),
                        markers
                );
        return new DocumentComparisonResponse(
                "contract",
                "两份文档实质一致",
                comparability,
                "# 对比结论\n两份文档实质一致",
                List.of(new DocumentComparisonResponse.PairwiseComparison(
                        comparisonId,
                        "comparison.pdf",
                        "两份文档实质一致",
                        comparability,
                        List.of()
                )),
                new DocumentComparisonResponse.CrossDocumentConclusion(
                        "两份文档实质一致",
                        List.of()
                ),
                List.of(),
                List.of(
                        new DocumentCitation(
                                "S1",
                                baselineId,
                                "baseline.pdf",
                                "本合同自签署之日起生效",
                                Map.of("type", "PDF_PAGE", "pageNumber", 1)
                        ),
                        new DocumentCitation(
                                "S2",
                                comparisonId,
                                "comparison.pdf",
                                "本合同自签署之日起生效",
                                Map.of("type", "PDF_PAGE", "pageNumber", 1)
                        )
                ),
                List.of(),
                Map.of()
        );
    }

    private static DocumentComparisonResponse.Comparability comparable(
            List<String> markers
    ) {
        return new DocumentComparisonResponse.Comparability(
                "COMPARABLE",
                "文档主题和用途一致",
                List.of("终止条款"),
                markers
        );
    }
}
