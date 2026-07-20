package com.aibox.features.image.localedit;

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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public final class ImageLocalEditFeatureHandler implements FeatureHandler {

    public static final String FEATURE_CODE = "image.local_edit";
    static final String MODEL_ALIAS = "image.generation.default";
    static final int MAX_INSTRUCTION_LENGTH = 500;
    static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    static final long MAX_INPUT_BYTES = 20L * 1024L * 1024L;
    static final long MAX_OUTPUT_IMAGE_BYTES = 50L * 1024L * 1024L;
    static final int MAX_IMAGE_DIMENSION = 8_192;
    static final long MAX_IMAGE_PIXELS = 40_000_000L;

    private static final Set<String> SOURCE_MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp"
    );
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".webp"
    );

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        String instruction = stringParameter(context, "instruction");
        if (instruction.isBlank()) {
            throw new FeatureValidationException("instruction", "请输入局部修改指令");
        }
        if (instruction.length() > MAX_INSTRUCTION_LENGTH) {
            throw new FeatureValidationException("instruction", "修改指令不能超过 500 个字符");
        }

        UUID sourceImageId = effectiveSourceImageId(context);
        UUID maskImageId = requiredAssetParameter(context, "maskImage", "请涂抹需要修改的区域");
        List<UUID> expectedAssets = List.of(sourceImageId, maskImageId);
        if (!context.inputAssetIds().equals(expectedAssets)) {
            throw new FeatureValidationException(
                    "inputAssetIds",
                    "图片顺序必须是原始图片、编辑区域蒙版"
            );
        }
        if (context.inputAssets().size() != 2) {
            throw new FeatureValidationException("inputAssetIds", "图片信息不完整，请重新选择原图并涂抹选区");
        }
        InputAssetReference source = requireInputAsset(context, sourceImageId, "sourceImage");
        InputAssetReference mask = requireInputAsset(context, maskImageId, "maskImage");
        validateSource(source);
        validateMask(mask);
        if (!source.width().equals(mask.width()) || !source.height().equals(mask.height())) {
            throw new FeatureValidationException("maskImage", "编辑区域蒙版尺寸必须与原图完全一致");
        }
        if (source.sizeBytes() + mask.sizeBytes() > MAX_INPUT_BYTES) {
            throw new FeatureValidationException("inputAssetIds", "原图和蒙版总大小不能超过 20 MB");
        }
    }

    @Override
    public FeatureExecutionResult execute(FeatureExecutionContext context, ModelGateway modelGateway) {
        UUID sourceImageId = effectiveSourceImageId(context);
        UUID maskImageId = requiredAssetParameter(context, "maskImage", "请涂抹需要修改的区域");
        InputAssetReference source = requireInputAsset(context, sourceImageId, "sourceImage");

        ImageGenerationResponse response = modelGateway.generateImage(new ImageGenerationRequest(
                context.tenantId(),
                context.runId(),
                MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.IMAGE_GENERATION),
                prompt(stringParameter(context, "instruction")),
                List.of(sourceImageId),
                List.of(),
                maskImageId,
                true,
                "auto",
                1,
                Map.of(
                        "featureCode", FEATURE_CODE,
                        "runId", context.runId().toString(),
                        "outputFormat", "png",
                        "inputFidelity", "high",
                        "sourceWidth", source.width(),
                        "sourceHeight", source.height(),
                        "preserveUnmaskedPixels", true,
                        "providerInvocationKey", "image_local_edit"
                )
        ));
        validateOutput(response, source.width(), source.height());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceWidth", source.width());
        metadata.put("sourceHeight", source.height());
        metadata.put("maskSemantics", "transparent_is_edit");
        metadata.put("preserveUnmaskedPixels", true);
        if (context.baseArtifact() != null) {
            metadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
            metadata.put("basedOnVersion", context.baseArtifact().versionNumber());
        }
        ArtifactDraft artifact = ArtifactDrafts.generatedImages(
                "图片局部编辑结果",
                response,
                metadata
        );
        return FeatureExecutionResult.of(artifact);
    }

    private static void validateSource(InputAssetReference source) {
        String mediaType = normalized(source.mediaType());
        String fileName = normalized(source.fileName());
        if (!SOURCE_MEDIA_TYPES.contains(mediaType)
                || SOURCE_EXTENSIONS.stream().noneMatch(fileName::endsWith)) {
            throw new FeatureValidationException(
                    "sourceImage",
                    "原始图片仅支持 PNG、JPG、JPEG 和 WebP 格式"
            );
        }
        validateImageLimits(source, "sourceImage", "原始图片");
    }

    private static void validateMask(InputAssetReference mask) {
        if (!"image/png".equals(normalized(mask.mediaType()))
                || !normalized(mask.fileName()).endsWith(".png")) {
            throw new FeatureValidationException("maskImage", "编辑区域蒙版必须是 PNG 图片");
        }
        validateImageLimits(mask, "maskImage", "编辑区域蒙版");
    }

    private static void validateImageLimits(
            InputAssetReference asset,
            String field,
            String label
    ) {
        if (asset.sizeBytes() <= 0 || asset.sizeBytes() > MAX_IMAGE_BYTES) {
            throw new FeatureValidationException(field, label + "不能超过 10 MB");
        }
        if (asset.width() == null || asset.height() == null
                || asset.width() <= 0 || asset.height() <= 0) {
            throw new FeatureValidationException(field, label + "内容无法读取，请重新选择");
        }
        if (asset.width() > MAX_IMAGE_DIMENSION || asset.height() > MAX_IMAGE_DIMENSION
                || (long) asset.width() * asset.height() > MAX_IMAGE_PIXELS) {
            throw new FeatureValidationException(
                    field,
                    label + "最长边不能超过 8192 像素且总像素不能超过 4000 万"
            );
        }
    }

    private static void validateOutput(ImageGenerationResponse response, int width, int height) {
        if (response.images().size() != 1) {
            throw invalidResponse("图片模型必须且仅返回一张有效图片");
        }
        GeneratedImage image = response.images().get(0);
        if (!"image/png".equalsIgnoreCase(image.mediaType())
                || image.content().length == 0
                || image.content().length > MAX_OUTPUT_IMAGE_BYTES) {
            throw invalidResponse("局部编辑结果必须是一张不超过 50 MB 的 PNG 图片");
        }
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.content()));
            if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                throw invalidResponse("局部编辑结果尺寸必须与原图完全一致");
            }
        } catch (IOException exception) {
            throw invalidResponse("局部编辑结果无法读取");
        }
    }

    private static String prompt(String instruction) {
        return """
                Edit only the transparent region of the provided PNG mask.
                Keep every pixel outside the mask unchanged. Preserve the original canvas dimensions,
                crop, composition, perspective, lighting continuity, subject identity, text, logos,
                colors, and geometry outside the selected region.
                Blend the edited region naturally into its surroundings without changing unselected areas.
                Return exactly one PNG image with the same aspect ratio and canvas as the source image.

                Edit instruction:
                """ + instruction;
    }

    private static UUID effectiveSourceImageId(FeatureExecutionContext context) {
        UUID submitted = requiredAssetParameter(context, "sourceImage", "请上传原始图片");
        if (context.baseArtifact() == null) return submitted;
        validateBaseArtifact(context.baseArtifact());
        return baseAssetId(context.baseArtifact());
    }

    private static void validateBaseArtifact(ArtifactReference artifact) {
        if (!"image".equals(artifact.kind())
                && (artifact.mimeType() == null || !artifact.mimeType().startsWith("image/"))) {
            throw new FeatureValidationException("baseArtifactId", "只能基于图片成果继续修改");
        }
        baseAssetId(artifact);
    }

    private static UUID baseAssetId(ArtifactReference artifact) {
        Object primary = artifact.content().get("assetId");
        if (primary != null && !primary.toString().isBlank()) {
            return parseAssetId("baseArtifactId", primary);
        }
        Object multiple = artifact.content().get("assetIds");
        if (multiple instanceof List<?> items && !items.isEmpty()) {
            return parseAssetId("baseArtifactId", items.get(0));
        }
        throw new FeatureValidationException("baseArtifactId", "上一版图片成果缺少可复用的图片资源");
    }

    private static InputAssetReference requireInputAsset(
            FeatureExecutionContext context,
            UUID id,
            String field
    ) {
        return context.inputAssets().stream()
                .filter(asset -> id.equals(asset.id()))
                .findFirst()
                .orElseThrow(() -> new FeatureValidationException(
                        field,
                        "图片信息不完整，请重新选择"
                ));
    }

    private static UUID requiredAssetParameter(
            FeatureExecutionContext context,
            String name,
            String message
    ) {
        Object value = context.parameters().get(name);
        if (value == null || value.toString().isBlank()) {
            throw new FeatureValidationException(name, message);
        }
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

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static ModelProviderException invalidResponse(String message) {
        return new ModelProviderException("PROVIDER_INVALID_RESPONSE", message, false);
    }
}
