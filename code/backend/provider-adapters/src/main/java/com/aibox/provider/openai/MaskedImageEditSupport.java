package com.aibox.provider.openai;

import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelProviderException;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MaskedImageEditSupport {

    private static final int MAX_PROVIDER_DIMENSION = 8_192;
    private static final Pattern SIZE_PATTERN = Pattern.compile("^(\\d{1,5})x(\\d{1,5})$");

    private MaskedImageEditSupport() {
    }

    static String orientationSizeKey(ModelAsset sourceAsset) {
        BufferedImage source = decode(sourceAsset.content(), "source image");
        if (source.getWidth() == source.getHeight()) return "1:1";
        return source.getWidth() > source.getHeight() ? "16:9" : "9:16";
    }

    static Prepared prepare(ModelAsset sourceAsset, ModelAsset maskAsset, String providerSize) {
        Dimensions dimensions = parseDimensions(providerSize);
        if (dimensions == null) {
            return new Prepared(sourceAsset, maskAsset, providerSize);
        }
        BufferedImage source = decode(sourceAsset.content(), "source image");
        BufferedImage mask = decode(maskAsset.content(), "mask image");
        if (source.getWidth() != mask.getWidth() || source.getHeight() != mask.getHeight()) {
            throw new ModelProviderException(
                    "MASK_DIMENSION_MISMATCH",
                    "The mask dimensions must match the source image",
                    false
            );
        }
        ModelAsset preparedSource = new ModelAsset(
                sourceAsset.id(),
                "masked-edit-source.png",
                "image/png",
                encodePng(resize(source, dimensions.width(), dimensions.height()))
        );
        ModelAsset preparedMask = new ModelAsset(
                maskAsset.id(),
                "masked-edit-mask.png",
                "image/png",
                encodePng(resize(mask, dimensions.width(), dimensions.height()))
        );
        return new Prepared(preparedSource, preparedMask, providerSize);
    }

    private static Dimensions parseDimensions(String size) {
        if (size == null) return null;
        Matcher matcher = SIZE_PATTERN.matcher(size.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) return null;
        int width = Integer.parseInt(matcher.group(1));
        int height = Integer.parseInt(matcher.group(2));
        if (width <= 0 || height <= 0
                || width > MAX_PROVIDER_DIMENSION || height > MAX_PROVIDER_DIMENSION) {
            return null;
        }
        return new Dimensions(width, height);
    }

    private static BufferedImage decode(byte[] content, String label) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw invalidInput("Unable to decode masked edit " + label);
            }
            return image;
        } catch (IOException exception) {
            throw invalidInput("Unable to decode masked edit " + label);
        }
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
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

    private static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw invalidInput("Unable to encode masked edit input as PNG");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw invalidInput("Unable to encode masked edit input as PNG");
        }
    }

    private static ModelProviderException invalidInput(String message) {
        return new ModelProviderException("MASK_INPUT_INVALID", message, false);
    }

    record Prepared(ModelAsset source, ModelAsset mask, String providerSize) {
    }

    private record Dimensions(int width, int height) {
    }
}
