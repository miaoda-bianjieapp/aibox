package com.aibox.platform.document;

import com.aibox.feature.spi.DocumentCitation;
import com.aibox.feature.spi.DocumentComparisonExportRequest;
import com.aibox.feature.spi.DocumentComparisonExportResult;
import com.aibox.feature.spi.DocumentComparisonResponse;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.platform.asset.AssetService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
                "# 对比结论\n期限变化",
                List.of(new DocumentComparisonResponse.PairwiseComparison(
                        comparisonId,
                        "comparison.pdf",
                        "期限变化",
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
}
