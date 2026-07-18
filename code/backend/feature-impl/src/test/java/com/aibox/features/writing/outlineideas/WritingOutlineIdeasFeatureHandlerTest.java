package com.aibox.features.writing.outlineideas;

import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritingOutlineIdeasFeatureHandlerTest {

    private final WritingOutlineIdeasFeatureHandler handler = new WritingOutlineIdeasFeatureHandler();

    @Test
    void requiresTitleThesisAndSupportedStyle() {
        assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(Map.of("articleTitle", "标题", "style", "professional")))
        );
        assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(Map.of(
                        "articleTitle", "标题",
                        "thesis", "主旨",
                        "style", "dramatic"
                )))
        );
    }

    @Test
    void validatesUnicodeCharacterLimitsAndRejectsAttachments() {
        assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(Map.of(
                        "articleTitle", "题".repeat(201),
                        "thesis", "主旨",
                        "style", "professional"
                )))
        );
        FeatureExecutionContext withAsset = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingOutlineIdeasFeatureHandler.FEATURE_CODE,
                1,
                baseParameters(),
                List.of(UUID.randomUUID())
        );
        assertThrows(FeatureValidationException.class, () -> handler.validate(withAsset));
    }

    @Test
    void generatesPlainTextWithTheSelectedDeployment() {
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingOutlineIdeasFeatureHandler.FEATURE_CODE,
                1,
                baseParameters(),
                List.of(),
                Map.of("TEXT_GENERATION", "codex2api-gpt-5-6-text"),
                null,
                null
        );
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return response("选题方向\n1. 方向一\n2. 方向二\n3. 方向三");
        };

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertEquals("codex2api-gpt-5-6-text", captured.get().deploymentCode());
        assertTrue(captured.get().systemPrompt().contains("never a complete article"));
        assertTrue(captured.get().systemPrompt().contains("1.1"));
        assertTrue(captured.get().userPrompt().contains("人工智能产品设计"));
        assertEquals(0.7, captured.get().temperature());
        assertEquals("outline_text", result.artifacts().get(0).kind());
        assertEquals("text/plain", result.artifacts().get(0).mimeType());
        assertEquals("plain_text", result.artifacts().get(0).content().get("format"));
        assertEquals("model", result.artifacts().get(0).metadata().get("sourceType"));
    }

    @Test
    void regeneratesUsingThePreviousFrameworkAsAnAvoidanceReference() {
        ArtifactReference base = baseArtifact("旧框架内容");
        FeatureExecutionContext context = context(
                Map.of(
                        "articleTitle", "人工智能产品设计",
                        "thesis", "讨论如何设计有价值的 AI 产品",
                        "style", "creative",
                        "operation", "regenerate"
                ),
                base
        );
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return response("新的框架");
        };

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertTrue(captured.get().userPrompt().contains("substantially different"));
        assertTrue(captured.get().userPrompt().contains("旧框架内容"));
        assertEquals(0.85, captured.get().temperature());
        assertEquals(base.id().toString(), result.artifacts().get(0).metadata().get("basedOnArtifactId"));
    }

    @Test
    void savesAnEditedVersionWithoutCallingTheModel() {
        ArtifactReference base = baseArtifact("模型生成内容");
        FeatureExecutionContext context = context(
                Map.of(
                        "articleTitle", "人工智能产品设计",
                        "thesis", "讨论如何设计有价值的 AI 产品",
                        "style", "professional",
                        "operation", "save_edit",
                        "editedText", "人工调整后的框架\n1. 第一部分\n1.1 要点"
                ),
                base
        );
        AtomicBoolean called = new AtomicBoolean();
        ModelGateway gateway = request -> {
            called.set(true);
            return response("不应调用");
        };

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertFalse(called.get());
        assertEquals(
                "人工调整后的框架\n1. 第一部分\n1.1 要点",
                result.artifacts().get(0).content().get("text")
        );
        assertEquals("manual", result.artifacts().get(0).metadata().get("sourceType"));
        assertEquals("save_edit", result.artifacts().get(0).metadata().get("operation"));
    }

    @Test
    void requiresABaseArtifactForRegenerateAndSaveEdit() {
        Map<String, Object> regenerate = new java.util.HashMap<>(baseParameters());
        regenerate.put("operation", "regenerate");
        assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(regenerate))
        );

        Map<String, Object> saveEdit = new java.util.HashMap<>(baseParameters());
        saveEdit.put("operation", "save_edit");
        saveEdit.put("editedText", "修改内容");
        assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(saveEdit))
        );
    }

    @Test
    void rejectsEmptyAndOversizedEditedText() {
        ArtifactReference base = baseArtifact("模型生成内容");
        Map<String, Object> empty = new java.util.HashMap<>(baseParameters());
        empty.put("operation", "save_edit");
        empty.put("editedText", " ");
        assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(empty, base))
        );

        Map<String, Object> oversized = new java.util.HashMap<>(baseParameters());
        oversized.put("operation", "save_edit");
        oversized.put("editedText", "文".repeat(10_001));
        assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(oversized, base))
        );
    }

    @Test
    void treatsAnEmptyModelResponseAsRetryable() {
        FeatureExecutionContext context = context(baseParameters());
        ModelGateway gateway = request -> response(" ");

        handler.validate(context);
        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> handler.execute(context, gateway)
        );

        assertEquals("MODEL_EMPTY_RESPONSE", exception.code());
        assertTrue(exception.retryable());
    }

    private static Map<String, Object> baseParameters() {
        return Map.of(
                "articleTitle", "人工智能产品设计",
                "thesis", "讨论如何设计有价值的 AI 产品",
                "style", "professional"
        );
    }

    private static FeatureExecutionContext context(Map<String, Object> parameters) {
        return context(parameters, null);
    }

    private static FeatureExecutionContext context(
            Map<String, Object> parameters,
            ArtifactReference baseArtifact
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingOutlineIdeasFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                List.of(),
                Map.of("TEXT_GENERATION", "codex2api-gpt-5-6-text"),
                null,
                baseArtifact
        );
    }

    private static ArtifactReference baseArtifact(String text) {
        return new ArtifactReference(
                UUID.randomUUID(),
                2,
                "outline_text",
                "text/plain",
                Map.of("format", "plain_text", "text", text),
                Map.of()
        );
    }

    private static TextGenerationResponse response(String text) {
        return new TextGenerationResponse(
                text,
                "test-provider",
                "test-model",
                "request-1",
                10,
                20
        );
    }
}
