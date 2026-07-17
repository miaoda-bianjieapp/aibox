package com.aibox.features.image.generate;

import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageGenerateFeatureHandlerTest {

    private final ImageGenerateFeatureHandler handler = new ImageGenerateFeatureHandler();

    @Test
    void requiresPromptAndSupportedAspectRatio() {
        assertThrows(FeatureValidationException.class, () -> handler.validate(context(
                Map.of("aspectRatio", "1:1"), List.of(), List.of(), null
        )));
        assertThrows(FeatureValidationException.class, () -> handler.validate(context(
                Map.of("prompt", "一只猫", "aspectRatio", "4:3"), List.of(), List.of(), null
        )));
    }

    @Test
    void validatesReferenceImageLimitsAndTypes() {
        List<UUID> ids = List.of(UUID.randomUUID());
        assertThrows(FeatureValidationException.class, () -> handler.validate(context(
                Map.of("prompt", "一只猫", "aspectRatio", "1:1"),
                ids,
                List.of(new InputAssetReference(ids.get(0), "note.txt", "text/plain", 10)),
                null
        )));
    }

    @Test
    void sendsUserAndPreviousImagesAndReturnsOneAssetDraft() {
        UUID userAssetId = UUID.randomUUID();
        UUID previousAssetId = UUID.randomUUID();
        ArtifactReference base = new ArtifactReference(
                UUID.randomUUID(),
                1,
                "image",
                "image/png",
                Map.of("assetId", previousAssetId.toString()),
                Map.of()
        );
        FeatureExecutionContext context = context(
                Map.of("prompt", "改成夜晚场景", "aspectRatio", "16:9"),
                List.of(userAssetId),
                List.of(new InputAssetReference(userAssetId, "reference.png", "image/png", 1024)),
                base
        );
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new CapturingImageGateway(captured);

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertEquals(List.of(userAssetId, previousAssetId), captured.get().inputAssetIds());
        assertEquals("16:9", captured.get().size());
        assertEquals(1, captured.get().count());
        assertEquals("image-deployment", captured.get().deploymentCode());
        assertEquals(1, result.artifacts().size());
        assertEquals("image", result.artifacts().get(0).kind());
        assertEquals(1, result.artifacts().get(0).outputAssets().size());
        assertEquals(base.id().toString(), result.artifacts().get(0).metadata().get("basedOnArtifactId"));
    }

    private static FeatureExecutionContext context(
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            List<InputAssetReference> inputAssets,
            ArtifactReference baseArtifact
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ImageGenerateFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                inputAssetIds,
                inputAssets,
                Map.of(ModelCapability.IMAGE_GENERATION.name(), "image-deployment"),
                "legacy-deployment",
                baseArtifact
        );
    }

    private static final class CapturingImageGateway implements ModelGateway {
        private final AtomicReference<ImageGenerationRequest> captured;

        private CapturingImageGateway(AtomicReference<ImageGenerationRequest> captured) {
            this.captured = captured;
        }

        @Override
        public TextGenerationResponse generateText(TextGenerationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
            captured.set(request);
            return new ImageGenerationResponse(
                    List.of(new GeneratedImage(
                            null,
                            "image/png",
                            null,
                            "image".getBytes(StandardCharsets.UTF_8)
                    )),
                    "test-provider",
                    "test-model",
                    "request-1",
                    null,
                    null
            );
        }
    }
}
