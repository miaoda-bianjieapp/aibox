package com.aibox.provider.openai;

import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.ImagePreservationMode;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelProviderException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageExpansionSupportTest {

    @Test
    void registersWebpReaderForAcceptedSourceImages() {
        assertTrue(ImageIO.getImageReadersByFormatName("webp").hasNext());
    }

    @Test
    void preparesCenteredCanvasAndTransparentOuterMask() throws IOException {
        ModelAsset asset = asset(320, 240, new Color(210, 30, 20, 255));

        ImageExpansionSupport.PreparedExpansion prepared =
                ImageExpansionSupport.prepare(asset, "16:9");

        assertEquals(16 * prepared.targetHeight(), 9 * prepared.targetWidth());
        assertEquals(
                0,
                prepared.targetWidth()
                        % ImageExpansionSupport.GPT_IMAGE_2_LIMITS.dimensionMultiple()
        );
        assertEquals(
                0,
                prepared.targetHeight()
                        % ImageExpansionSupport.GPT_IMAGE_2_LIMITS.dimensionMultiple()
        );
        assertTrue((long) prepared.targetWidth() * prepared.targetHeight()
                >= ImageExpansionSupport.MIN_TOTAL_PIXELS);

        BufferedImage input = decode(prepared.inputPng());
        BufferedImage mask = decode(prepared.maskPng());
        assertEquals(0, input.getRGB(0, 0) >>> 24);
        assertEquals(0, mask.getRGB(0, 0) >>> 24);
        assertEquals(
                255,
                mask.getRGB(prepared.offsetX(), prepared.offsetY()) >>> 24
        );
    }

    @Test
    void strictModeRestoresOriginalPixelsWhileFlexibleModeKeepsModelPixels() throws IOException {
        ModelAsset asset = asset(32, 24, new Color(220, 40, 30, 255));
        ImageExpansionSupport.PreparedExpansion prepared =
                ImageExpansionSupport.prepare(
                        asset,
                        "1:1",
                        1.0,
                        ImageExpansionSupport.GPT_IMAGE_2_LIMITS,
                        "1024x1024"
                );
        byte[] generated = png(
                prepared.providerWidth(),
                prepared.providerHeight(),
                new Color(20, 180, 80, 255)
        );
        GeneratedImage modelImage = new GeneratedImage(null, "image/png", null, generated);

        BufferedImage strict = decode(ImageExpansionSupport.finalizeImage(
                modelImage, prepared, ImagePreservationMode.STRICT
        ).content());
        BufferedImage flexible = decode(ImageExpansionSupport.finalizeImage(
                modelImage, prepared, ImagePreservationMode.FLEXIBLE
        ).content());

        int sourceX = prepared.offsetX() + 4;
        int sourceY = prepared.offsetY() + 4;
        assertEquals(new Color(220, 40, 30, 255).getRGB(), strict.getRGB(sourceX, sourceY));
        assertEquals(new Color(20, 180, 80, 255).getRGB(), flexible.getRGB(sourceX, sourceY));
        assertEquals(new Color(20, 180, 80, 255).getRGB(), strict.getRGB(0, 0));
    }

    @Test
    void preparesFixedProviderCanvasAndCenteredCustomRatioCrop() throws IOException {
        ModelAsset asset = asset(320, 240, Color.BLUE);

        ImageExpansionSupport.PreparedExpansion prepared =
                ImageExpansionSupport.prepare(
                        asset,
                        "7:5",
                        1.0,
                        ImageExpansionSupport.GPT_IMAGE_2_LIMITS,
                        "1536x864"
                );

        assertEquals(1536, prepared.providerWidth());
        assertEquals(864, prepared.providerHeight());
        assertEquals(163, prepared.providerCropX());
        assertEquals(0, prepared.providerCropY());
        assertEquals(1210, prepared.providerCropWidth());
        assertEquals(864, prepared.providerCropHeight());

        BufferedImage input = decode(prepared.inputPng());
        assertEquals(1536, input.getWidth());
        assertEquals(864, input.getHeight());
        assertEquals(0, input.getRGB(0, 0) >>> 24);
    }

    @Test
    void acceptsProviderOutputWithDifferentDimensionsAndCenterCropsIt() throws IOException {
        ModelAsset asset = asset(32, 24, Color.BLUE);
        ImageExpansionSupport.PreparedExpansion prepared =
                ImageExpansionSupport.prepare(
                        asset,
                        "1:1",
                        1.0,
                        ImageExpansionSupport.GPT_IMAGE_2_LIMITS,
                        "1024x1024"
                );
        BufferedImage providerOutput =
                new BufferedImage(1536, 1024, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < providerOutput.getHeight(); y++) {
            for (int x = 0; x < providerOutput.getWidth(); x++) {
                providerOutput.setRGB(
                        x,
                        y,
                        x >= 256 && x < 1280 ? Color.GREEN.getRGB() : Color.RED.getRGB()
                );
            }
        }

        GeneratedImage finalized = ImageExpansionSupport.finalizeImage(
                new GeneratedImage(null, "image/png", null, png(providerOutput)),
                prepared,
                ImagePreservationMode.FLEXIBLE
        );
        BufferedImage result = decode(finalized.content());

        assertEquals(prepared.targetWidth(), result.getWidth());
        assertEquals(prepared.targetHeight(), result.getHeight());
        assertEquals(Color.GREEN.getRGB(), result.getRGB(0, 0));
        assertEquals(
                Color.GREEN.getRGB(),
                result.getRGB(result.getWidth() - 1, result.getHeight() - 1)
        );
    }

    @Test
    void expansionScaleIncreasesTheResolvedCanvas() {
        ModelAsset asset = asset(320, 240, Color.BLUE);

        ImageExpansionSupport.PreparedExpansion minimum = ImageExpansionSupport.prepare(
                asset, "16:9", 1.0, ImageExpansionSupport.GPT_IMAGE_2_LIMITS
        );
        ImageExpansionSupport.PreparedExpansion expanded = ImageExpansionSupport.prepare(
                asset, "16:9", 1.5, ImageExpansionSupport.GPT_IMAGE_2_LIMITS
        );

        assertTrue(expanded.targetWidth() > minimum.targetWidth());
        assertTrue(expanded.targetHeight() > minimum.targetHeight());
        assertEquals(16 * expanded.targetHeight(), 9 * expanded.targetWidth());
    }

    @Test
    void resolvesSourceAspectRatioForScaleOnlyExpansion() {
        assertEquals(
                "4:3",
                ImageExpansionSupport.resolveAspectRatio(
                        asset(320, 240, Color.BLUE),
                        "source",
                        ImageExpansionSupport.GPT_IMAGE_2_LIMITS
                )
        );
    }

    @Test
    void sourceRatioExpansionAppliesTheSelectedLinearScale() {
        ImageExpansionSupport.Limits limits =
                new ImageExpansionSupport.Limits(1, 8_294_400, 3_840, 50 * 1024 * 1024, 1);

        ImageExpansionSupport.PreparedExpansion prepared =
                ImageExpansionSupport.prepare(
                        asset(108, 192, Color.BLUE),
                        "9:16",
                        1.25,
                        limits,
                        "864x1536"
                );

        assertEquals(135, prepared.targetWidth());
        assertEquals(240, prepared.targetHeight());
    }

    @Test
    void supportsCoprimeSourceDimensionsForOneAndFractionalScale() {
        ModelAsset source = asset(1279, 1629, Color.BLUE);
        String ratio = ImageExpansionSupport.resolveAspectRatio(
                source,
                "source",
                ImageExpansionSupport.GPT_IMAGE_2_LIMITS
        );

        ImageExpansionSupport.PreparedExpansion originalSize =
                ImageExpansionSupport.prepare(
                        source,
                        ratio,
                        1.0,
                        ImageExpansionSupport.GPT_IMAGE_2_LIMITS,
                        "864x1536",
                        true
                );
        ImageExpansionSupport.PreparedExpansion expanded =
                ImageExpansionSupport.prepare(
                        source,
                        ratio,
                        1.25,
                        ImageExpansionSupport.GPT_IMAGE_2_LIMITS,
                        "864x1536",
                        true
                );

        assertEquals("1279:1629", ratio);
        assertEquals(1279, originalSize.targetWidth());
        assertEquals(1629, originalSize.targetHeight());
        assertEquals(1599, expanded.targetWidth());
        assertEquals(2037, expanded.targetHeight());
        assertEquals(864, expanded.providerCropWidth());
        assertTrue(expanded.providerCropHeight() > 0);
    }

    @Test
    void rejectsInvalidAspectRatio() {
        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> ImageExpansionSupport.prepare(asset(32, 24, Color.RED), "not-a-ratio")
        );
        assertEquals("IMAGE_ASPECT_RATIO_INVALID", exception.code());
    }

    private static ModelAsset asset(int width, int height, Color color) {
        return new ModelAsset(
                UUID.randomUUID(),
                "source.png",
                "image/png",
                png(width, height, color)
        );
    }

    private static byte[] png(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return png(image);
    }

    private static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BufferedImage decode(byte[] content) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(content));
    }
}
