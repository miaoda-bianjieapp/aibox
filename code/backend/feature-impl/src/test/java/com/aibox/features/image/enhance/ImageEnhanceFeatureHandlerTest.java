package com.aibox.features.image.enhance;

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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageEnhanceFeatureHandlerTest {

    private final ImageEnhanceFeatureHandler handler = new ImageEnhanceFeatureHandler();

    @Test
    void upscalesOneImageToTheExactRequestedDimensions() throws IOException {
        UUID sourceId = UUID.randomUUID();
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "upscale",
                        "sourceImage", sourceId.toString(),
                        "scale", "2x"
                ),
                sourceId,
                3,
                2
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(
                context,
                capturingGateway(captured, png(3, 2))
        );

        assertEquals(List.of(sourceId), captured.get().inputAssetIds());
        assertEquals("image-deployment", captured.get().deploymentCode());
        assertEquals("png", captured.get().metadata().get("outputFormat"));
        assertTrue(captured.get().prompt().contains("2 times"));
        assertEquals(1, result.artifacts().size());
        assertEquals(1, result.artifacts().get(0).outputAssets().size());
        assertEquals("image/png", result.artifacts().get(0).outputAssets().get(0).mediaType());
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                result.artifacts().get(0).outputAssets().get(0).content()
        ));
        assertEquals(6, output.getWidth());
        assertEquals(4, output.getHeight());
    }

    @Test
    void restoresThenColorizesAnOldPhotoInADedicatedSecondPass() throws IOException {
        UUID sourceId = UUID.randomUUID();
        List<ImageGenerationRequest> captured = new ArrayList<>();
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "old_photo_restore",
                        "sourceImage", sourceId.toString(),
                        "colorize", true
                ),
                sourceId,
                5,
                3
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(
                context,
                sequentialGateway(captured, png(5, 3), png(5, 3))
        );

        assertEquals(2, captured.size());
        ImageGenerationRequest restoration = captured.get(0);
        ImageGenerationRequest colorization = captured.get(1);
        assertEquals(List.of(sourceId), restoration.inputAssetIds());
        assertTrue(restoration.inlineInputAssets().isEmpty());
        assertTrue(restoration.prompt().contains("dedicated restoration pass"));
        assertTrue(restoration.prompt().contains("Do not colorize"));
        assertEquals("enhance_old_photo_restore", restoration.metadata().get("providerInvocationKey"));

        assertTrue(colorization.inputAssetIds().isEmpty());
        assertEquals(1, colorization.inlineInputAssets().size());
        assertEquals("restored-old-photo.png", colorization.inlineInputAssets().get(0).fileName());
        assertEquals("image/png", colorization.inlineInputAssets().get(0).mediaType());
        assertArrayEquals(png(5, 3), colorization.inlineInputAssets().get(0).content());
        assertTrue(colorization.prompt().contains("MANDATORY FULL-COLOR COLORIZATION"));
        assertTrue(colorization.prompt().contains("Do not return grayscale"));
        assertEquals("enhance_old_photo_colorize", colorization.metadata().get("providerInvocationKey"));

        assertEquals(2, result.artifacts().get(0).metadata().get("modelInvocationCount"));
        assertEquals("老照片修复结果", result.artifacts().get(0).title());
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                result.artifacts().get(0).outputAssets().get(0).content()
        ));
        assertEquals(5, output.getWidth());
        assertEquals(3, output.getHeight());
    }

    @Test
    void restoresAnOldPhotoInOneDedicatedPassWhenColorizationIsDisabled() {
        UUID sourceId = UUID.randomUUID();
        List<ImageGenerationRequest> captured = new ArrayList<>();
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "old_photo_restore",
                        "sourceImage", sourceId.toString(),
                        "colorize", false
                ),
                sourceId,
                5,
                3
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(
                context,
                sequentialGateway(captured, png(5, 3))
        );

        assertEquals(1, captured.size());
        assertTrue(captured.get(0).prompt().contains("Remove visible scratches"));
        assertTrue(captured.get(0).prompt().contains("recover local contrast"));
        assertTrue(captured.get(0).prompt().contains("sharp edges"));
        assertTrue(captured.get(0).prompt().contains("Do not colorize"));
        assertEquals(1, result.artifacts().get(0).metadata().get("modelInvocationCount"));
    }

    @Test
    void usesTheBaseArtifactImageWhenCreatingANewVersion() {
        UUID originalSourceId = UUID.randomUUID();
        UUID currentResultId = UUID.randomUUID();
        ArtifactReference baseArtifact = new ArtifactReference(
                UUID.randomUUID(),
                2,
                "image",
                "image/png",
                Map.of("assetId", currentResultId.toString()),
                Map.of()
        );
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ImageEnhanceFeatureHandler.FEATURE_CODE,
                1,
                Map.of(
                        "mode", "denoise",
                        "sourceImage", originalSourceId.toString()
                ),
                List.of(currentResultId),
                List.of(new InputAssetReference(
                        currentResultId,
                        "version-2.png",
                        "image/png",
                        1024,
                        4,
                        3
                )),
                Map.of(ModelCapability.IMAGE_GENERATION.name(), "image-deployment"),
                "legacy-deployment",
                baseArtifact
        );
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(
                context,
                capturingGateway(captured, png(4, 3))
        );

        assertEquals(List.of(currentResultId), captured.get().inputAssetIds());
        assertEquals(
                baseArtifact.id().toString(),
                result.artifacts().get(0).metadata().get("basedOnArtifactId")
        );
        assertEquals(2, result.artifacts().get(0).metadata().get("basedOnVersion"));
    }

    @Test
    void rejectsUnsupportedOptionCombinationsAndOversizedUpscaleTargets() {
        UUID sourceId = UUID.randomUUID();
        FeatureValidationException colorizeError = assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(
                        Map.of(
                                "mode", "denoise",
                                "sourceImage", sourceId.toString(),
                                "colorize", true
                        ),
                        sourceId,
                        100,
                        100
                ))
        );
        assertEquals("colorize", colorizeError.field());

        FeatureValidationException sizeError = assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(
                        Map.of(
                                "mode", "upscale",
                                "sourceImage", sourceId.toString(),
                                "scale", "4x"
                        ),
                        sourceId,
                        2049,
                        100
                ))
        );
        assertEquals("scale", sizeError.field());
        assertTrue(sizeError.getMessage().contains("8192"));

        FeatureValidationException sourceSizeError = assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context(
                        Map.of(
                                "mode", "deblur",
                                "sourceImage", sourceId.toString()
                        ),
                        sourceId,
                        8193,
                        100
                ))
        );
        assertEquals("sourceImage", sourceSizeError.field());
    }

    @Test
    void fitsAStandardPortraitProviderResultBackIntoTheOriginalPortraitCanvas() throws IOException {
        UUID sourceId = UUID.randomUUID();
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "deblur",
                        "sourceImage", sourceId.toString()
                ),
                sourceId,
                4,
                9
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(
                context,
                capturingGateway(captured, png(9, 16))
        );

        assertEquals("9:16", captured.get().size());
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                result.artifacts().get(0).outputAssets().get(0).content()
        ));
        assertEquals(4, output.getWidth());
        assertEquals(9, output.getHeight());
    }

    @Test
    void rejectsAProviderResultWithTheOppositeOrientation() {
        UUID sourceId = UUID.randomUUID();
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "deblur",
                        "sourceImage", sourceId.toString()
                ),
                sourceId,
                4,
                9
        );

        handler.validate(context);
        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> handler.execute(
                        context,
                        capturingGateway(new AtomicReference<>(), png(16, 9))
                )
        );

        assertEquals("PROVIDER_INVALID_RESPONSE", exception.code());
        assertTrue(exception.getMessage().contains("方向"));
    }

    @Test
    void rejectsAnOversizedDecodedProviderImageBeforeNormalizingIt() {
        UUID sourceId = UUID.randomUUID();
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "denoise",
                        "sourceImage", sourceId.toString()
                ),
                sourceId,
                8192,
                1
        );

        handler.validate(context);
        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> handler.execute(
                        context,
                        capturingGateway(new AtomicReference<>(), png(8193, 1))
                )
        );

        assertEquals("PROVIDER_INVALID_RESPONSE", exception.code());
        assertTrue(exception.getMessage().contains("尺寸过大"));
    }

    private static FeatureExecutionContext context(
            Map<String, Object> parameters,
            UUID sourceId,
            int width,
            int height
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "image.enhance",
                1,
                parameters,
                List.of(sourceId),
                List.of(new InputAssetReference(
                        sourceId,
                        "source.png",
                        "image/png",
                        1024,
                        width,
                        height
                )),
                Map.of(ModelCapability.IMAGE_GENERATION.name(), "image-deployment"),
                "legacy-deployment",
                null
        );
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

    private static ModelGateway sequentialGateway(
            List<ImageGenerationRequest> captured,
            byte[]... outputs
    ) {
        return new ModelGateway() {
            private int invocationIndex;

            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
                captured.add(request);
                if (invocationIndex >= outputs.length) {
                    throw new AssertionError("Unexpected image generation invocation");
                }
                byte[] output = outputs[invocationIndex++];
                return new ImageGenerationResponse(
                        List.of(new GeneratedImage(null, "image/png", null, output)),
                        "test-provider",
                        "test-model",
                        "request-" + invocationIndex,
                        null,
                        null
                );
            }
        };
    }

    private static byte[] png(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
