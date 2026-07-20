package com.aibox.features.image.expand;

import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.ImageExpansionRequest;
import com.aibox.feature.spi.ImageExpansionResponse;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.ImagePreservationMode;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
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

class ImageExpandFeatureHandlerTest {

    private final ImageExpandFeatureHandler handler = new ImageExpandFeatureHandler();

    @Test
    void requiresOneSupportedSourceImageAndValidRatio() {
        assertThrows(FeatureValidationException.class, () -> handler.validate(context(
                Map.of(
                        "preservationMode", "strict",
                        "ratioMode", "preset",
                        "presetAspectRatio", "1:1"
                ),
                List.of(),
                List.of(),
                null
        )));

        UUID assetId = UUID.randomUUID();
        assertThrows(FeatureValidationException.class, () -> handler.validate(context(
                Map.of(
                        "preservationMode", "strict",
                        "ratioMode", "custom",
                        "customAspectRatio", "4:1"
                ),
                List.of(assetId),
                List.of(new InputAssetReference(assetId, "source.png", "image/png", 1024)),
                null
        )));
    }

    @Test
    void changeRatioModeUsesNormalizedRatioAndMinimumExpansion() {
        UUID sourceAssetId = UUID.randomUUID();
        FeatureExecutionContext context = context(
                Map.of(
                        "operationMode", "change_ratio",
                        "preservationMode", "strict",
                        "ratioMode", "custom",
                        "customAspectRatio", "14:10",
                        "expansionScaleMode", "custom",
                        "customExpansionScale", 1.8
                ),
                List.of(sourceAssetId),
                List.of(new InputAssetReference(sourceAssetId, "source.webp", "image/webp", 2048)),
                null
        );
        AtomicReference<ImageExpansionRequest> captured = new AtomicReference<>();

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, new CapturingGateway(captured));

        assertEquals(sourceAssetId, captured.get().inputAssetId());
        assertEquals("7:5", captured.get().aspectRatio());
        assertEquals(1.0, captured.get().expansionScale());
        assertEquals(ImagePreservationMode.STRICT, captured.get().preservationMode());
        assertEquals("image-deployment", captured.get().deploymentCode());
        assertEquals(1, result.artifacts().size());
        assertEquals("image", result.artifacts().get(0).kind());
        assertEquals("7:5", result.artifacts().get(0).metadata().get("targetAspectRatio"));
        assertEquals(1.0, result.artifacts().get(0).metadata().get("expansionScale"));
        assertEquals(
                "change_ratio",
                result.artifacts().get(0).metadata().get("operationMode")
        );
        assertEquals(true, result.artifacts().get(0).metadata().get("subjectLocked"));
        assertTrue(captured.get().prompt().contains("First identify the primary subject"));
        assertEquals(1, result.artifacts().get(0).outputAssets().size());
    }

    @Test
    void expandModeKeepsSourceRatioAndUsesSelectedScale() {
        UUID sourceAssetId = UUID.randomUUID();
        FeatureExecutionContext context = context(
                Map.of(
                        "operationMode", "expand",
                        "preservationMode", "strict",
                        "expansionScaleMode", "preset",
                        "presetExpansionScale", "1.25"
                ),
                List.of(sourceAssetId),
                List.of(new InputAssetReference(sourceAssetId, "source.png", "image/png", 2048)),
                null
        );
        AtomicReference<ImageExpansionRequest> captured = new AtomicReference<>();

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, new CapturingGateway(captured));

        assertEquals("source", captured.get().aspectRatio());
        assertEquals(1.25, captured.get().expansionScale());
        assertEquals("expand", result.artifacts().get(0).metadata().get("operationMode"));
    }

    @Test
    void customScaleCanExceedLegacyPlatformLimit() {
        UUID sourceAssetId = UUID.randomUUID();
        FeatureExecutionContext context = context(
                Map.of(
                        "operationMode", "expand",
                        "preservationMode", "strict",
                        "expansionScaleMode", "custom",
                        "customExpansionScale", 3.5
                ),
                List.of(sourceAssetId),
                List.of(new InputAssetReference(sourceAssetId, "source.png", "image/png", 2048)),
                null
        );
        AtomicReference<ImageExpansionRequest> captured = new AtomicReference<>();

        handler.validate(context);
        handler.execute(context, new CapturingGateway(captured));

        assertEquals(3.5, captured.get().expansionScale());
    }

    @Test
    void revisionUsesCurrentArtifactImageInsteadOfOriginalInput() {
        UUID originalAssetId = UUID.randomUUID();
        UUID currentAssetId = UUID.randomUUID();
        ArtifactReference base = new ArtifactReference(
                UUID.randomUUID(),
                2,
                "image",
                "image/png",
                Map.of("assetId", currentAssetId.toString()),
                Map.of()
        );
        FeatureExecutionContext context = context(
                Map.of(
                        "operationMode", "change_ratio",
                        "preservationMode", "flexible",
                        "ratioMode", "preset",
                        "presetAspectRatio", "16:9"
                ),
                List.of(originalAssetId),
                List.of(new InputAssetReference(originalAssetId, "original.jpg", "image/jpeg", 1024)),
                base
        );
        AtomicReference<ImageExpansionRequest> captured = new AtomicReference<>();

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, new CapturingGateway(captured));

        assertEquals(currentAssetId, captured.get().inputAssetId());
        assertEquals(ImagePreservationMode.STRICT, captured.get().preservationMode());
        assertEquals(
                "flexible",
                result.artifacts().get(0).metadata().get("requestedPreservationMode")
        );
        assertEquals(true, result.artifacts().get(0).metadata().get("subjectLocked"));
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
                ImageExpandFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                inputAssetIds,
                inputAssets,
                Map.of(ModelCapability.IMAGE_GENERATION.name(), "image-deployment"),
                "legacy-deployment",
                baseArtifact
        );
    }

    private static final class CapturingGateway implements ModelGateway {
        private final AtomicReference<ImageExpansionRequest> captured;

        private CapturingGateway(AtomicReference<ImageExpansionRequest> captured) {
            this.captured = captured;
        }

        @Override
        public TextGenerationResponse generateText(TextGenerationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ImageExpansionResponse expandImage(ImageExpansionRequest request) {
            captured.set(request);
            return new ImageExpansionResponse(
                    new ImageGenerationResponse(
                            List.of(new GeneratedImage(null, "image/png", null, new byte[]{1, 2, 3})),
                            "test-provider",
                            "test-model",
                            "request-1",
                            null,
                            null
                    ),
                    640,
                    480,
                    1120,
                    800
            );
        }
    }
}
