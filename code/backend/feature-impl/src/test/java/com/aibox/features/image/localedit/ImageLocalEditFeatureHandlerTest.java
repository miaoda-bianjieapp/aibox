package com.aibox.features.image.localedit;

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
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageLocalEditFeatureHandlerTest {

    private final ImageLocalEditFeatureHandler handler = new ImageLocalEditFeatureHandler();

    @Test
    void sendsSourceAndMaskWithPreservationEnabled() throws IOException {
        UUID sourceId = UUID.randomUUID();
        UUID maskId = UUID.randomUUID();
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        FeatureExecutionContext context = context(
                Map.of(
                        "sourceImage", sourceId.toString(),
                        "maskImage", maskId.toString(),
                        "instruction", "把杯子改成蓝色"
                ),
                List.of(sourceId, maskId),
                List.of(
                        asset(sourceId, "source.webp", "image/webp", 4, 3),
                        asset(maskId, "mask.png", "image/png", 4, 3)
                ),
                null
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(
                context,
                gateway(captured, png(4, 3, Color.BLUE))
        );

        assertEquals(List.of(sourceId), captured.get().inputAssetIds());
        assertEquals(maskId, captured.get().maskAssetId());
        assertTrue(captured.get().preserveUnmaskedPixels());
        assertEquals("image-deployment", captured.get().deploymentCode());
        assertEquals("image", result.artifacts().get(0).kind());
        assertEquals("image/png", result.artifacts().get(0).mimeType());
        assertEquals(1, result.artifacts().get(0).outputAssets().size());
    }

    @Test
    void rejectsMaskWithDifferentDimensions() {
        UUID sourceId = UUID.randomUUID();
        UUID maskId = UUID.randomUUID();
        FeatureExecutionContext context = context(
                Map.of(
                        "sourceImage", sourceId.toString(),
                        "maskImage", maskId.toString(),
                        "instruction", "移除文字"
                ),
                List.of(sourceId, maskId),
                List.of(
                        asset(sourceId, "source.jpg", "image/jpeg", 4, 3),
                        asset(maskId, "mask.png", "image/png", 3, 4)
                ),
                null
        );

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void revisionUsesBaseArtifactAsSource() throws IOException {
        UUID submittedSourceId = UUID.randomUUID();
        UUID currentResultId = UUID.randomUUID();
        UUID maskId = UUID.randomUUID();
        ArtifactReference base = new ArtifactReference(
                UUID.randomUUID(),
                2,
                "image",
                "image/png",
                Map.of("assetId", currentResultId.toString()),
                Map.of()
        );
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        FeatureExecutionContext context = context(
                Map.of(
                        "sourceImage", submittedSourceId.toString(),
                        "maskImage", maskId.toString(),
                        "instruction", "增加一朵花"
                ),
                List.of(currentResultId, maskId),
                List.of(
                        asset(currentResultId, "version-2.png", "image/png", 4, 3),
                        asset(maskId, "mask.png", "image/png", 4, 3)
                ),
                base
        );

        handler.validate(context);
        handler.execute(context, gateway(captured, png(4, 3, Color.GREEN)));

        assertEquals(List.of(currentResultId), captured.get().inputAssetIds());
        assertEquals(maskId, captured.get().maskAssetId());
    }

    private static FeatureExecutionContext context(
            Map<String, Object> parameters,
            List<UUID> assetIds,
            List<InputAssetReference> assets,
            ArtifactReference base
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ImageLocalEditFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                assetIds,
                assets,
                Map.of(ModelCapability.IMAGE_GENERATION.name(), "image-deployment"),
                "legacy-deployment",
                base
        );
    }

    private static InputAssetReference asset(
            UUID id,
            String name,
            String mediaType,
            int width,
            int height
    ) {
        return new InputAssetReference(id, name, mediaType, 1024, width, height);
    }

    private static ModelGateway gateway(
            AtomicReference<ImageGenerationRequest> captured,
            byte[] output
    ) {
        return new ModelGateway() {
            @Override
            public com.aibox.feature.spi.TextGenerationResponse generateText(
                    com.aibox.feature.spi.TextGenerationRequest request
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
                captured.set(request);
                return new ImageGenerationResponse(
                        List.of(new GeneratedImage(null, "image/png", null, output)),
                        "provider",
                        "model",
                        "request-id",
                        1,
                        1
                );
            }
        };
    }

    private static byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
