package com.aibox.features.document.compare;

import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.DocumentCitation;
import com.aibox.feature.spi.DocumentComparisonRequest;
import com.aibox.feature.spi.DocumentComparisonResponse;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.features.support.RecordingFeatureOutputEmitter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentCompareFeatureHandlerTest {

    @Test
    void createsStructuredArtifactAndSecondaryExports() {
        UUID baselineId = UUID.randomUUID();
        UUID comparisonId = UUID.randomUUID();
        AtomicReference<DocumentComparisonRequest> captured = new AtomicReference<>();
        DocumentCompareFeatureHandler handler =
                new DocumentCompareFeatureHandler();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new AssertionError("The comparison seam should be used");
            }

            @Override
            public DocumentComparisonResponse compareDocuments(
                    DocumentComparisonRequest request
            ) {
                captured.set(request);
                return response(baselineId, comparisonId);
            }
        };
        ArtifactReference baseArtifact = new ArtifactReference(
                UUID.randomUUID(),
                2,
                "document_comparison",
                "application/vnd.yuanzuo.document-comparison+json",
                Map.of(),
                Map.of()
        );
        FeatureExecutionContext context = context(
                baselineId,
                List.of(comparisonId),
                baseArtifact
        );
        RecordingFeatureOutputEmitter emitter = new RecordingFeatureOutputEmitter();

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway, emitter);

        assertEquals(baselineId, captured.get().baselineAssetId());
        assertEquals(List.of(comparisonId), captured.get().comparisonAssetIds());
        assertEquals("contract", captured.get().mode());
        assertEquals(
                "codex2api-gpt-5-6-sol-text",
                captured.get().textDeploymentCode()
        );
        assertEquals(
                "codex2api-gpt-5-6-sol-vision",
                captured.get().visionDeploymentCode()
        );

        assertEquals(1, result.artifacts().size());
        var artifact = result.artifacts().get(0);
        assertEquals("document_comparison", artifact.kind());
        assertEquals("document_comparison", artifact.content().get("format"));
        assertEquals(
                "COMPARABLE",
                ((Map<?, ?>) artifact.content().get("comparability")).get("status")
        );
        assertEquals("存在一项重要期限变化", artifact.content().get("summary"));
        assertEquals(
                List.of("excel", "annotatedBaseline"),
                ((List<?>) artifact.content().get("exportOptions")).stream()
                        .map(Map.class::cast)
                        .map(item -> item.get("type"))
                        .toList()
        );
        assertTrue(artifact.outputAssets().isEmpty());
        assertEquals(2, artifact.metadata().get("availableExportCount"));
        assertEquals(
                baseArtifact.id().toString(),
                artifact.metadata().get("basedOnArtifactId")
        );
        assertEquals("text", emitter.format());
        assertTrue(emitter.content().contains("对比完成"));
    }

    @Test
    void enforcesConditionalDocumentCountsAndUniqueFiles() {
        DocumentCompareFeatureHandler handler =
                new DocumentCompareFeatureHandler();
        UUID only = UUID.randomUUID();
        FeatureExecutionContext noBaselineWithOne = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentCompareFeatureHandler.FEATURE_CODE,
                1,
                Map.of(
                        "comparisonDocuments", List.of(only.toString()),
                        "comparisonMode", "auto"
                ),
                List.of(only),
                List.of(new InputAssetReference(
                        only,
                        "one.pdf",
                        "application/pdf",
                        1024
                )),
                models(),
                null,
                null
        );
        UUID baseline = UUID.randomUUID();
        FeatureExecutionContext duplicateBaseline = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentCompareFeatureHandler.FEATURE_CODE,
                1,
                Map.of(
                        "baselineDocument", baseline.toString(),
                        "comparisonDocuments", List.of(baseline.toString()),
                        "comparisonMode", "version"
                ),
                List.of(baseline),
                List.of(new InputAssetReference(
                        baseline,
                        "same.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        1024
                )),
                models(),
                null,
                null
        );

        assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(noBaselineWithOne)
        );
        assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(duplicateBaseline)
        );
    }

    private static FeatureExecutionContext context(
            UUID baselineId,
            List<UUID> comparisonIds,
            ArtifactReference baseArtifact
    ) {
        UUID comparisonId = comparisonIds.get(0);
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentCompareFeatureHandler.FEATURE_CODE,
                1,
                Map.of(
                        "baselineDocument", baselineId.toString(),
                        "comparisonDocuments",
                        comparisonIds.stream().map(UUID::toString).toList(),
                        "comparisonMode", "contract",
                        "instructions", "重点检查终止条款"
                ),
                List.of(baselineId, comparisonId),
                List.of(
                        new InputAssetReference(
                                baselineId,
                                "baseline.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                1024
                        ),
                        new InputAssetReference(
                                comparisonId,
                                "comparison.pdf",
                                "application/pdf",
                                2048
                        )
                ),
                models(),
                null,
                baseArtifact
        );
    }

    private static Map<String, String> models() {
        return Map.of(
                "TEXT_GENERATION", "codex2api-gpt-5-6-sol-text",
                "VISION", "codex2api-gpt-5-6-sol-vision"
        );
    }

    private static DocumentComparisonResponse response(
            UUID baselineId,
            UUID comparisonId
    ) {
        List<String> markers = List.of("S1", "S2");
        DocumentComparisonResponse.Difference difference =
                new DocumentComparisonResponse.Difference(
                        "终止条款",
                        "提前三十日通知",
                        "提前七日通知",
                        "终止准备时间缩短",
                        "modified",
                        markers
                );
        DocumentComparisonResponse.PairwiseComparison pair =
                new DocumentComparisonResponse.PairwiseComparison(
                        comparisonId,
                        "comparison.pdf",
                        "终止期限发生变化",
                        new DocumentComparisonResponse.Comparability(
                                "COMPARABLE",
                                "两份合同均规定终止期限",
                                List.of("终止期限"),
                                markers
                        ),
                        List.of(difference)
                );
        DocumentComparisonResponse.ConsensusFinding finding =
                new DocumentComparisonResponse.ConsensusFinding(
                        "终止期限",
                        List.of(
                                new DocumentComparisonResponse.DocumentStatement(
                                        baselineId,
                                        "baseline.docx",
                                        "三十日",
                                        List.of("S1")
                                ),
                                new DocumentComparisonResponse.DocumentStatement(
                                        comparisonId,
                                        "comparison.pdf",
                                        "七日",
                                        List.of("S2")
                                )
                        ),
                        "均允许提前终止",
                        "通知期限不同",
                        "影响退出安排",
                        markers
                );
        DocumentComparisonResponse.Risk risk =
                new DocumentComparisonResponse.Risk(
                        "MEDIUM",
                        "通知期限缩短",
                        "对比文档只要求提前七日通知",
                        "确认退出准备时间是否足够",
                        List.of(baselineId, comparisonId),
                        markers
                );
        return new DocumentComparisonResponse(
                "contract",
                "存在一项重要期限变化",
                new DocumentComparisonResponse.Comparability(
                        "COMPARABLE",
                        "两份合同主题和用途一致",
                        List.of("终止期限"),
                        markers
                ),
                "# 对比结论\n存在一项重要期限变化",
                List.of(pair),
                new DocumentComparisonResponse.CrossDocumentConclusion(
                        "终止规则总体一致但期限不同",
                        List.of(finding)
                ),
                List.of(risk),
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
                                Map.of("type", "PDF_PAGE", "pageNumber", 2)
                        )
                ),
                List.of(),
                Map.of("modelCallCount", 2)
        );
    }
}
