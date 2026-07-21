package com.aibox.platform.provider;

import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelProviderException;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

final class MaskedImageCompositor {

    private MaskedImageCompositor() {
    }

    static ImageGenerationResponse preserveUnmaskedPixels(
            ImageGenerationResponse response,
            ModelAsset sourceAsset,
            ModelAsset maskAsset
    ) {
        if (response.images().size() != 1) {
            throw invalidResponse("Masked image editing must return exactly one image");
        }
        BufferedImage source = decode(sourceAsset.content(), "source image");
        BufferedImage mask = decode(maskAsset.content(), "mask image");
        validateMask(source, mask);
        GeneratedImage generated = response.images().get(0);
        BufferedImage edited = resize(
                decode(generated.content(), "generated image"),
                source.getWidth(),
                source.getHeight()
        );
        BufferedImage composited = composite(source, edited, mask);
        byte[] content = encodePng(composited);
        return new ImageGenerationResponse(
                List.of(new GeneratedImage(
                        generated.sourceUrl(),
                        "image/png",
                        generated.revisedPrompt(),
                        content
                )),
                response.provider(),
                response.model(),
                response.providerRequestId(),
                response.inputUnits(),
                response.outputUnits()
        );
    }

    static void validateInputs(ModelAsset sourceAsset, ModelAsset maskAsset) {
        BufferedImage source = decode(sourceAsset.content(), "source image");
        BufferedImage mask = decode(maskAsset.content(), "mask image");
        validateMask(source, mask);
    }

    private static void validateMask(BufferedImage source, BufferedImage mask) {
        if (source.getWidth() != mask.getWidth() || source.getHeight() != mask.getHeight()) {
            throw new ModelProviderException(
                    "MASK_DIMENSION_MISMATCH",
                    "The mask dimensions must match the source image",
                    false
            );
        }
        if (!containsEditablePixel(mask)) {
            throw new ModelProviderException(
                    "MASK_EMPTY",
                    "The mask does not contain an editable transparent region",
                    false
            );
        }
    }

    private static BufferedImage decode(byte[] content, String label) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw invalidResponse("Unable to decode " + label);
            }
            return image;
        } catch (IOException exception) {
            throw invalidResponse("Unable to decode " + label);
        }
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private static BufferedImage composite(
            BufferedImage source,
            BufferedImage edited,
            BufferedImage mask
    ) {
        BufferedImage result = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int maskAlpha = mask.getRGB(x, y) >>> 24;
                int editWeight = 255 - maskAlpha;
                result.setRGB(
                        x,
                        y,
                        blend(source.getRGB(x, y), edited.getRGB(x, y), editWeight)
                );
            }
        }
        return result;
    }

    private static int blend(int original, int edited, int editWeight) {
        int preserveWeight = 255 - editWeight;
        int alpha = blendChannel(original >>> 24, edited >>> 24, preserveWeight, editWeight);
        int red = blendChannel((original >>> 16) & 0xff, (edited >>> 16) & 0xff,
                preserveWeight, editWeight);
        int green = blendChannel((original >>> 8) & 0xff, (edited >>> 8) & 0xff,
                preserveWeight, editWeight);
        int blue = blendChannel(original & 0xff, edited & 0xff, preserveWeight, editWeight);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int blendChannel(int original, int edited, int preserveWeight, int editWeight) {
        return (original * preserveWeight + edited * editWeight + 127) / 255;
    }

    private static boolean containsEditablePixel(BufferedImage mask) {
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                if ((mask.getRGB(x, y) >>> 24) < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw invalidResponse("Unable to encode masked image result as PNG");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw invalidResponse("Unable to encode masked image result as PNG");
        }
    }

    private static ModelProviderException invalidResponse(String message) {
        return new ModelProviderException("PROVIDER_INVALID_RESPONSE", message, false);
    }
}
