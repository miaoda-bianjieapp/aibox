package com.aibox.features.image.backgroundedit;

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
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundEditFeatureHandlerTest {

    private final BackgroundEditFeatureHandler handler = new BackgroundEditFeatureHandler();

    @Test
    void requiresOneSourceImageAndAReplaceInstruction() {
        UUID sourceId = UUID.randomUUID();
        assertThrows(FeatureValidationException.class, () -> handler.validate(context(
                Map.of("mode", "remove_background"),
                List.of(),
                List.of(),
                null
        )));
        assertThrows(FeatureValidationException.class, () -> handler.validate(context(
                Map.of("mode", "replace_background", "sourceImage", sourceId.toString()),
                List.of(sourceId),
                assets(sourceId),
                null
        )));
    }

    @Test
    void sendsSourceThenBackgroundReferenceAndKeepsOneOutput() throws IOException {
        UUID sourceId = UUID.randomUUID();
        UUID backgroundId = UUID.randomUUID();
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "replace_background",
                        "sourceImage", sourceId.toString(),
                        "backgroundImage", backgroundId.toString(),
                        "backgroundDescription", "自然光，保留主体投影"
                ),
                List.of(sourceId, backgroundId),
                List.of(
                        new InputAssetReference(sourceId, "subject.jpg", "image/jpeg", 1024, 3, 2),
                        new InputAssetReference(backgroundId, "background.png", "image/png", 1024, 2, 2)
                ),
                null
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, capturingGateway(captured, opaquePng()));

        assertEquals(List.of(sourceId, backgroundId), captured.get().inputAssetIds());
        assertEquals(null, captured.get().size());
        assertEquals(1, captured.get().count());
        assertEquals("image-deployment", captured.get().deploymentCode());
        assertEquals(1, result.artifacts().size());
        assertEquals(1, result.artifacts().get(0).outputAssets().size());
        assertEquals("image/png", result.artifacts().get(0).outputAssets().get(0).mediaType());
        BufferedImage output = ImageIO.read(
                new java.io.ByteArrayInputStream(result.artifacts().get(0).outputAssets().get(0).content())
        );
        assertTrue(output.getColorModel().hasAlpha());
        assertEquals(3, output.getWidth());
        assertEquals(2, output.getHeight());
    }

    @Test
    void acceptsTextOnlyBackgroundReplacement() {
        UUID sourceId = UUID.randomUUID();
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "replace_background",
                        "sourceImage", sourceId.toString(),
                        "backgroundDescription", "白色摄影棚背景"
                ),
                List.of(sourceId),
                assets(sourceId),
                null
        );

        handler.validate(context);
        handler.execute(context, capturingGateway(captured, opaquePng()));

        assertEquals(List.of(sourceId), captured.get().inputAssetIds());
        assertTrue(captured.get().prompt().contains("白色摄影棚背景"));
    }

    @Test
    void usesTheBaseArtifactAsTheFirstImageForARevision() {
        UUID originalSourceId = UUID.randomUUID();
        UUID currentResultId = UUID.randomUUID();
        UUID backgroundId = UUID.randomUUID();
        ArtifactReference baseArtifact = new ArtifactReference(
                UUID.randomUUID(),
                2,
                "image",
                "image/png",
                Map.of("assetId", currentResultId.toString()),
                Map.of()
        );
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "replace_background",
                        "sourceImage", originalSourceId.toString(),
                        "backgroundImage", backgroundId.toString()
                ),
                List.of(currentResultId, backgroundId),
                List.of(
                        new InputAssetReference(currentResultId, "version-2.png", "image/png", 1024, 4, 3),
                        new InputAssetReference(backgroundId, "background.png", "image/png", 1024, 2, 2)
                ),
                baseArtifact
        );
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();

        handler.validate(context);
        handler.execute(context, capturingGateway(captured, opaquePng()));

        assertEquals(List.of(currentResultId, backgroundId), captured.get().inputAssetIds());
    }

    @Test
    void extractsAlphaFromWhiteAndBlackBackgroundPair() throws IOException {
        UUID sourceId = UUID.randomUUID();
        List<ImageGenerationRequest> captured = new ArrayList<>();
        FeatureExecutionContext context = context(
                Map.of("mode", "remove_background", "sourceImage", sourceId.toString()),
                List.of(sourceId),
                assets(sourceId),
                null
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(
                context,
                sequenceGateway(captured, whiteBackgroundPng(), blackBackgroundPng())
        );

        assertEquals(2, captured.size());
        assertEquals(List.of(sourceId), captured.get(0).inputAssetIds());
        assertTrue(captured.get(0).inlineInputAssets().isEmpty());
        assertTrue(captured.get(0).prompt().contains("#FFFFFF"));
        assertEquals("alpha_white", captured.get(0).metadata().get("providerInvocationKey"));
        assertTrue(captured.get(1).inputAssetIds().isEmpty());
        assertEquals(1, captured.get(1).inlineInputAssets().size());
        BufferedImage inlineWhite = ImageIO.read(
                new java.io.ByteArrayInputStream(
                        captured.get(1).inlineInputAssets().get(0).content()
                )
        );
        assertEquals(0xFFFFFFFF, inlineWhite.getRGB(0, 0));
        assertEquals(0xFFFF0000, inlineWhite.getRGB(1, 0));
        assertTrue(captured.get(1).prompt().contains("#000000"));
        assertEquals("alpha_black", captured.get(1).metadata().get("providerInvocationKey"));
        BufferedImage output = ImageIO.read(
                new java.io.ByteArrayInputStream(result.artifacts().get(0).outputAssets().get(0).content())
        );
        assertTrue(output.getColorModel().hasAlpha());
        assertEquals(0, output.getRGB(0, 0) >>> 24);
        assertEquals(0xFFFF0000, output.getRGB(1, 0));
        assertTrue(Math.abs((output.getRGB(0, 1) >>> 24) - 128) <= 1);
        assertTrue((output.getRGB(0, 1) & 0xFF) >= 254);
        assertEquals(0xFF00FF00, output.getRGB(1, 1));
    }

    @Test
    void requiresTransparencyForCutout() {
        UUID sourceId = UUID.randomUUID();
        FeatureExecutionContext context = context(
                Map.of("mode", "remove_background", "sourceImage", sourceId.toString()),
                List.of(sourceId),
                assets(sourceId),
                null
        );

        handler.validate(context);
        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> handler.execute(context, capturingGateway(new AtomicReference<>(), opaquePng()))
        );

        assertEquals("PROVIDER_INVALID_RESPONSE", exception.code());
        assertFalse(exception.retryable());
    }

    @Test
    void rejectsWebpBecauseSourceDimensionsCannotBeReliablyPreserved() {
        UUID sourceId = UUID.randomUUID();
        FeatureExecutionContext context = context(
                Map.of("mode", "remove_background", "sourceImage", sourceId.toString()),
                List.of(sourceId),
                List.of(new InputAssetReference(
                        sourceId,
                        "subject.webp",
                        "image/webp",
                        1024,
                        2,
                        2
                )),
                null
        );

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
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
                BackgroundEditFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                inputAssetIds,
                inputAssets,
                Map.of(ModelCapability.IMAGE_GENERATION.name(), "image-deployment"),
                "legacy-deployment",
                baseArtifact
        );
    }

    private static List<InputAssetReference> assets(UUID sourceId) {
        return List.of(new InputAssetReference(sourceId, "subject.png", "image/png", 1024, 2, 2));
    }

    private static ModelGateway capturingGateway(
            AtomicReference<ImageGenerationRequest> captured,
            byte[] output
    ) {
        return new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
                captured.set(request);
                return new ImageGenerationResponse(
                        List.of(new GeneratedImage(null, "image/png", null, output)),
                        "test-provider",
                        "test-model",
                        "request-1",
                        null,
                        null
                );
            }
        };
    }

    private static ModelGateway sequenceGateway(
            List<ImageGenerationRequest> captured,
            byte[] firstOutput,
            byte[] secondOutput
    ) {
        return new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
                captured.add(request);
                byte[] output = captured.size() == 1 ? firstOutput : secondOutput;
                return new ImageGenerationResponse(
                        List.of(new GeneratedImage(null, "image/png", null, output)),
                        "test-provider",
                        "test-model",
                        "request-" + captured.size(),
                        null,
                        null
                );
            }
        };
    }

    private static byte[] opaquePng() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFFFF0000);
        return png(image);
    }

    private static byte[] whiteBackgroundPng() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00FFFFFF);
        image.setRGB(1, 0, 0x00FF0000);
        image.setRGB(0, 1, 0x007F7FFF);
        image.setRGB(1, 1, 0x0000FF00);
        return png(image);
    }

    private static byte[] blackBackgroundPng() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00000000);
        image.setRGB(1, 0, 0x00FF0000);
        image.setRGB(0, 1, 0x00000080);
        image.setRGB(1, 1, 0x0000FF00);
        return png(image);
    }

    private static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
