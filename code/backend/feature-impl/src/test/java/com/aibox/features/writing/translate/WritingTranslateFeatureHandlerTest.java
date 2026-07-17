package com.aibox.features.writing.translate;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritingTranslateFeatureHandlerTest {

    private final WritingTranslateFeatureHandler handler = new WritingTranslateFeatureHandler();

    @Test
    void requiresSourceText() {
        FeatureExecutionContext context = context(Map.of("targetLanguage", "en"));

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void limitsSourceTextToTwoThousandUnicodeCharacters() {
        FeatureExecutionContext context = context(Map.of(
                "sourceText", "文".repeat(2_001),
                "targetLanguage", "en"
        ));

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void rejectsUnsupportedTargetLanguage() {
        FeatureExecutionContext context = context(Map.of(
                "sourceText", "需要翻译的内容",
                "targetLanguage", "it"
        ));

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void rejectsAttachments() {
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingTranslateFeatureHandler.FEATURE_CODE,
                1,
                Map.of("sourceText", "需要翻译的内容", "targetLanguage", "en"),
                List.of(UUID.randomUUID())
        );

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void translatesWithTheSelectedTextDeploymentAndReturnsPlainText() {
        String source = "第一段\n\n#include <stdio.h>\nhttps://example.com";
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingTranslateFeatureHandler.FEATURE_CODE,
                1,
                Map.of("sourceText", source, "targetLanguage", "en"),
                List.of(),
                Map.of("TEXT_GENERATION", "codex2api-gpt-5-6-text"),
                null,
                null
        );
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return response("First paragraph\n\n#include <stdio.h>\nhttps://example.com");
        };

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertEquals("codex2api-gpt-5-6-text", captured.get().deploymentCode());
        assertTrue(captured.get().systemPrompt().contains("Detect the source language automatically"));
        assertTrue(captured.get().systemPrompt().contains("plain text"));
        assertTrue(captured.get().userPrompt().contains("English (en)"));
        assertTrue(captured.get().userPrompt().contains(source));
        assertEquals(0.2, captured.get().temperature());
        assertEquals("rich_text", result.artifacts().get(0).kind());
        assertEquals("text/plain", result.artifacts().get(0).mimeType());
        assertEquals("plain_text", result.artifacts().get(0).content().get("format"));
        assertEquals(
                "First paragraph\n\n#include <stdio.h>\nhttps://example.com",
                result.artifacts().get(0).content().get("text")
        );
        assertEquals("en", result.artifacts().get(0).metadata().get("targetLanguage"));
    }

    @Test
    void recordsTheBaseArtifactWithoutUsingItsTranslationAsSource() {
        ArtifactReference baseArtifact = new ArtifactReference(
                UUID.randomUUID(),
                2,
                "rich_text",
                "text/plain",
                Map.of("format", "plain_text", "text", "Previous translation"),
                Map.of()
        );
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingTranslateFeatureHandler.FEATURE_CODE,
                1,
                Map.of("sourceText", "原始内容", "targetLanguage", "en"),
                List.of(),
                Map.of("TEXT_GENERATION", "codex2api-gpt-5-4-mini-text"),
                null,
                baseArtifact
        );
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return response("Original content");
        };

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertTrue(captured.get().userPrompt().contains("原始内容"));
        assertTrue(!captured.get().userPrompt().contains("Previous translation"));
        assertEquals(
                baseArtifact.id().toString(),
                result.artifacts().get(0).metadata().get("basedOnArtifactId")
        );
        assertEquals(2, result.artifacts().get(0).metadata().get("basedOnVersion"));
    }

    @Test
    void treatsAnEmptyModelResponseAsRetryableProviderFailure() {
        FeatureExecutionContext context = context(Map.of(
                "sourceText", "需要翻译的内容",
                "targetLanguage", "en"
        ));
        ModelGateway gateway = request -> response("  ");

        handler.validate(context);
        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> handler.execute(context, gateway)
        );

        assertEquals("MODEL_EMPTY_RESPONSE", exception.code());
        assertTrue(exception.retryable());
    }

    private static FeatureExecutionContext context(Map<String, Object> parameters) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingTranslateFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                List.of()
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
