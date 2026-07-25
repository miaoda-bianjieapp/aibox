package com.aibox.features.document.qa;

import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.DocumentCitation;
import com.aibox.feature.spi.DocumentQuestionRequest;
import com.aibox.feature.spi.DocumentQuestionResponse;
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

class DocumentQaFeatureHandlerTest {

    private final DocumentQaFeatureHandler handler = new DocumentQaFeatureHandler();

    @Test
    void rejectsAFileWhoseMediaTypeDoesNotMatchTheDocumentContract() {
        UUID assetId = UUID.randomUUID();
        FeatureExecutionContext context = context(
                assetId,
                new InputAssetReference(
                        assetId,
                        "report.pdf",
                        "image/png",
                        1024
                ),
                null
        );

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void usesBothSelectedDeploymentsAndCreatesANewDocumentAnswerArtifact() {
        UUID assetId = UUID.randomUUID();
        ArtifactReference baseArtifact = new ArtifactReference(
                UUID.randomUUID(),
                1,
                "document_answer",
                "application/vnd.yuanzuo.document-answer+json",
                Map.of(
                        "contextTurns",
                        List.of(Map.of(
                                "question", "上一问",
                                "answer", "上一答 [S1]"
                        ))
                ),
                Map.of()
        );
        FeatureExecutionContext context = context(
                assetId,
                new InputAssetReference(
                        assetId,
                        "report.pdf",
                        "application/pdf",
                        1024
                ),
                baseArtifact
        );
        AtomicReference<DocumentQuestionRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DocumentQuestionResponse answerDocumentQuestion(
                    DocumentQuestionRequest request,
                    com.aibox.feature.spi.TextGenerationListener listener
            ) {
                captured.set(request);
                listener.onDelta("答案");
                listener.onDelta(" [S1]");
                return new DocumentQuestionResponse(
                        "答案 [S1]",
                        List.of(new DocumentCitation(
                                "S1",
                                assetId,
                                "report.pdf",
                                "引用内容",
                                Map.of("type", "PDF_PAGE", "pageNumber", 2)
                        )),
                        List.of(),
                        "relay",
                        "gpt-5.6-sol",
                        "request-1",
                        10,
                        5,
                        Map.of("retrievalMode", "LUCENE_BM25")
                );
            }
        };
        RecordingFeatureOutputEmitter emitter = new RecordingFeatureOutputEmitter();

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway, emitter);

        DocumentQuestionRequest request = captured.get();
        assertEquals("codex2api-gpt-5-6-sol-text", request.textDeploymentCode());
        assertEquals("codex2api-gpt-5-6-sol-vision", request.visionDeploymentCode());
        assertEquals(List.of(assetId), request.inputAssetIds());
        assertEquals(1, request.conversation().size());
        assertEquals("上一问", request.conversation().get(0).question());
        assertEquals("答案 [S1]", emitter.content());
        assertEquals("markdown", emitter.format());

        var artifact = result.artifacts().get(0);
        assertEquals("document_answer", artifact.kind());
        assertEquals(
                "application/vnd.yuanzuo.document-answer+json",
                artifact.mimeType()
        );
        assertEquals("答案 [S1]", artifact.content().get("answerMarkdown"));
        assertEquals(2, ((List<?>) artifact.content().get("contextTurns")).size());
        assertEquals(1, ((List<?>) artifact.content().get("citations")).size());
        assertEquals(
                "codex2api-gpt-5-6-sol-text",
                artifact.metadata().get("textDeploymentCode")
        );
    }

    private static FeatureExecutionContext context(
            UUID assetId,
            InputAssetReference asset,
            ArtifactReference baseArtifact
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentQaFeatureHandler.FEATURE_CODE,
                1,
                Map.of(
                        "documents", List.of(assetId.toString()),
                        "question", "本次问题",
                        "strictGrounding", true
                ),
                List.of(assetId),
                List.of(asset),
                Map.of(
                        "TEXT_GENERATION", "codex2api-gpt-5-6-sol-text",
                        "VISION", "codex2api-gpt-5-6-sol-vision"
                ),
                null,
                baseArtifact
        );
    }
}
