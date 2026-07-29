package com.aibox.platform.document;

import com.aibox.feature.spi.DocumentComparisonRequest;
import com.aibox.feature.spi.DocumentComparisonResponse;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
                            baseline ? "提前三十日通知" : "提前七日通知；新增审计权",
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
        AtomicBoolean streamed = new AtomicBoolean();
        List<TextGenerationRequest> generatedRequests = new ArrayList<>();
        DocumentComparisonEngine engine = new DocumentComparisonEngine(
                knowledge,
                (request, listener) -> {
                    generatedRequests.add(request);
                    streamed.set(true);
                    listener.onDelta("{");
                    calls.incrementAndGet();
                    String operation = request.metadata().get("operation").toString();
                    String json = switch (operation) {
                        case "DOCUMENT_COMPARE_PAIRWISE" -> """
                                {
                                  "comparability":{
                                    "status":"COMPARABLE",
                                    "reason":"两份合同均规定终止期限和审计义务",
                                    "sharedTopics":["终止条款","审计权"],
                                    "citationMarkers":["S1","S2"]
                                  },
                                  "summary":"终止期限发生变化",
                                  "differences":[{
                                    "topic":"终止条款",
                                    "baselineContent":"三十日",
                                    "comparisonContent":"七日",
                                    "impact":"准备时间缩短",
                                    "changeType":"modified",
                                    "citationMarkers":["S1","S2"]
                                  },{
                                    "topic":"审计权",
                                    "baselineContent":"未载明",
                                    "comparisonContent":"新增审计权",
                                    "impact":"增加配合审计义务",
                                    "changeType":"added",
                                    "citationMarkers":["S2"]
                                  }],
                                  "risks":[{
                                    "severity":"LOW",
                                    "title":"新增审计义务",
                                    "basis":"修订版新增审计权",
                                    "recommendation":"确认审计范围",
                                    "affectedAssetIds":["%s"],
                                    "citationMarkers":["S2"]
                                  }]
                                }
                                """.formatted(comparisonId);
                        case "DOCUMENT_COMPARE_AGGREGATE",
                                "DOCUMENT_COMPARE_AGGREGATE_REPAIR" -> """
                                {
                                  "detectedMode":"contract",
                                  "comparability":{
                                    "status":"COMPARABLE",
                                    "reason":"两份合同主题和用途一致，可以进行完整比较",
                                    "sharedTopics":["终止条款","审计权"],
                                    "citationMarkers":["S1","S2"]
                                  },
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
                                    "affectedAssetIds":["%s"],
                                    "citationMarkers":["S2"]
                                  }],
                                  "warnings":[]
                                }
                                """.formatted(
                                baselineId,
                                comparisonId,
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
        assertThat(response.comparability().status()).isEqualTo("COMPARABLE");
        assertThat(response.pairwiseComparisons()).singleElement()
                .satisfies(pair -> {
                    assertThat(pair.comparability().status()).isEqualTo("COMPARABLE");
                    assertThat(pair.differences()).hasSize(2);
                });
        assertThat(response.crossDocumentConclusion().findings()).singleElement();
        assertThat(response.risks()).hasSize(2)
                .extracting("severity")
                .containsExactlyInAnyOrder("LOW", "MEDIUM");
        assertThat(response.risks())
                .extracting("affectedAssetIds")
                .containsOnly(List.of(comparisonId));
        assertThat(response.citations()).extracting("marker")
                .containsExactly("S1", "S2");
        assertThat(response.reportMarkdown())
                .contains("基准文档逐份差异", "风险清单");
        assertThat(calls).hasValue(2);
        assertThat(streamed).isTrue();
        assertThat(generatedRequests).hasSize(2).allSatisfy(generated -> {
            assertThat(generated.systemPrompt()).contains("Simplified Chinese");
            assertThat(generated.userPrompt()).contains("Simplified Chinese");
        });
        verify(knowledge, times(2)).prepareAndSearch(any(), any());
    }

    @Test
    void returnsNotComparableWithoutCallingAggregateModel() {
        UUID baselineId = UUID.randomUUID();
        UUID comparisonId = UUID.randomUUID();
        DocumentKnowledgeService knowledge = knowledgeWithOneChunkPerDocument(
                baselineId,
                comparisonId,
                "软件采购合同",
                "员工休假与考勤制度"
        );
        AtomicInteger calls = new AtomicInteger();
        DocumentComparisonEngine engine = new DocumentComparisonEngine(
                knowledge,
                (request, listener) -> {
                    calls.incrementAndGet();
                    String operation = request.metadata().get("operation").toString();
                    if (!"DOCUMENT_COMPARE_PAIRWISE".equals(operation)) {
                        throw new AssertionError(
                                "Aggregate model must not be called: " + operation
                        );
                    }
                    return new TextGenerationResponse(
                            """
                            {
                              "comparability":{
                                "status":"NOT_COMPARABLE",
                                "reason":"采购合同与员工制度的主题和用途不同",
                                "sharedTopics":[],
                                "citationMarkers":["S1","S2"]
                              },
                              "summary":"两份文档不适合进行实质差异对比",
                              "differences":[],
                              "risks":[]
                            }
                            """,
                            "relay",
                            "gpt-5.6-sol",
                            operation,
                            50,
                            30
                    );
                },
                request -> {
                    throw new AssertionError("Vision should not be used in this test");
                }
        );

        DocumentComparisonResponse response = engine.compare(request(
                baselineId,
                comparisonId
        ));

        assertThat(response.comparability().status())
                .isEqualTo("NOT_COMPARABLE");
        assertThat(response.pairwiseComparisons()).singleElement()
                .satisfies(pair -> {
                    assertThat(pair.comparability().status())
                            .isEqualTo("NOT_COMPARABLE");
                    assertThat(pair.differences()).isEmpty();
                });
        assertThat(response.crossDocumentConclusion().findings()).isEmpty();
        assertThat(response.risks()).isEmpty();
        assertThat(response.citations()).extracting("marker")
                .containsExactly("S1", "S2");
        assertThat(response.reportMarkdown()).contains("不可比");
        assertThat(calls).hasValue(1);
    }

    @Test
    void returnsIdenticalWithoutCallingAggregateModel() {
        UUID baselineId = UUID.randomUUID();
        UUID comparisonId = UUID.randomUUID();
        DocumentKnowledgeService knowledge = knowledgeWithOneChunkPerDocument(
                baselineId,
                comparisonId,
                "本合同自签署之日起生效",
                "本合同自签署之日起生效"
        );
        AtomicInteger calls = new AtomicInteger();
        DocumentComparisonEngine engine = new DocumentComparisonEngine(
                knowledge,
                (request, listener) -> {
                    calls.incrementAndGet();
                    String operation = request.metadata().get("operation").toString();
                    if (!"DOCUMENT_COMPARE_PAIRWISE".equals(operation)) {
                        throw new AssertionError(
                                "Aggregate model must not be called: " + operation
                        );
                    }
                    return new TextGenerationResponse(
                            """
                            {
                              "comparability":{
                                "status":"IDENTICAL",
                                "reason":"两份文档在已提取证据范围内实质一致",
                                "sharedTopics":["全部实质内容"],
                                "citationMarkers":["S1","S2"]
                              },
                              "summary":"两份文档实质一致",
                              "differences":[],
                              "risks":[]
                            }
                            """,
                            "relay",
                            "gpt-5.6-sol",
                            operation,
                            50,
                            30
                    );
                },
                request -> {
                    throw new AssertionError("Vision should not be used in this test");
                }
        );

        DocumentComparisonResponse response = engine.compare(request(
                baselineId,
                comparisonId
        ));

        assertThat(response.comparability().status()).isEqualTo("IDENTICAL");
        assertThat(response.pairwiseComparisons()).singleElement()
                .satisfies(pair -> {
                    assertThat(pair.comparability().status())
                            .isEqualTo("IDENTICAL");
                    assertThat(pair.differences()).isEmpty();
                });
        assertThat(response.crossDocumentConclusion().findings()).isEmpty();
        assertThat(response.risks()).isEmpty();
        assertThat(response.reportMarkdown()).contains("完全相同");
        assertThat(calls).hasValue(1);
    }

    @Test
    void mixedPairStatusesProducePartiallyComparableAggregate() {
        UUID baselineId = UUID.randomUUID();
        UUID comparableId = UUID.randomUUID();
        UUID unrelatedId = UUID.randomUUID();
        DocumentKnowledgeService knowledge = mock(DocumentKnowledgeService.class);
        when(knowledge.prepareAndSearch(any(), any())).thenAnswer(invocation -> {
            com.aibox.feature.spi.DocumentQuestionRequest search =
                    invocation.getArgument(0);
            UUID assetId = search.inputAssetIds().get(0);
            String fileName;
            String text;
            if (baselineId.equals(assetId)) {
                fileName = "baseline.docx";
                text = "合同终止需提前三十日通知";
            } else if (comparableId.equals(assetId)) {
                fileName = "revision.docx";
                text = "合同终止需提前七日通知";
            } else {
                fileName = "employee-policy.pdf";
                text = "员工休假与考勤制度";
            }
            return new DocumentKnowledgeService.PreparedSearch(
                    List.of(new DocumentKnowledgeService.ChunkCandidate(
                            UUID.randomUUID(),
                            assetId,
                            fileName,
                            text,
                            Map.of(
                                    "type", "WORD_PARAGRAPH",
                                    "paragraphStart", 1,
                                    "paragraphEnd", 1
                            ),
                            1.0
                    )),
                    Map.of()
            );
        });
        AtomicInteger pairCalls = new AtomicInteger();
        AtomicInteger aggregateCalls = new AtomicInteger();
        DocumentComparisonEngine engine = new DocumentComparisonEngine(
                knowledge,
                (request, listener) -> {
                    String operation = request.metadata().get("operation").toString();
                    String json;
                    if ("DOCUMENT_COMPARE_PAIRWISE".equals(operation)) {
                        int pairNumber = pairCalls.incrementAndGet();
                        if (pairNumber == 1) {
                            json = """
                                    {
                                      "comparability":{
                                        "status":"PARTIALLY_COMPARABLE",
                                        "reason":"两份合同可在终止通知主题上进行比较",
                                        "sharedTopics":["终止通知"],
                                        "citationMarkers":["S1","S2"]
                                      },
                                      "summary":"终止通知期限不同",
                                      "differences":[{
                                        "topic":"终止通知",
                                        "baselineContent":"提前三十日",
                                        "comparisonContent":"提前七日",
                                        "impact":"退出准备时间缩短",
                                        "changeType":"modified",
                                        "citationMarkers":["S1","S2"]
                                      }],
                                      "risks":[]
                                    }
                                    """;
                        } else {
                            json = """
                                    {
                                      "comparability":{
                                        "status":"NOT_COMPARABLE",
                                        "reason":"合同与员工制度主题和用途不同",
                                        "sharedTopics":[],
                                        "citationMarkers":["S1","S3"]
                                      },
                                      "summary":"不适合进行实质差异对比",
                                      "differences":[],
                                      "risks":[]
                                    }
                                    """;
                        }
                    } else if ("DOCUMENT_COMPARE_AGGREGATE".equals(operation)) {
                        aggregateCalls.incrementAndGet();
                        json = """
                                {
                                  "detectedMode":"contract",
                                  "comparability":{
                                    "status":"PARTIALLY_COMPARABLE",
                                    "reason":"仅基准合同与修订合同存在可比较的终止通知主题",
                                    "sharedTopics":["终止通知"],
                                    "citationMarkers":["S1","S2","S3"]
                                  },
                                  "summary":"三份文档仅部分可比",
                                  "crossDocumentConclusion":{
                                    "summary":"仅对共同的终止通知主题形成结论",
                                    "findings":[{
                                      "topic":"终止通知",
                                      "documentStatements":[
                                        {
                                          "assetId":"%s",
                                          "fileName":"baseline.docx",
                                          "content":"提前三十日",
                                          "citationMarkers":["S1"]
                                        },
                                        {
                                          "assetId":"%s",
                                          "fileName":"revision.docx",
                                          "content":"提前七日",
                                          "citationMarkers":["S2"]
                                        }
                                      ],
                                      "commonality":"均约定终止通知",
                                      "difference":"通知期限不同",
                                      "impact":"影响退出准备",
                                      "citationMarkers":["S1","S2"]
                                    }]
                                  },
                                  "risks":[],
                                  "warnings":[]
                                }
                                """.formatted(baselineId, comparableId);
                    } else {
                        throw new AssertionError("Unexpected operation: " + operation);
                    }
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
        DocumentComparisonRequest comparisonRequest = new DocumentComparisonRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                baselineId,
                List.of(comparableId, unrelatedId),
                "auto",
                "",
                "text.document-compare",
                "selected-text",
                "vision.document-compare",
                "selected-vision",
                8_000,
                Map.of()
        );

        DocumentComparisonResponse response = engine.compare(comparisonRequest);

        assertThat(response.comparability().status())
                .isEqualTo("PARTIALLY_COMPARABLE");
        assertThat(response.comparability().sharedTopics())
                .containsExactly("终止通知");
        assertThat(response.pairwiseComparisons())
                .extracting(pair -> pair.comparability().status())
                .containsExactly("PARTIALLY_COMPARABLE", "NOT_COMPARABLE");
        assertThat(response.crossDocumentConclusion().findings()).hasSize(1);
        assertThat(pairCalls).hasValue(2);
        assertThat(aggregateCalls).hasValue(1);
    }

    private static DocumentKnowledgeService knowledgeWithOneChunkPerDocument(
            UUID baselineId,
            UUID comparisonId,
            String baselineText,
            String comparisonText
    ) {
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
                            baseline ? baselineText : comparisonText,
                            baseline
                                    ? Map.of(
                                            "type", "WORD_PARAGRAPH",
                                            "paragraphStart", 1,
                                            "paragraphEnd", 1
                                    )
                                    : Map.of(
                                            "type", "PDF_PAGE",
                                            "pageNumber", 1
                                    ),
                            1.0
                    )),
                    Map.of()
            );
        });
        return knowledge;
    }

    private static DocumentComparisonRequest request(
            UUID baselineId,
            UUID comparisonId
    ) {
        return new DocumentComparisonRequest(
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
    }
}
