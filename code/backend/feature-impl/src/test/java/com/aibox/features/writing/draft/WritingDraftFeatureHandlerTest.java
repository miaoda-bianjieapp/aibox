package com.aibox.features.writing.draft;

import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.TextGenerationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WritingDraftFeatureHandlerTest {

    private final WritingDraftFeatureHandler handler = new WritingDraftFeatureHandler();

    @Test
    void requiresTopic() {
        FeatureExecutionContext context = context(Map.of());

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void returnsStandardRichTextArtifact() {
        FeatureExecutionContext context = context(Map.of(
                "topic", "AI 产品周报",
                "audience", "产品团队",
                "tone", "professional",
                "length", "medium"
        ));
        ModelGateway gateway = request -> new TextGenerationResponse(
                "# AI 产品周报",
                "test-provider",
                "test-model",
                "request-1",
                10,
                20
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertEquals(1, result.artifacts().size());
        assertEquals("rich_text", result.artifacts().get(0).kind());
        assertEquals("# AI 产品周报", result.artifacts().get(0).content().get("text"));
    }

    @Test
    void includesTheBaseArtifactWhenCreatingARevision() {
        ArtifactReference base = new ArtifactReference(
                UUID.randomUUID(), 1, "rich_text", "text/markdown",
                Map.of("text", "第一版正文"), Map.of()
        );
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                WritingDraftFeatureHandler.FEATURE_CODE, 1,
                Map.of("topic", "更新后的主题"), List.of(), base
        );
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return new TextGenerationResponse("第二版正文", "test", "model", "request", 1, 1);
        };

        FeatureExecutionResult result = handler.execute(context, gateway);

        assertEquals(true, captured.get().userPrompt().contains("第一版正文"));
        assertEquals(base.id().toString(), result.artifacts().get(0).metadata().get("basedOnArtifactId"));
    }

    private static FeatureExecutionContext context(Map<String, Object> parameters) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                WritingDraftFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                List.of()
        );
    }
}
