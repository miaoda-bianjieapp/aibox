package com.aibox.features.image.backgroundedit;

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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public final class BackgroundEditFeatureHandler implements FeatureHandler {

    public static final String FEATURE_CODE = "image.background_edit";
    static final String MODEL_ALIAS = "image.generation.default";
    static final int MAX_BACKGROUND_DESCRIPTION_LENGTH = 500;
    static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    static final long MAX_INPUT_BYTES = 20L * 1024L * 1024L;
    static final int MAX_IMAGE_DIMENSION = 8_192;
    static final long MAX_IMAGE_PIXELS = 40_000_000L;
    static final long MAX_OUTPUT_IMAGE_BYTES = 50L * 1024L * 1024L;

    private static final String REMOVE_BACKGROUND = "remove_background";
    private static final String REPLACE_BACKGROUND = "replace_background";
    private static final Set<String> MODES = Set.of(REMOVE_BACKGROUND, REPLACE_BACKGROUND);
    private static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg");
    private static final Set<String> EXTENSIONS = Set.of(".png", ".jpg", ".jpeg");

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        String mode = stringParameter(context, "mode");
        if (!MODES.contains(mode)) {
            throw new FeatureValidationException("mode", "请选择抠图或换背景");
        }

        UUID sourceImageId = effectiveSourceImageId(context);
        UUID backgroundImageId = optionalAssetParameter(context, "backgroundImage");
        String backgroundDescription = stringParameter(context, "backgroundDescription");
        if (backgroundDescription.length() > MAX_BACKGROUND_DESCRIPTION_LENGTH) {
            throw new FeatureValidationException("backgroundDescription", "背景描述不能超过 500 个字符");
        }
        if (REPLACE_BACKGROUND.equals(mode)
                && backgroundImageId == null
                && backgroundDescription.isBlank()) {
            throw new FeatureValidationException(
                    "backgroundDescription",
                    "换背景时请上传背景参考图或填写背景描述"
            );
        }
        if (REMOVE_BACKGROUND.equals(mode)
                && (backgroundImageId != null || !backgroundDescription.isBlank())) {
            throw new FeatureValidationException("mode", "抠图模式不接受背景参考图或背景描述");
        }

        List<UUID> expectedAssets = new ArrayList<>();
        expectedAssets.add(sourceImageId);
        if (backgroundImageId != null) expectedAssets.add(backgroundImageId);
        if (!context.inputAssetIds().equals(expectedAssets)) {
            throw new FeatureValidationException(
                    "inputAssetIds",
                    "图片顺序必须是第一张主体图、第二张背景参考图"
            );
        }
        validateInputAssets(context, expectedAssets);
    }

    @Override
    public FeatureExecutionResult execute(FeatureExecutionContext context, ModelGateway modelGateway) {
        String mode = stringParameter(context, "mode");
        UUID sourceImageId = effectiveSourceImageId(context);
        UUID backgroundImageId = optionalAssetParameter(context, "backgroundImage");
        String backgroundDescription = stringParameter(context, "backgroundDescription");
        List<UUID> orderedImages = backgroundImageId == null
                ? List.of(sourceImageId)
                : List.of(sourceImageId, backgroundImageId);
        InputAssetReference sourceImage = requireInputAsset(context, sourceImageId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", mode);
        metadata.put("preserveSourceDimensions", true);
        metadata.put("backgroundReferenceUsed", backgroundImageId != null);
        metadata.put("backgroundDescriptionUsed", !backgroundDescription.isBlank());
        if (context.baseArtifact() != null) {
            metadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
            metadata.put("basedOnVersion", context.baseArtifact().versionNumber());
        }

        ImageGenerationResponse normalized;
        if (REMOVE_BACKGROUND.equals(mode)) {
            CutoutGeneration cutout = generateCutout(
                    context,
                    modelGateway,
                    sourceImage
            );
            normalized = cutout.response();
            metadata.put("alphaExtraction", "black_white_difference");
            metadata.put("modelInvocationCount", 2);
            putIfPresent(metadata, "whitePassRequestId", cutout.whitePassRequestId());
            putIfPresent(metadata, "blackPassRequestId", cutout.blackPassRequestId());
        } else {
            Map<String, Object> requestMetadata = requestMetadata(
                    context,
                    mode,
                    sourceImage,
                    orderedImages.size(),
                    "replace_background"
            );
            ImageGenerationResponse response = modelGateway.generateImage(new ImageGenerationRequest(
                    context.tenantId(),
                    context.runId(),
                    MODEL_ALIAS,
                    context.selectedModelCode(ModelCapability.IMAGE_GENERATION),
                    replaceBackgroundPrompt(backgroundImageId != null, backgroundDescription),
                    orderedImages,
                    null,
                    1,
                    requestMetadata
            ));
            normalized = normalizeSingleImageResponse(
                    response,
                    sourceImage.width(),
                    sourceImage.height()
            );
        }

        String title = REMOVE_BACKGROUND.equals(mode) ? "透明背景抠图" : "换背景结果";
        ArtifactDraft artifact = ArtifactDrafts.generatedImages(title, normalized, metadata);
        return FeatureExecutionResult.of(artifact);
    }

    private static String replaceBackgroundPrompt(boolean hasBackgroundImage, String description) {
        String common = """
                Keep the subject identity, shape, colors, fine edges, hair, clothing, and small details unchanged.
                Keep exactly the same canvas dimensions and aspect ratio as the first input image.
                Return exactly one PNG image. Do not add borders, captions, logos, or extra subjects.
                The first input image is always the subject image.
                """;
        String reference = hasBackgroundImage
                ? "The second input image is the background reference. Use it as the replacement background.\n"
                : "";
        String requirements = description.isBlank()
                ? ""
                : "Additional background requirements:\n" + description + "\n";
        return common + """
                Replace only the background and keep the foreground subject unchanged.
                Match lighting, perspective, color temperature, edge blending, and natural subject shadows.
                """ + reference + requirements;
    }

    private static CutoutGeneration generateCutout(
            FeatureExecutionContext context,
            ModelGateway modelGateway,
            InputAssetReference sourceImage
    ) {
        ImageGenerationResponse whiteResponse = modelGateway.generateImage(new ImageGenerationRequest(
                context.tenantId(),
                context.runId(),
                MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.IMAGE_GENERATION),
                whiteBackgroundPrompt(),
                List.of(sourceImage.id()),
                null,
                1,
                requestMetadata(context, REMOVE_BACKGROUND, sourceImage, 1, "alpha_white")
        ));
        GeneratedImage whiteImage = requireSingleImage(whiteResponse, "白底抠图");
        BufferedImage whitePrepared = flattenOnBackground(
                decodeImage(whiteImage, "白底抠图"),
                0xFFFFFF
        );
        ModelAsset whiteReference = new ModelAsset(
                UUID.randomUUID(),
                "cutout-white-background.png",
                "image/png",
                encodePng(whitePrepared)
        );

        ImageGenerationResponse blackResponse = modelGateway.generateImage(new ImageGenerationRequest(
                context.tenantId(),
                context.runId(),
                MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.IMAGE_GENERATION),
                blackBackgroundPrompt(),
                List.of(),
                List.of(whiteReference),
                null,
                1,
                requestMetadata(context, REMOVE_BACKGROUND, sourceImage, 1, "alpha_black")
        ));
        ImageGenerationResponse extracted = extractAlphaResponse(
                whiteResponse,
                whitePrepared,
                blackResponse,
                sourceImage.width(),
                sourceImage.height()
        );
        return new CutoutGeneration(
                extracted,
                whiteResponse.providerRequestId(),
                blackResponse.providerRequestId()
        );
    }

    private static Map<String, Object> requestMetadata(
            FeatureExecutionContext context,
            String mode,
            InputAssetReference sourceImage,
            int referenceImageCount,
            String invocationKey
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("featureCode", FEATURE_CODE);
        metadata.put("runId", context.runId().toString());
        metadata.put("mode", mode);
        metadata.put("outputFormat", "png");
        metadata.put("preserveSourceDimensions", true);
        metadata.put("sourceWidth", sourceImage.width());
        metadata.put("sourceHeight", sourceImage.height());
        metadata.put("referenceImageCount", referenceImageCount);
        metadata.put("providerInvocationKey", invocationKey);
        return metadata;
    }

    private static String whiteBackgroundPrompt() {
        return """
                Remove the entire background from the first input image.
                Preserve the foreground subject exactly: identity, pose, position, scale, colors, hair,
                clothing, fine edges, semi-transparent details, and every attached foreground element.
                Replace every removed background pixel with perfectly uniform solid white #FFFFFF.
                The white background must be fully opaque with no shadows, gradients, reflections,
                textures, checkerboards, borders, captions, logos, or extra subjects.
                Keep exactly the same canvas dimensions and aspect ratio. Return exactly one PNG image.
                """;
    }

    private static String blackBackgroundPrompt() {
        return """
                This input is the white-background version prepared in the previous step.
                Change only the uniform white #FFFFFF background to uniform solid black #000000.
                Keep every foreground pixel, edge, color, position, scale, and canvas dimension identical.
                Do not redraw, restyle, move, resize, sharpen, soften, or otherwise modify the subject.
                Return exactly one fully opaque PNG image with no shadows, gradients, textures,
                checkerboards, borders, captions, logos, or extra subjects.
                """;
    }

    private static ImageGenerationResponse extractAlphaResponse(
            ImageGenerationResponse whiteResponse,
            BufferedImage white,
            ImageGenerationResponse blackResponse,
            int targetWidth,
            int targetHeight
    ) {
        GeneratedImage blackSource = requireSingleImage(blackResponse, "黑底抠图");
        BufferedImage black = flattenOnBackground(
                decodeImage(blackSource, "黑底抠图"),
                0x000000
        );
        if (white.getWidth() != black.getWidth() || white.getHeight() != black.getHeight()) {
            throw invalidResponse("黑白背景图片尺寸不一致，无法计算透明通道");
        }

        BufferedImage extracted = BlackWhiteAlphaExtractor.extract(white, black);
        if (!hasTransparentPixel(extracted)) {
            throw invalidResponse("黑白双图差分未得到透明像素");
        }
        BufferedImage normalized = resizeToTarget(extracted, targetWidth, targetHeight);
        byte[] png = encodePng(normalized);
        return new ImageGenerationResponse(
                List.of(new GeneratedImage(
                        blackSource.sourceUrl(),
                        "image/png",
                        blackSource.revisedPrompt(),
                        png
                )),
                blackResponse.provider(),
                blackResponse.model(),
                blackResponse.providerRequestId(),
                sumUnits(whiteResponse.inputUnits(), blackResponse.inputUnits()),
                sumUnits(whiteResponse.outputUnits(), blackResponse.outputUnits())
        );
    }

    private static ImageGenerationResponse normalizeSingleImageResponse(
            ImageGenerationResponse response,
            int targetWidth,
            int targetHeight
    ) {
        GeneratedImage source = requireSingleImage(response, "图片模型");
        BufferedImage decoded = decodeImage(source, "图片模型");
        byte[] png = encodePng(resizeToTarget(decoded, targetWidth, targetHeight));
        return new ImageGenerationResponse(
                List.of(new GeneratedImage(
                        source.sourceUrl(),
                        "image/png",
                        source.revisedPrompt(),
                        png
                )),
                response.provider(),
                response.model(),
                response.providerRequestId(),
                response.inputUnits(),
                response.outputUnits()
        );
    }

    private static GeneratedImage requireSingleImage(ImageGenerationResponse response, String label) {
        if (response.images().size() != 1) {
            throw invalidResponse(label + "必须且仅返回一张有效图片");
        }
        GeneratedImage image = response.images().get(0);
        if (image.content().length == 0 || image.content().length > MAX_OUTPUT_IMAGE_BYTES) {
            throw invalidResponse(label + "返回的图片大小无效");
        }
        return image;
    }

    private static BufferedImage decodeImage(GeneratedImage image, String label) {
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(image.content()));
        } catch (IOException exception) {
            throw invalidResponse(label + "返回了无法读取的图片");
        }
        if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
            throw invalidResponse(label + "返回了无效图片");
        }
        if (decoded.getWidth() > MAX_IMAGE_DIMENSION || decoded.getHeight() > MAX_IMAGE_DIMENSION
                || (long) decoded.getWidth() * decoded.getHeight() > MAX_IMAGE_PIXELS) {
            throw invalidResponse(label + "返回的图片尺寸过大");
        }
        return decoded;
    }

    private static BufferedImage resizeToTarget(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        double scale = Math.max(
                targetWidth / (double) source.getWidth(),
                targetHeight / (double) source.getHeight()
        );
        int drawWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        int drawX = (targetWidth - drawWidth) / 2;
        int drawY = (targetHeight - drawHeight) / 2;
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );
            graphics.drawImage(source, drawX, drawY, drawWidth, drawHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static BufferedImage flattenOnBackground(BufferedImage source, int backgroundRgb) {
        BufferedImage result = new BufferedImage(
                source.getWidth(),
                source.getHeight(),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(backgroundRgb));
            graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw invalidResponse("图片结果无法转换为 PNG");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw invalidResponse("图片结果无法转换为 PNG");
        }
    }

    private static boolean hasTransparentPixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) < 255) return true;
            }
        }
        return false;
    }

    private static Integer sumUnits(Integer first, Integer second) {
        if (first == null && second == null) return null;
        return (first == null ? 0 : first) + (second == null ? 0 : second);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private record CutoutGeneration(
            ImageGenerationResponse response,
            String whitePassRequestId,
            String blackPassRequestId
    ) {
    }

    private static void validateInputAssets(FeatureExecutionContext context, List<UUID> expectedAssets) {
        if (context.inputAssets().size() != expectedAssets.size()) {
            throw new FeatureValidationException("inputAssetIds", "图片信息不完整，请重新选择图片");
        }
        Map<UUID, InputAssetReference> assetsById = new LinkedHashMap<>();
        context.inputAssets().forEach(asset -> assetsById.put(asset.id(), asset));
        long totalBytes = 0;
        for (UUID id : expectedAssets) {
            InputAssetReference asset = assetsById.get(id);
            if (asset == null) {
                throw new FeatureValidationException("inputAssetIds", "图片信息不完整，请重新选择图片");
            }
            validateImage(asset);
            totalBytes += asset.sizeBytes();
        }
        if (totalBytes > MAX_INPUT_BYTES) {
            throw new FeatureValidationException("inputAssetIds", "输入图片总大小不能超过 20 MB");
        }
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
                    "inputAssetIds",
                    "图片仅支持 PNG、JPG 和 JPEG 格式"
            );
        }
        if (asset.sizeBytes() <= 0 || asset.sizeBytes() > MAX_IMAGE_BYTES) {
            throw new FeatureValidationException("inputAssetIds", "单张图片不能超过 10 MB");
        }
        if (asset.width() == null || asset.height() == null
                || asset.width() <= 0 || asset.height() <= 0) {
            throw new FeatureValidationException("inputAssetIds", "图片内容无法读取，请重新选择图片");
        }
        if (asset.width() > MAX_IMAGE_DIMENSION || asset.height() > MAX_IMAGE_DIMENSION
                || (long) asset.width() * asset.height() > MAX_IMAGE_PIXELS) {
            throw new FeatureValidationException(
                    "inputAssetIds",
                    "图片最长边不能超过 8192 像素且总像素不能超过 4000 万"
            );
        }
    }

    private static InputAssetReference requireInputAsset(
            FeatureExecutionContext context,
            UUID assetId
    ) {
        return context.inputAssets().stream()
                .filter(asset -> asset.id().equals(assetId))
                .findFirst()
                .orElseThrow(() -> new FeatureValidationException(
                        "inputAssetIds",
                        "主体原图信息不完整，请重新选择图片"
                ));
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

    private static UUID requiredAssetParameter(FeatureExecutionContext context, String name) {
        Object value = context.parameters().get(name);
        if (value == null || value.toString().isBlank()) {
            throw new FeatureValidationException(name, "请上传主体原图");
        }
        return parseAssetId(name, value);
    }

    private static UUID optionalAssetParameter(FeatureExecutionContext context, String name) {
        Object value = context.parameters().get(name);
        if (value == null || value.toString().isBlank()) return null;
        return parseAssetId(name, value);
    }

    private static UUID parseAssetId(String field, Object value) {
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            throw new FeatureValidationException(field, "图片资源标识无效");
        }
    }

    private static String stringParameter(FeatureExecutionContext context, String name) {
        Object value = context.parameters().get(name);
        return value == null ? "" : value.toString().trim();
    }

    private static ModelProviderException invalidResponse(String message) {
        return new ModelProviderException("PROVIDER_INVALID_RESPONSE", message, false);
    }
}
