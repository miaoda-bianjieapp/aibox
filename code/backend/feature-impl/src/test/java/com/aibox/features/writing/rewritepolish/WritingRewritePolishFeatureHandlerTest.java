package com.aibox.features.writing.rewritepolish;

import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
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

class WritingRewritePolishFeatureHandlerTest {

    private final WritingRewritePolishFeatureHandler handler = new WritingRewritePolishFeatureHandler();

    @Test
    void requiresAValidMode() {
        FeatureExecutionContext context = context(Map.of(
                "mode", "translate",
                "sourceText", "需要处理的文字"
        ));

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void requiresSourceText() {
        FeatureExecutionContext context = context(Map.of("mode", "rewrite"));

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void limitsSourceTextToTwoThousandUnicodeCharacters() {
        FeatureExecutionContext context = context(Map.of(
                "mode", "polish",
                "sourceText", "文".repeat(2_001)
        ));

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void limitsModeRequirementsToFiveHundredUnicodeCharacters() {
        FeatureExecutionContext context = context(Map.of(
                "mode", "rewrite",
                "sourceText", "需要改写的内容",
                "rewriteRequirements", "文".repeat(501)
        ));

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void rejectsRequirementsForTheInactiveMode() {
        FeatureExecutionContext context = context(Map.of(
                "mode", "rewrite",
                "sourceText", "需要改写的内容",
                "polishRequirements", "更自然"
        ));

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void rewritesWithTheSelectedTextDeployment() {
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingRewritePolishFeatureHandler.FEATURE_CODE,
                1,
                Map.of(
                        "mode", "rewrite",
                        "sourceText", "原始内容",
                        "rewriteRequirements", "改得更口语化，并压缩篇幅"
                ),
                List.of(),
                Map.of("TEXT_GENERATION", "codex2api-gpt-5-6-text"),
                null,
                null
        );
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return response("改写后的内容");
        };

        RecordingFeatureOutputEmitter emitter = new RecordingFeatureOutputEmitter();
        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway, emitter);

        assertEquals("codex2api-gpt-5-6-text", captured.get().deploymentCode());
        assertEquals("main", emitter.channel());
        assertEquals("markdown", emitter.format());
        assertEquals(result.artifacts().get(0).content().get("text"), emitter.content());
        assertTrue(captured.get().systemPrompt().contains("not a proofreader"));
        assertTrue(captured.get().userPrompt().contains("Preserve every fact"));
        assertTrue(captured.get().userPrompt().contains("Do not return a near-copy"));
        assertTrue(captured.get().userPrompt().contains("silently compare"));
        assertTrue(captured.get().userPrompt().contains("改得更口语化，并压缩篇幅"));
        assertTrue(captured.get().userPrompt().contains("原始内容"));
        assertEquals(3, captured.get().metadata().get("promptVersion"));
        assertEquals("改写结果", result.artifacts().get(0).title());
        assertEquals("改写后的内容", result.artifacts().get(0).content().get("text"));
        assertEquals("rewrite", result.artifacts().get(0).metadata().get("mode"));
        assertEquals(3, result.artifacts().get(0).metadata().get("promptVersion"));
    }

    @Test
    void canPolishTheSelectedBaseArtifact() {
        ArtifactReference baseArtifact = new ArtifactReference(
                UUID.randomUUID(),
                2,
                "rich_text",
                "text/markdown",
                Map.of("format", "markdown", "text", "上一版内容"),
                Map.of()
        );
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingRewritePolishFeatureHandler.FEATURE_CODE,
                1,
                Map.of(
                        "mode", "polish",
                        "polishRequirements", "表达更专业，改善句间衔接"
                ),
                List.of(),
                Map.of("TEXT_GENERATION", "codex2api-gpt-5-4-mini-text"),
                null,
                baseArtifact
        );
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return response("润色后的内容");
        };

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertTrue(captured.get().userPrompt().contains("上一版内容"));
        assertTrue(captured.get().systemPrompt().contains("not a rewriter"));
        assertTrue(captured.get().userPrompt().contains("light but visible improvement"));
        assertTrue(captured.get().userPrompt().contains("Do not return the source unchanged"));
        assertTrue(captured.get().userPrompt().contains("表达更专业，改善句间衔接"));
        assertEquals("润色结果", result.artifacts().get(0).title());
        assertEquals(baseArtifact.id().toString(),
                result.artifacts().get(0).metadata().get("basedOnArtifactId"));
    }

    private static FeatureExecutionContext context(Map<String, Object> parameters) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingRewritePolishFeatureHandler.FEATURE_CODE,
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
