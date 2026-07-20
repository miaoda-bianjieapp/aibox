package com.aibox.provider.openai;

import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.ImagePreservationMode;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelProviderException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

final class ImageExpansionSupport {

    static final int MIN_TOTAL_PIXELS = 655_360;
    static final int MAX_TOTAL_PIXELS = 8_294_400;
    static final int MAX_EDGE = 3_840;
    static final int DIMENSION_MULTIPLE = 16;
    static final int MAX_PROVIDER_FILE_BYTES = 50 * 1024 * 1024;
    static final Limits GPT_IMAGE_2_LIMITS = new Limits(
            MIN_TOTAL_PIXELS,
            MAX_TOTAL_PIXELS,
            MAX_EDGE,
            MAX_PROVIDER_FILE_BYTES,
            1
    );

    private ImageExpansionSupport() {
    }

    static PreparedExpansion prepare(ModelAsset asset, String aspectRatio) {
        return prepare(asset, aspectRatio, 1.0, GPT_IMAGE_2_LIMITS);
    }

    static PreparedExpansion prepare(
            ModelAsset asset,
            String aspectRatio,
            double expansionScale,
            Limits limits
    ) {
        return prepare(asset, aspectRatio, expansionScale, limits, null);
    }

    static PreparedExpansion prepare(
            ModelAsset asset,
            String aspectRatio,
            double expansionScale,
            Limits limits,
            String providerSize
    ) {
        return prepare(
                asset,
                aspectRatio,
                expansionScale,
                limits,
                providerSize,
                false
        );
    }

