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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    void restoresAndColorizesAnOldPhotoWithoutChangingItsDimensions() throws IOException {
        UUID sourceId = UUID.randomUUID();
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
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
                capturingGateway(captured, png(5, 3))
        );

        assertTrue(captured.get().prompt().contains("scratches"));
        assertTrue(captured.get().prompt().contains("colorize"));
        assertEquals("old_photo_restore", captured.get().metadata().get("mode"));
        assertEquals("老照片修复结果", result.artifacts().get(0).title());
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(
                result.artifacts().get(0).outputAssets().get(0).content()
        ));
        assertEquals(5, output.getWidth());
        assertEquals(3, output.getHeight());
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
    void rejectsAProviderResultThatChangesTheOriginalAspectRatio() {
        UUID sourceId = UUID.randomUUID();
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "deblur",
                        "sourceImage", sourceId.toString()
                ),
                sourceId,
                4,
                3
        );

        handler.validate(context);
        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> handler.execute(
                        context,
                        capturingGateway(new AtomicReference<>(), png(4, 4))
                )
        );

        assertEquals("PROVIDER_INVALID_RESPONSE", exception.code());
        assertTrue(exception.getMessage().contains("比例"));
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
