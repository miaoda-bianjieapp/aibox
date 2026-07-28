package com.aibox.features.document.translate;

import com.aibox.feature.spi.DocumentTranslationPlan;
import com.aibox.feature.spi.DocumentTranslationProcessor;
import com.aibox.feature.spi.DocumentTranslationUnit;
import com.aibox.feature.spi.DocumentVisualPage;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.MultimodalTextGenerationRequest;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.TranslatedDocumentOutput;
import com.aibox.feature.spi.VisualPageTranslation;
import com.aibox.features.support.RecordingFeatureOutputEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTranslateFeatureHandlerTest {

    @Test
    void translatesTextAndScannedPagesWithSelectedDeployments() {
        UUID assetId = UUID.randomUUID();
        DocumentTranslationUnit first = new DocumentTranslationUnit(
                "body:p0:r0:x0",
                "项目已经完成",
                "body:p0"
        );
        DocumentTranslationUnit second = new DocumentTranslationUnit(
                "body:p1:r0:x0",
                "等待验收",
                "body:p1"
        );
        DocumentVisualPage page = visualPage(2);
        RecordingProcessor processor = new RecordingProcessor(new DocumentTranslationPlan(
                "pdf",
                2,
                10,
                List.of(first, second),
                List.of(page)
        ));
        DocumentTranslateFeatureHandler handler = handler(processor);
        RecordingGateway gateway = new RecordingGateway(
                """
                        {"translations":[
                          {"id":"body:p0:r0:x0","text":"The project is complete"},
                          {"id":"body:p1:r0:x0","text":"Acceptance is pending"}
                        ]}
                        """,
                """
                        {"pages":[{"pageNumber":2,"blocks":[
                          {"x":0.1,"y":0.2,"width":0.6,"height":0.15,"text":"Scanned translation"}
                        ]}]}
                        """
        );
        RecordingFeatureOutputEmitter emitter = new RecordingFeatureOutputEmitter();
        FeatureExecutionContext context = context(assetId, "report.pdf", "application/pdf");

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway, emitter);

        assertEquals("codex2api-gpt-5-6-sol-text", gateway.textRequest.deploymentCode());
        assertEquals("codex2api-gpt-5-6-sol-vision", gateway.visualRequest.deploymentCode());
        assertEquals(List.of(page.image()), gateway.visualRequest.inlineInputAssets());
        assertEquals("The project is complete", processor.textTranslations.get(first.id()));
        assertEquals("Acceptance is pending", processor.textTranslations.get(second.id()));
        assertEquals(2, processor.visualTranslations.get(0).pageNumber());
        assertEquals("Scanned translation",
                processor.visualTranslations.get(0).blocks().get(0).text());

        var artifact = result.artifacts().get(0);
        assertEquals("file", artifact.kind());
        assertEquals("application/pdf", artifact.mimeType());
        assertEquals("report-en.pdf", artifact.content().get("name"));
        assertEquals(1, artifact.outputAssets().size());
        assertEquals("assetId", artifact.outputAssets().get(0).contentField());
        assertEquals("report-en.pdf", artifact.outputAssets().get(0).fileName());
        assertEquals(2, artifact.metadata().get("modelInvocationCount"));
        assertEquals("译文文件已生成", emitter.content());
    }

    @Test
    void rejectsIncompleteModelTranslationWithoutRenderingOutput() {
        UUID assetId = UUID.randomUUID();
        RecordingProcessor processor = new RecordingProcessor(new DocumentTranslationPlan(
                "docx",
                0,
                8,
                List.of(
                        new DocumentTranslationUnit("one", "第一段", "body"),
                        new DocumentTranslationUnit("two", "第二段", "body")
                ),
                List.of()
        ));
        DocumentTranslateFeatureHandler handler = handler(processor);
        RecordingGateway gateway = new RecordingGateway(
                """
                        {"translations":[{"id":"one","text":"First paragraph"}]}
                        """,
                null
        );

        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> handler.execute(
                        context(
                                assetId,
                                "report.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        ),
                        gateway,
                        new RecordingFeatureOutputEmitter()
                )
        );

        assertEquals("MODEL_INVALID_RESPONSE", exception.code());
        assertFalse(exception.retryable());
        assertFalse(processor.rendered);
    }

    @Test
    void rejectsInvalidVisualCoordinatesWithoutRenderingOutput() {
        UUID assetId = UUID.randomUUID();
        RecordingProcessor processor = new RecordingProcessor(new DocumentTranslationPlan(
                "pdf",
                1,
                0,
                List.of(),
                List.of(visualPage(1))
        ));
        DocumentTranslateFeatureHandler handler = handler(processor);
        RecordingGateway gateway = new RecordingGateway(
                null,
                """
                        {"pages":[{"pageNumber":1,"blocks":[
                          {"x":0.9,"y":0.2,"width":0.3,"height":0.1,"text":"Translation"}
                        ]}]}
                        """
        );

        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> handler.execute(
                        context(assetId, "scan.pdf", "application/pdf"),
                        gateway,
                        new RecordingFeatureOutputEmitter()
                )
        );

        assertEquals("MODEL_INVALID_RESPONSE", exception.code());
        assertFalse(processor.rendered);
    }

    @Test
    void rejectsPlansThatWouldRequireMoreThanFiveModelCalls() {
        UUID assetId = UUID.randomUUID();
        List<DocumentVisualPage> pages = new ArrayList<>();
        for (int pageNumber = 1; pageNumber <= 20; pageNumber++) {
            pages.add(visualPage(pageNumber));
        }
        RecordingProcessor processor = new RecordingProcessor(new DocumentTranslationPlan(
                "pdf",
                20,
                6_001,
                List.of(new DocumentTranslationUnit(
                        "large",
                        "a".repeat(6_001),
                        "PDF 第 1 页"
                )),
                pages
        ));
        DocumentTranslateFeatureHandler handler = handler(processor);
        RecordingGateway gateway = new RecordingGateway(null, null);

        FeatureValidationException exception = assertThrows(
                FeatureValidationException.class,
                () -> handler.execute(
                        context(assetId, "mixed.pdf", "application/pdf"),
                        gateway,
                        new RecordingFeatureOutputEmitter()
                )
        );

        assertTrue(exception.getMessage().contains("超过 5 次"));
        assertEquals(0, gateway.invocationCount);
        assertFalse(processor.rendered);
    }

    @Test
    void validatesOneSupportedDocumentWithinFiftyMegabytes() {
        UUID assetId = UUID.randomUUID();
        DocumentTranslateFeatureHandler handler = handler(new RecordingProcessor(
                new DocumentTranslationPlan(
                        "docx",
                        0,
                        2,
                        List.of(new DocumentTranslationUnit("one", "正文", "body")),
                        List.of()
                )
        ));
        FeatureExecutionContext oversized = context(
                assetId,
                "large.pdf",
                "application/pdf",
                50L * 1024 * 1024 + 1
        );
        FeatureExecutionContext unsupported = context(
                assetId,
                "sheet.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );

        assertThrows(FeatureValidationException.class, () -> handler.validate(oversized));
        assertThrows(FeatureValidationException.class, () -> handler.validate(unsupported));
    }

    @Test
    void rejectsArabicAsADocumentTranslationTarget() {
        UUID assetId = UUID.randomUUID();
        DocumentTranslateFeatureHandler handler = handler(new RecordingProcessor(
                new DocumentTranslationPlan(
                        "docx",
                        0,
                        2,
                        List.of(new DocumentTranslationUnit("one", "正文", "body")),
                        List.of()
                )
        ));

        FeatureValidationException exception = assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(
                        assetId,
                        "report.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        1024,
                        "ar"
                ))
        );

        assertEquals("targetLanguage", exception.field());
    }

    private static DocumentTranslateFeatureHandler handler(
            DocumentTranslationProcessor processor
    ) {
        return new DocumentTranslateFeatureHandler(processor, new ObjectMapper());
    }

    private static DocumentVisualPage visualPage(int pageNumber) {
        return new DocumentVisualPage(
                pageNumber,
                new ModelAsset(
                        UUID.randomUUID(),
                        "page-" + pageNumber + ".jpg",
                        "image/jpeg",
                        new byte[]{1, 2, 3}
                )
        );
    }

    private static FeatureExecutionContext context(
            UUID assetId,
            String fileName,
            String mediaType
    ) {
        return context(assetId, fileName, mediaType, 1024);
    }

    private static FeatureExecutionContext context(
            UUID assetId,
            String fileName,
            String mediaType,
            long sizeBytes
    ) {
        return context(assetId, fileName, mediaType, sizeBytes, "en");
    }

    private static FeatureExecutionContext context(
            UUID assetId,
            String fileName,
            String mediaType,
            long sizeBytes,
            String targetLanguage
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentTranslateFeatureHandler.FEATURE_CODE,
                1,
                Map.of(
                        "document", assetId.toString(),
                        "targetLanguage", targetLanguage
                ),
                List.of(assetId),
                List.of(new InputAssetReference(assetId, fileName, mediaType, sizeBytes)),
                Map.of(
                        "TEXT_GENERATION", "codex2api-gpt-5-6-sol-text",
                        "VISION", "codex2api-gpt-5-6-sol-vision"
                ),
                null,
                null
        );
    }

    private static TextGenerationResponse response(String text) {
        return new TextGenerationResponse(
                text,
                "test-provider",
                "test-model",
                UUID.randomUUID().toString(),
                100,
                200
        );
    }

    private static final class RecordingProcessor implements DocumentTranslationProcessor {
        private final DocumentTranslationPlan plan;
        private Map<String, String> textTranslations;
        private List<VisualPageTranslation> visualTranslations;
        private boolean rendered;

        private RecordingProcessor(DocumentTranslationPlan plan) {
            this.plan = plan;
        }

        @Override
        public DocumentTranslationPlan prepare(
                UUID assetId,
                int maxCharacters,
                int maxScannedPdfPages
        ) {
            assertEquals(30_000, maxCharacters);
            assertEquals(20, maxScannedPdfPages);
            return plan;
        }

        @Override
        public TranslatedDocumentOutput render(
                UUID assetId,
                DocumentTranslationPlan plan,
                Map<String, String> textTranslations,
                List<VisualPageTranslation> visualTranslations
        ) {
            rendered = true;
            this.textTranslations = textTranslations;
            this.visualTranslations = visualTranslations;
            String mediaType = switch (plan.format()) {
                case "pdf" -> "application/pdf";
                case "doc" -> "application/msword";
                default ->
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            };
            return new TranslatedDocumentOutput(
                    mediaType,
                    "translated".getBytes(StandardCharsets.UTF_8)
            );
        }
    }

    private static final class RecordingGateway implements ModelGateway {
        private final String textResponse;
        private final String visualResponse;
        private TextGenerationRequest textRequest;
        private MultimodalTextGenerationRequest visualRequest;
        private int invocationCount;

        private RecordingGateway(String textResponse, String visualResponse) {
            this.textResponse = textResponse;
            this.visualResponse = visualResponse;
        }

        @Override
        public TextGenerationResponse generateText(TextGenerationRequest request) {
            invocationCount++;
            textRequest = request;
            return response(textResponse);
        }

        @Override
        public TextGenerationResponse generateMultimodalText(
                MultimodalTextGenerationRequest request
        ) {
            invocationCount++;
            visualRequest = request;
            return response(visualResponse);
        }
    }
}