    static PreparedExpansion prepare(
            ModelAsset asset,
            String aspectRatio,
            double expansionScale,
            Limits limits,
            String providerSize,
            boolean scaleFromSourceDimensions
    ) {
        BufferedImage source = decodeSource(asset.content(), limits);
        Ratio ratio = parseRatio(aspectRatio);
        CanvasSize canvas = scaleFromSourceDimensions
                ? resolveSourceScaleCanvas(
                        source.getWidth(),
                        source.getHeight(),
                        expansionScale,
                        limits
                )
                : resolveCanvas(
                        source.getWidth(),
                        source.getHeight(),
                        ratio,
                        expansionScale,
                        limits
        );
        int offsetX = (canvas.width() - source.getWidth()) / 2;
        int offsetY = (canvas.height() - source.getHeight()) / 2;
        int canvasDivisor = gcd(canvas.width(), canvas.height());
        Ratio canvasRatio = new Ratio(
                canvas.width() / canvasDivisor,
                canvas.height() / canvasDivisor
        );
        ProviderCanvas providerCanvas =
                resolveProviderCanvas(canvas, canvasRatio, providerSize);
        double providerScaleX = (double) providerCanvas.cropWidth() / canvas.width();
        double providerScaleY = (double) providerCanvas.cropHeight() / canvas.height();
        int providerSourceX = providerCanvas.cropX() + scaledBoundary(offsetX, providerScaleX);
        int providerSourceY = providerCanvas.cropY() + scaledBoundary(offsetY, providerScaleY);
        int providerSourceRight = providerCanvas.cropX()
                + scaledBoundary(offsetX + source.getWidth(), providerScaleX);
        int providerSourceBottom = providerCanvas.cropY()
                + scaledBoundary(offsetY + source.getHeight(), providerScaleY);
        int providerSourceWidth = Math.max(1, providerSourceRight - providerSourceX);
        int providerSourceHeight = Math.max(1, providerSourceBottom - providerSourceY);

        BufferedImage input = new BufferedImage(
                providerCanvas.width(),
                providerCanvas.height(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D inputGraphics = input.createGraphics();
        try {
            inputGraphics.setComposite(AlphaComposite.Src);
            applyHighQualityScaling(inputGraphics);
            inputGraphics.drawImage(
                    source,
                    providerSourceX,
                    providerSourceY,
                    providerSourceX + providerSourceWidth,
                    providerSourceY + providerSourceHeight,
                    0,
                    0,
                    source.getWidth(),
                    source.getHeight(),
                    null
            );
        } finally {
            inputGraphics.dispose();
        }

        BufferedImage mask = new BufferedImage(
                providerCanvas.width(),
                providerCanvas.height(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D maskGraphics = mask.createGraphics();
        try {
            maskGraphics.setComposite(AlphaComposite.Src);
            maskGraphics.setColor(new Color(0, 0, 0, 255));
            maskGraphics.fillRect(
                    providerSourceX,
                    providerSourceY,
                    providerSourceWidth,
                    providerSourceHeight
            );
        } finally {
            maskGraphics.dispose();
        }

        byte[] inputPng = encodePng(input);
        byte[] maskPng = encodePng(mask);
        if (inputPng.length > limits.maxUploadBytes() || maskPng.length > limits.maxUploadBytes()) {
            throw new ModelProviderException(
                    "IMAGE_EXPANSION_PAYLOAD_TOO_LARGE",
                    "扩图画布超过模型服务允许的文件大小",
                    false
            );
        }
        return new PreparedExpansion(
                source,
                canvas.width(),
                canvas.height(),
                offsetX,
                offsetY,
                providerCanvas.width(),
                providerCanvas.height(),
                providerCanvas.cropX(),
                providerCanvas.cropY(),
                providerCanvas.cropWidth(),
                providerCanvas.cropHeight(),
                inputPng,
                maskPng
        );
    }

    static GeneratedImage finalizeImage(
            GeneratedImage generated,
            PreparedExpansion prepared,
            ImagePreservationMode preservationMode
    ) {
        BufferedImage output = decode(
                generated.content(),
                "PROVIDER_INVALID_RESPONSE",
                "图片模型返回了无法解码的扩图结果"
        );
        CropRegion outputCrop = resolveCenteredCrop(
                output.getWidth(),
                output.getHeight(),
                prepared.targetWidth(),
                prepared.targetHeight()
        );
        BufferedImage finalized = new BufferedImage(
                prepared.targetWidth(),
                prepared.targetHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D resizeGraphics = finalized.createGraphics();
        try {
            resizeGraphics.setComposite(AlphaComposite.Src);
            applyHighQualityScaling(resizeGraphics);
            resizeGraphics.drawImage(
                    output,
                    0,
                    0,
                    prepared.targetWidth(),
                    prepared.targetHeight(),
                    outputCrop.x(),
                    outputCrop.y(),
                    outputCrop.x() + outputCrop.width(),
                    outputCrop.y() + outputCrop.height(),
                    null
            );
        } finally {
            resizeGraphics.dispose();
        }
        if (preservationMode == ImagePreservationMode.STRICT) {
            Graphics2D graphics = finalized.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.drawImage(prepared.source(), prepared.offsetX(), prepared.offsetY(), null);
            } finally {
                graphics.dispose();
            }
        }
        return new GeneratedImage(
                generated.sourceUrl(),
                "image/png",
                generated.revisedPrompt(),
                encodePng(finalized)
        );
    }

    private static CropRegion resolveCenteredCrop(
            int canvasWidth,
            int canvasHeight,
            int targetWidth,
            int targetHeight
    ) {
        return centeredCrop(canvasWidth, canvasHeight, targetWidth, targetHeight);
    }

    static String orientationSizeKey(String aspectRatio) {
        Ratio ratio = parseRatio(aspectRatio);
        if (ratio.width() == ratio.height()) return "1:1";
        return ratio.width() > ratio.height() ? "16:9" : "9:16";
    }

    static String resolveAspectRatio(
            ModelAsset asset,
            String requestedAspectRatio,
            Limits limits
    ) {
        Ratio ratio;
        if ("source".equalsIgnoreCase(requestedAspectRatio)) {
            BufferedImage source = decodeSource(asset.content(), limits);
            int divisor = gcd(source.getWidth(), source.getHeight());
            ratio = new Ratio(source.getWidth() / divisor, source.getHeight() / divisor);
        } else {
            ratio = parseRatio(requestedAspectRatio);
        }
        return ratio.width() + ":" + ratio.height();
    }

    private static CanvasSize resolveCanvas(
            int sourceWidth,
            int sourceHeight,
            Ratio ratio,
            double expansionScale,
            Limits limits
    ) {
        int firstScale = Math.max(ceilDiv(sourceWidth, ratio.width()), ceilDiv(sourceHeight, ratio.height()));
        int maxScale = Math.min(limits.maxEdge() / ratio.width(), limits.maxEdge() / ratio.height());
        int minimumValidScale = findValidScale(firstScale, maxScale, ratio, limits);
        int expandedScale = Math.max(
                minimumValidScale,
                (int) Math.ceil(minimumValidScale * expansionScale)
        );
        int resolvedScale = findValidScale(expandedScale, maxScale, ratio, limits);
        return new CanvasSize(ratio.width() * resolvedScale, ratio.height() * resolvedScale);
    }

    private static CanvasSize resolveSourceScaleCanvas(
            int sourceWidth,
            int sourceHeight,
            double expansionScale,
            Limits limits
    ) {
        int width = alignedScaledDimension(
                sourceWidth,
                expansionScale,
                limits.dimensionMultiple(),
                limits.maxEdge()
        );
        int height = alignedScaledDimension(
                sourceHeight,
                expansionScale,
                limits.dimensionMultiple(),
                limits.maxEdge()
        );
        long totalPixels = (long) width * height;
        if (totalPixels < limits.minPixels()) {
            double minimumScale = Math.sqrt((double) limits.minPixels() / totalPixels);
            width = alignedScaledDimension(
                    width,
                    minimumScale,
                    limits.dimensionMultiple(),
                    limits.maxEdge()
            );
            height = alignedScaledDimension(
                    height,
                    minimumScale,
                    limits.dimensionMultiple(),
                    limits.maxEdge()
            );
            totalPixels = (long) width * height;
        }
        if (width > limits.maxEdge()
                || height > limits.maxEdge()
                || totalPixels > limits.maxPixels()) {
            throw unsupportedScale();
        }
        return new CanvasSize(width, height);
    }

    private static int findValidScale(int startScale, int maxScale, Ratio ratio, Limits limits) {
        for (int scale = startScale; scale <= maxScale; scale++) {
            int width = ratio.width() * scale;
            int height = ratio.height() * scale;
            long totalPixels = (long) width * height;
            if (width % limits.dimensionMultiple() != 0
                    || height % limits.dimensionMultiple() != 0) {
                continue;
            }
            if (totalPixels < limits.minPixels() || totalPixels > limits.maxPixels()) continue;
            return scale;
        }
        throw unsupportedScale();
    }

    private static int alignedScaledDimension(
            int value,
            double scale,
            int multiple,
            int maxEdge
    ) {
        double scaled = value * scale;
        if (!Double.isFinite(scaled) || scaled > maxEdge) {
            throw unsupportedScale();
        }
        int rounded = Math.max(value, (int) Math.ceil(scaled));
        int remainder = rounded % multiple;
        int aligned = remainder == 0 ? rounded : rounded + multiple - remainder;
        if (aligned > maxEdge) {
            throw unsupportedScale();
        }
        return aligned;
    }

    private static ModelProviderException unsupportedScale() {
        return new ModelProviderException(
                "IMAGE_EXPANSION_SCALE_UNSUPPORTED",
                "当前原图、目标比例和扩展倍数超出所选模型的尺寸限制",
                false
        );
    }

    private static ProviderCanvas resolveProviderCanvas(
            CanvasSize target,
            Ratio ratio,
            String providerSize
    ) {
        if (providerSize == null || providerSize.isBlank()) {
            return new ProviderCanvas(
                    target.width(),
                    target.height(),
                    0,
                    0,
                    target.width(),
                    target.height()
            );
        }
        CanvasSize provider = parseProviderSize(providerSize);
        CropRegion crop = centeredCrop(
                provider.width(),
                provider.height(),
                ratio.width(),
                ratio.height()
        );
        return new ProviderCanvas(
                provider.width(),
                provider.height(),
                crop.x(),
                crop.y(),
                crop.width(),
                crop.height()
        );
    }

    private static CropRegion centeredCrop(
            int canvasWidth,
            int canvasHeight,
            int targetWidth,
            int targetHeight
    ) {
        if (canvasWidth <= 0 || canvasHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            throw new ModelProviderException(
                    "PROVIDER_INVALID_RESPONSE",
                    "图片模型返回的扩图尺寸无效",
                    false
            );
        }
        double targetAspect = (double) targetWidth / targetHeight;
        double canvasAspect = (double) canvasWidth / canvasHeight;
        int cropWidth;
        int cropHeight;
        if (canvasAspect >= targetAspect) {
            cropHeight = canvasHeight;
            cropWidth = Math.max(1, (int) Math.round(cropHeight * targetAspect));
        } else {
            cropWidth = canvasWidth;
            cropHeight = Math.max(1, (int) Math.round(cropWidth / targetAspect));
        }
        cropWidth = Math.min(cropWidth, canvasWidth);
        cropHeight = Math.min(cropHeight, canvasHeight);
        return new CropRegion(
                (canvasWidth - cropWidth) / 2,
                (canvasHeight - cropHeight) / 2,
                cropWidth,
                cropHeight
        );
    }

    private static CanvasSize parseProviderSize(String value) {
        String[] parts = value == null
                ? new String[0]
                : value.toLowerCase().split("x", -1);
        if (parts.length != 2) {
            throw invalidProviderSize();
        }
        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            if (width <= 0 || height <= 0) throw invalidProviderSize();
            return new CanvasSize(width, height);
        } catch (NumberFormatException exception) {
            throw invalidProviderSize();
        }
    }

    private static ModelProviderException invalidProviderSize() {
        return new ModelProviderException(
                "IMAGE_EXPANSION_PROVIDER_SIZE_INVALID",
                "扩图供应商请求尺寸配置无效",
                false
        );
    }

    private static Ratio parseRatio(String value) {
        String[] parts = value == null ? new String[0] : value.split(":", -1);
        if (parts.length != 2) {
            throw invalidRatio();
        }
        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            if (width <= 0 || height <= 0) throw invalidRatio();
            int divisor = gcd(width, height);
            return new Ratio(width / divisor, height / divisor);
        } catch (NumberFormatException exception) {
            throw invalidRatio();
        }
    }

    private static ModelProviderException invalidRatio() {
        return new ModelProviderException(
                "IMAGE_ASPECT_RATIO_INVALID",
                "目标比例格式无效",
                false
        );
    }

    private static BufferedImage decode(byte[] content, String code, String message) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new ModelProviderException(code, message, false);
            }
            return image;
        } catch (IOException exception) {
            throw new ModelProviderException(code, message, false, exception);
        }
    }

    private static BufferedImage decodeSource(byte[] content, Limits limits) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new ModelProviderException(
                        "IMAGE_DECODE_FAILED", "原图无法解码，请重新选择图片", false
                );
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ModelProviderException(
                        "IMAGE_DECODE_FAILED", "原图无法解码，请重新选择图片", false
                );
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                        || width > limits.maxEdge()
                        || height > limits.maxEdge()
                        || (long) width * height > limits.maxPixels()) {
                    throw new ModelProviderException(
                            "IMAGE_DIMENSIONS_UNSUPPORTED",
                            "原图尺寸超出模型支持范围",
                            false
                    );
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new ModelProviderException(
                            "IMAGE_DECODE_FAILED", "原图无法解码，请重新选择图片", false
                    );
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new ModelProviderException(
                    "IMAGE_DECODE_FAILED",
                    "原图无法解码，请重新选择图片",
                    false,
                    exception
            );
        }
    }

    private static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG writer is unavailable");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ModelProviderException(
                    "IMAGE_PROCESSING_FAILED",
                    "扩图画布处理失败",
                    false,
                    exception
            );
        }
    }

    private static void applyHighQualityScaling(Graphics2D graphics) {
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
        );
        graphics.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
        );
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );
    }

    private static int scaledBoundary(int value, double scale) {
        return (int) Math.round(value * scale);
    }

    private static int ceilDiv(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    private static int gcd(int left, int right) {
        int a = left;
        int b = right;
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    record PreparedExpansion(
            BufferedImage source,
            int targetWidth,
            int targetHeight,
            int offsetX,
            int offsetY,
            int providerWidth,
            int providerHeight,
            int providerCropX,
            int providerCropY,
            int providerCropWidth,
            int providerCropHeight,
            byte[] inputPng,
            byte[] maskPng
    ) {
        PreparedExpansion {
            inputPng = inputPng.clone();
            maskPng = maskPng.clone();
        }

        @Override
        public byte[] inputPng() {
            return inputPng.clone();
        }

        @Override
        public byte[] maskPng() {
            return maskPng.clone();
        }

        String size() {
            return providerWidth + "x" + providerHeight;
        }

        String dashScopeSize() {
            return providerWidth + "*" + providerHeight;
        }
    }

    record Limits(
            int minPixels,
            int maxPixels,
            int maxEdge,
            int maxUploadBytes,
            int dimensionMultiple
    ) {
        Limits(int minPixels, int maxPixels, int maxEdge, int maxUploadBytes) {
            this(minPixels, maxPixels, maxEdge, maxUploadBytes, DIMENSION_MULTIPLE);
        }

        Limits {
            if (minPixels <= 0
                    || maxPixels < minPixels
                    || maxEdge <= 0
                    || maxUploadBytes <= 0
                    || dimensionMultiple <= 0) {
                throw new IllegalArgumentException("Invalid image expansion limits");
            }
        }
    }

    private record CanvasSize(int width, int height) {
    }

    private record ProviderCanvas(
            int width,
            int height,
            int cropX,
            int cropY,
            int cropWidth,
            int cropHeight
    ) {
    }

    private record CropRegion(int x, int y, int width, int height) {
    }

    private record Ratio(int width, int height) {
    }
}
