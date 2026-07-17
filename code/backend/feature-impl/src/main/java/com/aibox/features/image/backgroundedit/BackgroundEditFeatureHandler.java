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
import java.util.ArrayDeque;
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
    static final int CHROMA_KEY_RGB = 0x00FF00;
    static final int CHROMA_KEY_DISTANCE = 150;

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
        Map<String, Object> requestMetadata = new LinkedHashMap<>();
        requestMetadata.put("featureCode", FEATURE_CODE);
        requestMetadata.put("runId", context.runId().toString());
        requestMetadata.put("mode", mode);
        requestMetadata.put("outputFormat", "png");
        requestMetadata.put("preserveSourceDimensions", true);
        requestMetadata.put("sourceWidth", sourceImage.width());
        requestMetadata.put("sourceHeight", sourceImage.height());
        requestMetadata.put("referenceImageCount", orderedImages.size());

        ImageGenerationResponse response = modelGateway.generateImage(new ImageGenerationRequest(
                context.tenantId(),
                context.runId(),
                MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.IMAGE_GENERATION),
                prompt(mode, backgroundImageId != null, backgroundDescription),
                orderedImages,
                null,
                1,
                requestMetadata
        ));
        ImageGenerationResponse normalized = normalizeResponse(
                response,
                REMOVE_BACKGROUND.equals(mode),
                sourceImage.width(),
                sourceImage.height()
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", mode);
        metadata.put("preserveSourceDimensions", true);
        metadata.put("backgroundReferenceUsed", backgroundImageId != null);
        metadata.put("backgroundDescriptionUsed", !backgroundDescription.isBlank());
        if (context.baseArtifact() != null) {
            metadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
            metadata.put("basedOnVersion", context.baseArtifact().versionNumber());
        }

        String title = REMOVE_BACKGROUND.equals(mode) ? "透明背景抠图" : "换背景结果";
        ArtifactDraft artifact = ArtifactDrafts.generatedImages(title, normalized, metadata);
        return FeatureExecutionResult.of(artifact);
    }

    private static String prompt(String mode, boolean hasBackgroundImage, String description) {
        String common = """
                Keep the subject identity, shape, colors, fine edges, hair, clothing, and small details unchanged.
                Keep exactly the same canvas dimensions and aspect ratio as the first input image.
                Return exactly one PNG image. Do not add borders, captions, logos, or extra subjects.
                The first input image is always the subject image.
                """;
        if (REMOVE_BACKGROUND.equals(mode)) {
            return common + """
                    Remove the entire background automatically.
                    Preserve only the foreground subject and any naturally attached foreground details.
                    Replace every removed background pixel with one perfectly uniform solid chroma-key green #00FF00.
                    Do not use gradients, shadows, reflections, textures, checkerboards, or green spill in the background.
                    """;
        }
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

    private static ImageGenerationResponse normalizeResponse(
            ImageGenerationResponse response,
            boolean requireTransparentPixels,
            int targetWidth,
            int targetHeight
    ) {
        if (response.images().size() != 1) {
            throw invalidResponse("图片模型必须且仅返回一张有效图片");
        }
        GeneratedImage source = response.images().get(0);
        if (source.content().length == 0 || source.content().length > MAX_OUTPUT_IMAGE_BYTES) {
            throw invalidResponse("图片模型返回的图片大小无效");
        }
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(source.content()));
        } catch (IOException exception) {
            throw invalidResponse("图片模型返回了无法读取的图片");
        }
        if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
            throw invalidResponse("图片模型返回了无效图片");
        }

        BufferedImage prepared = decoded;
        if (requireTransparentPixels && !hasTransparentPixel(prepared)) {
            prepared = removeBorderConnectedChromaKey(prepared);
        }
        if (requireTransparentPixels && !hasTransparentPixel(prepared)) {
            throw invalidResponse("抠图模型未返回透明背景或标准绿幕背景");
        }

        BufferedImage argb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        double scale = Math.max(
                targetWidth / (double) prepared.getWidth(),
                targetHeight / (double) prepared.getHeight()
        );
        int drawWidth = Math.max(1, (int) Math.round(prepared.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(prepared.getHeight() * scale));
        int drawX = (targetWidth - drawWidth) / 2;
        int drawY = (targetHeight - drawHeight) / 2;
        Graphics2D graphics = argb.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY
            );
            graphics.drawImage(prepared, drawX, drawY, drawWidth, drawHeight, null);
        } finally {
            graphics.dispose();
        }
        if (requireTransparentPixels && !hasTransparentPixel(argb)) {
            throw invalidResponse("抠图结果没有透明像素");
        }

        byte[] png;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(argb, "png", output)) {
                throw invalidResponse("图片结果无法转换为 PNG");
            }
            png = output.toByteArray();
        } catch (IOException exception) {
            throw invalidResponse("图片结果无法转换为 PNG");
        }

        GeneratedImage normalized = new GeneratedImage(
                source.sourceUrl(),
                "image/png",
                source.revisedPrompt(),
                png
        );
        return new ImageGenerationResponse(
                List.of(normalized),
                response.provider(),
                response.model(),
                response.providerRequestId(),
                response.inputUnits(),
                response.outputUnits()
        );
    }

    private static boolean hasTransparentPixel(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) < 255) return true;
            }
        }
        return false;
    }

    private static BufferedImage removeBorderConnectedChromaKey(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean[] background = new boolean[width * height];
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int x = 0; x < width; x++) {
            enqueueChromaPixel(source, x, 0, background, queue);
            enqueueChromaPixel(source, x, height - 1, background, queue);
        }
        for (int y = 1; y < height - 1; y++) {
            enqueueChromaPixel(source, 0, y, background, queue);
            enqueueChromaPixel(source, width - 1, y, background, queue);
        }

        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            int x = index % width;
            int y = index / width;
            if (x > 0) enqueueChromaPixel(source, x - 1, y, background, queue);
            if (x + 1 < width) enqueueChromaPixel(source, x + 1, y, background, queue);
            if (y > 0) enqueueChromaPixel(source, x, y - 1, background, queue);
            if (y + 1 < height) enqueueChromaPixel(source, x, y + 1, background, queue);
        }

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int argb = source.getRGB(x, y);
                if (background[index]) {
                    result.setRGB(x, y, argb & 0x00FFFFFF);
                } else {
                    result.setRGB(x, y, argb);
                }
            }
        }
        return result;
    }

    private static void enqueueChromaPixel(
            BufferedImage source,
            int x,
            int y,
            boolean[] background,
            ArrayDeque<Integer> queue
    ) {
        int index = y * source.getWidth() + x;
        if (background[index] || !isChromaKey(source.getRGB(x, y))) return;
        background[index] = true;
        queue.addLast(index);
    }

    private static boolean isChromaKey(int argb) {
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        int keyRed = (CHROMA_KEY_RGB >>> 16) & 0xFF;
        int keyGreen = (CHROMA_KEY_RGB >>> 8) & 0xFF;
        int keyBlue = CHROMA_KEY_RGB & 0xFF;
        int redDifference = red - keyRed;
        int greenDifference = green - keyGreen;
        int blueDifference = blue - keyBlue;
        int distanceSquared = redDifference * redDifference
                + greenDifference * greenDifference
                + blueDifference * blueDifference;
        return distanceSquared <= CHROMA_KEY_DISTANCE * CHROMA_KEY_DISTANCE;
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
