package com.aibox.features.image.enhance;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.ArtifactDrafts;
import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureHandler;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public final class ImageEnhanceFeatureHandler implements FeatureHandler {

    public static final String FEATURE_CODE = "image.enhance";
    static final String MODEL_ALIAS = "image.generation.default";
    static final long MAX_SOURCE_IMAGE_BYTES = 10L * 1024L * 1024L;
    static final int MAX_IMAGE_DIMENSION = 8_192;
    static final long MAX_IMAGE_PIXELS = 40_000_000L;
    static final long MAX_OUTPUT_IMAGE_BYTES = 50L * 1024L * 1024L;

    private static final String UPSCALE = "upscale";
    private static final String DEBLUR = "deblur";
    private static final String DENOISE = "denoise";
    private static final String OLD_PHOTO_RESTORE = "old_photo_restore";
    private static final Set<String> MODES = Set.of(
            UPSCALE, DEBLUR, DENOISE, OLD_PHOTO_RESTORE
    );
    private static final Set<String> SCALES = Set.of("2x", "4x");
    private static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".webp");

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        String mode = stringParameter(context, "mode");
        if (!MODES.contains(mode)) {
            throw new FeatureValidationException("mode", "请选择图片放大、去模糊、图片降噪或老照片修复");
        }
        String scale = stringParameter(context, "scale");
        if (UPSCALE.equals(mode) && !SCALES.contains(scale)) {
            throw new FeatureValidationException("scale", "请选择 2x 或 4x 放大倍率");
        }
        if (!UPSCALE.equals(mode) && !scale.isBlank()) {
            throw new FeatureValidationException("scale", "只有图片放大模式可以选择放大倍率");
        }
        boolean colorize = booleanParameter(context, "colorize");
        if (!OLD_PHOTO_RESTORE.equals(mode) && colorize) {
            throw new FeatureValidationException("colorize", "只有老照片修复可以启用黑白照片上色");
        }
        UUID sourceImageId = effectiveSourceImageId(context);
        if (!context.inputAssetIds().equals(List.of(sourceImageId))) {
            throw new FeatureValidationException("sourceImage", "请仅上传一张原始图片");
        }
        InputAssetReference source = requireInputAsset(context, sourceImageId);
        validateImage(source);
        targetDimensions(
                source,
                UPSCALE.equals(mode) ? scaleFactor(scale) : 1,
                UPSCALE.equals(mode)
        );
    }

    @Override
    public FeatureExecutionResult execute(FeatureExecutionContext context, ModelGateway modelGateway) {
        UUID sourceImageId = effectiveSourceImageId(context);
        InputAssetReference source = requireInputAsset(context, sourceImageId);
        String mode = stringParameter(context, "mode");
        boolean colorize = booleanParameter(context, "colorize");
        int factor = UPSCALE.equals(mode)
                ? scaleFactor(stringParameter(context, "scale"))
                : 1;
        Dimensions target = targetDimensions(source, factor, UPSCALE.equals(mode));

        ImageGenerationResponse normalized;
        int modelInvocationCount = 1;
        if (OLD_PHOTO_RESTORE.equals(mode)) {
            normalized = generateAndNormalize(
                    context,
                    modelGateway,
                    oldPhotoRestorePrompt(),
                    List.of(sourceImageId),
                    List.of(),
                    source,
                    target,
                    mode,
                    factor,
                    "enhance_old_photo_restore"
            );
            if (colorize) {
                GeneratedImage restored = normalized.images().get(0);
                normalized = generateAndNormalize(
                        context,
                        modelGateway,
                        oldPhotoColorizePrompt(),
                        List.of(),
                        List.of(new ModelAsset(
                                null,
                                "restored-old-photo.png",
                                restored.mediaType(),
                                restored.content()
                        )),
                        source,
                        target,
                        mode,
                        factor,
                        "enhance_old_photo_colorize"
                );
                modelInvocationCount = 2;
            }
        } else {
            normalized = generateAndNormalize(
                    context,
                    modelGateway,
                    prompt(mode, factor),
                    List.of(sourceImageId),
                    List.of(),
                    source,
                    target,
                    mode,
                    factor,
                    "enhance_" + mode
            );
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", mode);
        metadata.put("scaleFactor", factor);
        metadata.put("colorize", colorize);
        metadata.put("modelInvocationCount", modelInvocationCount);
        metadata.put("sourceWidth", source.width());
        metadata.put("sourceHeight", source.height());
        metadata.put("targetWidth", target.width());
        metadata.put("targetHeight", target.height());
        if (context.baseArtifact() != null) {
            metadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
            metadata.put("basedOnVersion", context.baseArtifact().versionNumber());
        }

        ArtifactDraft artifact = ArtifactDrafts.generatedImages(
                title(mode),
                normalized,
                metadata
        );
        return FeatureExecutionResult.of(artifact);
    }

    private static ImageGenerationResponse generateAndNormalize(
            FeatureExecutionContext context,
            ModelGateway modelGateway,
            String prompt,
            List<UUID> inputAssetIds,
            List<ModelAsset> inlineInputAssets,
            InputAssetReference source,
            Dimensions target,
            String mode,
            int factor,
            String providerInvocationKey
    ) {
        ImageGenerationResponse response = modelGateway.generateImage(new ImageGenerationRequest(
                context.tenantId(),
                context.runId(),
                MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.IMAGE_GENERATION),
                prompt,
                inputAssetIds,
                inlineInputAssets,
                preferredModelSize(source),
                1,
                Map.of(
                        "featureCode", FEATURE_CODE,
                        "runId", context.runId().toString(),
                        "mode", mode,
                        "scaleFactor", factor,
                        "sourceWidth", source.width(),
                        "sourceHeight", source.height(),
                        "targetWidth", target.width(),
                        "targetHeight", target.height(),
                        "outputFormat", "png",
                        "providerInvocationKey", providerInvocationKey
                )
        ));
        return normalizeResponse(response, target);
    }

    private static ImageGenerationResponse normalizeResponse(
            ImageGenerationResponse response,
            Dimensions target
    ) {
        if (response.images().size() != 1) {
            throw invalidResponse("图片模型必须且仅返回一张有效图片");
        }
        GeneratedImage generated = response.images().get(0);
        if (generated.content().length == 0 || generated.content().length > MAX_OUTPUT_IMAGE_BYTES) {
            throw invalidResponse("图片模型返回的图片大小无效");
        }
        BufferedImage decoded = decodeImage(generated);
        requireCompatibleOrientation(decoded, target);
        byte[] png = encodePng(fitToCanvas(decoded, target.width(), target.height()));
        if (png.length > MAX_OUTPUT_IMAGE_BYTES) {
            throw invalidResponse("修复结果超过 50 MB，无法保存");
        }
        return new ImageGenerationResponse(
                List.of(new GeneratedImage(
                        generated.sourceUrl(),
                        "image/png",
                        generated.revisedPrompt(),
                        png
                )),
                response.provider(),
                response.model(),
                response.providerRequestId(),
                response.inputUnits(),
                response.outputUnits()
        );
    }

    private static BufferedImage decodeImage(GeneratedImage image) {
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.content()));
            if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
                throw invalidResponse("图片模型返回了无法读取的图片");
            }
            if (decoded.getWidth() > MAX_IMAGE_DIMENSION
                    || decoded.getHeight() > MAX_IMAGE_DIMENSION
                    || (long) decoded.getWidth() * decoded.getHeight() > MAX_IMAGE_PIXELS) {
                throw invalidResponse("图片模型返回的图片尺寸过大");
            }
            return decoded;
        } catch (IOException exception) {
            throw invalidResponse("图片模型返回了无法读取的图片");
        }
    }

    private static void requireCompatibleOrientation(BufferedImage image, Dimensions target) {
        double actual = image.getWidth() / (double) image.getHeight();
        double expected = target.width() / (double) target.height();
        boolean expectedPortrait = expected < 0.9d;
        boolean expectedLandscape = expected > 1.1d;
        boolean actualPortrait = actual < 0.9d;
        boolean actualLandscape = actual > 1.1d;
        if ((expectedPortrait && actualLandscape) || (expectedLandscape && actualPortrait)) {
            throw invalidResponse("图片模型返回了与原图相反的横竖方向，无法生成保真结果");
        }
    }

    private static BufferedImage fitToCanvas(BufferedImage source, int width, int height) {
        int imageType = source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB;
        BufferedImage result = new BufferedImage(width, height, imageType);
        double scale = Math.min(
                width / (double) source.getWidth(),
                height / (double) source.getHeight()
        );
        int renderedWidth = Math.min(
                width,
                Math.max(1, (int) Math.round(source.getWidth() * scale))
        );
        int renderedHeight = Math.min(
                height,
                Math.max(1, (int) Math.round(source.getHeight() * scale))
        );
        int x = (width - renderedWidth) / 2;
        int y = (height - renderedHeight) / 2;

        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(source.getColorModel().hasAlpha()
                    ? new Color(0, 0, 0, 0)
                    : averageBorderColor(source));
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );
            graphics.drawImage(
                    source,
                    x,
                    y,
                    x + renderedWidth,
                    y + renderedHeight,
                    0,
                    0,
                    source.getWidth(),
                    source.getHeight(),
                    null
            );
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static Color averageBorderColor(BufferedImage image) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long count = 0;
        int lastX = image.getWidth() - 1;
        int lastY = image.getHeight() - 1;
        for (int x = 0; x <= lastX; x++) {
            int top = image.getRGB(x, 0);
            int bottom = image.getRGB(x, lastY);
            red += ((top >> 16) & 0xff) + ((bottom >> 16) & 0xff);
            green += ((top >> 8) & 0xff) + ((bottom >> 8) & 0xff);
            blue += (top & 0xff) + (bottom & 0xff);
            count += 2;
        }
        for (int y = 1; y < lastY; y++) {
            int left = image.getRGB(0, y);
            int right = image.getRGB(lastX, y);
            red += ((left >> 16) & 0xff) + ((right >> 16) & 0xff);
            green += ((left >> 8) & 0xff) + ((right >> 8) & 0xff);
            blue += (left & 0xff) + (right & 0xff);
            count += 2;
        }
        return new Color(
                (int) (red / count),
                (int) (green / count),
                (int) (blue / count)
        );
    }

    private static String preferredModelSize(InputAssetReference source) {
        double aspectRatio = source.width() / (double) source.height();
        if (aspectRatio < 0.75d) return "9:16";
        if (aspectRatio > 4d / 3d) return "16:9";
        if (aspectRatio >= 0.9d && aspectRatio <= 1.1d) return "1:1";
        return null;
    }

    private static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw invalidResponse("修复结果无法转换为 PNG");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw invalidResponse("修复结果无法转换为 PNG");
        }
    }

    private static Dimensions targetDimensions(
            InputAssetReference source,
            int factor,
            boolean upscale
    ) {
        long width = (long) source.width() * factor;
        long height = (long) source.height() * factor;
        long pixels = width * height;
        if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION || pixels > MAX_IMAGE_PIXELS) {
            if (!upscale) {
                throw new FeatureValidationException(
                        "sourceImage",
                        "原始图片最长边不能超过 8192 像素且总像素不能超过 4000 万"
                );
            }
            throw new FeatureValidationException(
                    "scale",
                    "放大后最长边不能超过 8192 像素且总像素不能超过 4000 万，请降低倍率或缩小原图"
            );
        }
        return new Dimensions((int) width, (int) height);
    }

    private static void validateImage(InputAssetReference asset) {
        String mediaType = asset.mediaType() == null
                ? ""
                : asset.mediaType().toLowerCase(Locale.ROOT);
        String fileName = asset.fileName() == null
                ? ""
                : asset.fileName().toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPES.contains(mediaType) || EXTENSIONS.stream().noneMatch(fileName::endsWith)) {
            throw new FeatureValidationException(
                    "sourceImage",
                    "原始图片仅支持 PNG、JPG、JPEG 和 WebP 格式"
            );
        }
        if (asset.sizeBytes() <= 0 || asset.sizeBytes() > MAX_SOURCE_IMAGE_BYTES) {
            throw new FeatureValidationException("sourceImage", "原始图片不能超过 10 MB");
        }
        if (asset.width() == null || asset.height() == null
                || asset.width() <= 0 || asset.height() <= 0) {
            throw new FeatureValidationException("sourceImage", "图片内容无法读取，请重新选择图片");
        }
    }

    private static InputAssetReference requireInputAsset(
            FeatureExecutionContext context,
            UUID assetId
    ) {
        if (context.inputAssets().size() != 1) {
            throw new FeatureValidationException("sourceImage", "图片信息不完整，请重新选择图片");
        }
        return context.inputAssets().stream()
                .filter(asset -> asset.id().equals(assetId))
                .findFirst()
                .orElseThrow(() -> new FeatureValidationException(
                        "sourceImage",
                        "图片信息不完整，请重新选择图片"
                ));
    }

    private static UUID requiredAssetParameter(FeatureExecutionContext context, String name) {
        Object value = context.parameters().get(name);
        if (value == null || value.toString().isBlank()) {
            throw new FeatureValidationException(name, "请上传原始图片");
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            throw new FeatureValidationException(name, "图片资源标识无效");
        }
    }

    private static UUID effectiveSourceImageId(FeatureExecutionContext context) {
        UUID submitted = requiredAssetParameter(context, "sourceImage");
        if (context.baseArtifact() == null) return submitted;
        validateBaseArtifact(context.baseArtifact());
        return baseAssetId(context.baseArtifact());
    }

    private static void validateBaseArtifact(ArtifactReference baseArtifact) {
        if (!"image".equals(baseArtifact.kind())
                && (baseArtifact.mimeType() == null || !baseArtifact.mimeType().startsWith("image/"))) {
            throw new FeatureValidationException("baseArtifactId", "只能基于图片成果继续修改");
        }
        baseAssetId(baseArtifact);
    }

    private static UUID baseAssetId(ArtifactReference baseArtifact) {
        Object primary = baseArtifact.content().get("assetId");
        if (primary != null && !primary.toString().isBlank()) {
            return parseAssetId("baseArtifactId", primary);
        }
        Object multiple = baseArtifact.content().get("assetIds");
        if (multiple instanceof List<?> items && !items.isEmpty()) {
            return parseAssetId("baseArtifactId", items.get(0));
        }
        throw new FeatureValidationException("baseArtifactId", "上一版图片成果缺少可复用的图片资源");
    }

    private static UUID parseAssetId(String field, Object value) {
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            throw new FeatureValidationException(field, "图片资源标识无效");
        }
    }

    private static int scaleFactor(String scale) {
        return "4x".equals(scale) ? 4 : 2;
    }

    private static String stringParameter(FeatureExecutionContext context, String name) {
        Object value = context.parameters().get(name);
        return value == null ? "" : value.toString().trim();
    }

    private static boolean booleanParameter(FeatureExecutionContext context, String name) {
        Object value = context.parameters().get(name);
        if (value == null) return false;
        if (value instanceof Boolean booleanValue) return booleanValue;
        if ("true".equalsIgnoreCase(value.toString())) return true;
        if ("false".equalsIgnoreCase(value.toString())) return false;
        throw new FeatureValidationException(name, "开关参数格式无效");
    }

    private static String title(String mode) {
        return switch (mode) {
            case DEBLUR -> "去模糊结果";
            case DENOISE -> "降噪结果";
            case OLD_PHOTO_RESTORE -> "老照片修复结果";
            default -> "清晰放大结果";
        };
    }

    private static String prompt(String mode, int factor) {
        return switch (mode) {
            case DEBLUR -> deblurPrompt();
            case DENOISE -> denoisePrompt();
            default -> upscalePrompt(factor);
        };
    }

    private static String upscalePrompt(int factor) {
        return """
                Enhance the first input image with high fidelity and upscale it to %d times its original
                width and height. Preserve the exact subject identity, composition, crop, geometry, colors,
                text, logos, facial features, and background. Reconstruct plausible fine details only where
                blur or low resolution removed information. Do not add, remove, move, restyle, or redesign
                anything. Keep exactly the original aspect ratio and return exactly one PNG image.
                """.formatted(factor);
    }

    private static String deblurPrompt() {
        return """
                Remove focus blur and motion blur from the first input image with high fidelity. Restore
                plausible edge and texture detail without changing the subject identity, composition, crop,
                geometry, colors, text, logos, facial features, or background. Avoid oversharpening, halos,
                plastic skin, invented objects, and style changes. Keep exactly the original dimensions and
                aspect ratio and return exactly one PNG image.
                """;
    }

    private static String denoisePrompt() {
        return """
                Remove sensor noise, grain, color noise, and compression artifacts from the first input image
                with high fidelity. Preserve real texture, fine edges, subject identity, composition, crop,
                colors, text, logos, facial features, and background. Avoid smoothing away natural detail,
                plastic skin, invented objects, and style changes. Keep exactly the original dimensions and
                aspect ratio and return exactly one PNG image.
                """;
    }

    private static String oldPhotoRestorePrompt() {
        return """
                Perform a dedicated restoration pass on the first input old photo. Remove visible scratches,
                cracks, folds, dust, stains, spots, paper damage, fading, excessive grain, noise,
                focus blur, and motion blur. Reconstruct plausible missing small regions, recover local contrast
                and sharp edges, and restore fine subject texture. Do not merely soften or hide the damage:
                remove it wherever the surrounding image provides enough evidence.

                Preserve the exact people, facial identity, pose, composition, crop, geometry, clothing,
                objects, text, background, and historical character. Do not modernize, restyle, add, remove,
                or move scene content. Preserve whether the source is monochrome or color. Do not colorize a
                black-and-white or sepia photograph during this restoration pass. Keep exactly the original
                dimensions and aspect ratio and return exactly one PNG image.
                """;
    }

    private static String oldPhotoColorizePrompt() {
        return """
                MANDATORY FULL-COLOR COLORIZATION: the first input is an already restored old photograph.
                Convert every recognizable part of the scene from black-and-white or sepia into believable,
                natural full color. Use multiple distinct, period-appropriate hues for skin, hair, clothing,
                vegetation, sky, ground, buildings, wood, metal, and other materials when present. Remove the
                monochrome or yellow-brown cast while preserving the original luminance, shadows, highlights,
                photographic texture, and historical atmosphere.

                Do not return grayscale, monochrome, sepia, duotone, or a merely tinted image. Do not change
                people, facial identity, composition, crop, geometry, text, objects, or background. Do not add,
                remove, move, modernize, or redesign anything. Keep exactly the original dimensions and aspect
                ratio and return exactly one PNG image.
                """;
    }

    private static ModelProviderException invalidResponse(String message) {
        return new ModelProviderException("PROVIDER_INVALID_RESPONSE", message, false);
    }

    private record Dimensions(int width, int height) {
    }
}
