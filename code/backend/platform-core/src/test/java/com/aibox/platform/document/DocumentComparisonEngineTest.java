package com.aibox.platform.document;

import com.aibox.feature.spi.DocumentComparisonRequest;
import com.aibox.feature.spi.DocumentComparisonResponse;
import com.aibox.feature.spi.TextGenerationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentComparisonEngineTest {

    @Test
    void comparesBaselineAndComparisonWithGroundedMarkers() {
        UUID baselineId = UUID.randomUUID();
        UUID comparisonId = UUID.randomUUID();
        DocumentKnowledgeService knowledge = mock(DocumentKnowledgeService.class);
        when(knowledge.prepareAndSearch(any(), any())).thenAnswer(invocation -> {
            com.aibox.feature.spi.DocumentQuestionRequest request =
                    invocation.getArgument(0);
            UUID assetId = request.inputAssetIds().get(0);
            boolean baseline = baselineId.equals(assetId);
            return new DocumentKnowledgeService.PreparedSearch(
                    List.of(new DocumentKnowledgeService.ChunkCandidate(
                            UUID.randomUUID(),
                            assetId,
                            baseline ? "baseline.docx" : "comparison.pdf",
                            baseline ? "提前三十日通知" : "提前七日通知",
                            baseline
                                    ? Map.of(
                                            "type", "WORD_PARAGRAPH",
                                            "paragraphStart", 1,
                                            "paragraphEnd", 1
                                    )
                                    : Map.of(
                                            "type", "PDF_PAGE",
                                            "pageNumber", 2
                                    ),
                            1.0
                    )),
                    Map.of()
            );
        });
        AtomicInteger calls = new AtomicInteger();
        DocumentComparisonEngine engine = new DocumentComparisonEngine(
                knowledge,
                request -> {
                    calls.incrementAndGet();
                    String operation = request.metadata().get("operation").toString();
                    String json = switch (operation) {
                        case "DOCUMENT_COMPARE_PAIRWISE" -> """
                                {
                                  "summary":"终止期限发生变化",
                                  "differences":[{
                                    "topic":"终止条款",
                                    "baselineContent":"三十日",
                                    "comparisonContent":"七日",
                                    "impact":"准备时间缩短",
                                    "changeType":"modified",
                                    "citationMarkers":["S1","S2"]
                                  }],
                                  "risks":[]
                                }
                                """;
                        case "DOCUMENT_COMPARE_AGGREGATE" -> """
                                {
                                  "detectedMode":"contract",
                                  "summary":"存在终止期限变化",
                                  "crossDocumentConclusion":{
                                    "summary":"两份文档均允许终止但期限不同",
                                    "findings":[{
                                      "topic":"终止期限",
                                      "documentStatements":[
                                        {
                                          "assetId":"%s",
                                          "fileName":"baseline.docx",
                                          "content":"三十日",
                                          "citationMarkers":["S1"]
                                        },
                                        {
                                          "assetId":"%s",
                                          "fileName":"comparison.pdf",
                                          "content":"七日",
                                          "citationMarkers":["S2"]
                                        }
                                      ],
                                      "commonality":"均允许提前终止",
                                      "difference":"通知期限不同",
                                      "impact":"影响退出准备",
                                      "citationMarkers":["S1","S2"]
                                    }]
                                  },
                                  "risks":[{
                                    "severity":"MEDIUM",
                                    "title":"通知期限缩短",
                                    "basis":"七日短于三十日",
                                    "recommendation":"确认退出准备时间",
                                    "affectedAssetIds":["%s","%s"],
                                    "citationMarkers":["S1","S2"]
                                  }],
                                  "warnings":[]
                                }
                                """.formatted(
                                baselineId,
                                comparisonId,
                                baselineId,
                                comparisonId
                        );
                        default -> throw new AssertionError(
                                "Unexpected operation: " + operation
                        );
                    };
                    return new TextGenerationResponse(
                            json,
                            "relay",
                            "gpt-5.6-sol",
                            operation,
                            100,
                            50
                    );
                },
                request -> {
                    throw new AssertionError("Vision should not be used in this test");
                }
        );
        DocumentComparisonRequest request = new DocumentComparisonRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                baselineId,
                List.of(comparisonId),
                "auto",
                "",
                "text.document-compare",
                "selected-text",
                "vision.document-compare",
                "selected-vision",
                8_000,
                Map.of()
        );

        DocumentComparisonResponse response = engine.compare(request);

        assertThat(response.detectedMode()).isEqualTo("contract");
        assertThat(response.pairwiseComparisons()).singleElement()
                .satisfies(pair -> assertThat(pair.differences()).singleElement());
        assertThat(response.crossDocumentConclusion().findings()).singleElement();
        assertThat(response.risks()).singleElement()
                .satisfies(risk -> assertThat(risk.severity()).isEqualTo("MEDIUM"));
        assertThat(response.citations()).extracting("marker")
                .containsExactly("S1", "S2");
        assertThat(response.reportMarkdown())
                .contains("基准文档逐份差异", "风险清单");
        assertThat(calls).hasValue(2);
        verify(knowledge, times(2)).prepareAndSearch(any(), any());
    }
}
