package com.aibox.features.image.expand;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.ArtifactDrafts;
import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureHandler;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.ImageExpansionRequest;
import com.aibox.feature.spi.ImageExpansionResponse;
import com.aibox.feature.spi.ImagePreservationMode;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class ImageExpandFeatureHandler implements FeatureHandler {

    public static final String FEATURE_CODE = "image.expand";
    static final String MODEL_ALIAS = "image.generation.default";
    static final long MAX_SOURCE_IMAGE_BYTES = 10L * 1024L * 1024L;

    private static final Set<String> PRESET_RATIOS = Set.of("1:1", "3:4", "16:9", "9:16", "4:5");
    private static final Set<String> PRESET_SCALES = Set.of("1.0", "1.25", "1.5", "2.0");
    private static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".webp");
    private static final Pattern CUSTOM_RATIO = Pattern.compile("^[1-9][0-9]{0,2}:[1-9][0-9]{0,2}$");

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        preservationMode(context);
        switch (operationMode(context)) {
            case CHANGE_RATIO -> aspectRatio(context);
            case EXPAND -> expansionScale(context);
            case LEGACY -> {
                aspectRatio(context);
                expansionScale(context);
            }
        }
        if (context.inputAssetIds().size() > 1) {
            throw new FeatureValidationException("sourceImage", "只能选择一张原图");
        }
        if (context.baseArtifact() != null) {
            validateBaseArtifact(context.baseArtifact());
            return;
        }
        if (context.inputAssetIds().size() != 1 || context.inputAssets().size() != 1) {
            throw new FeatureValidationException("sourceImage", "请上传一张原图");
        }
        validateSourceAsset(context.inputAssets().get(0));
    }

    @Override
    public FeatureExecutionResult execute(FeatureExecutionContext context, ModelGateway modelGateway) {
        OperationMode operationMode = operationMode(context);
        ImagePreservationMode requestedPreservationMode = preservationMode(context);
        ImagePreservationMode preservationMode = operationMode == OperationMode.CHANGE_RATIO
                ? ImagePreservationMode.STRICT
                : requestedPreservationMode;
        String aspectRatio = operationMode == OperationMode.EXPAND
                ? "source"
                : aspectRatio(context);
        double expansionScale = operationMode == OperationMode.CHANGE_RATIO
                ? 1.0
                : expansionScale(context);
        UUID sourceAssetId = sourceAssetId(context);
        String operationInstruction = operationMode == OperationMode.EXPAND
                ? "Keep the source image aspect ratio unchanged and expand the canvas equally in all directions. "
                : "First identify the primary subject. Lock its exact original position, scale, pose, geometry, "
                        + "and visual identity. Expand the canvas to the requested target aspect ratio without "
                        + "moving, duplicating, stretching, or repainting the subject. ";
        String prompt = operationInstruction + (preservationMode == ImagePreservationMode.STRICT
                ? "Expand the transparent outer canvas naturally. Fill only the area outside the centered source "
                        + "image. Match its lighting, perspective, texture, colors, and scene continuity. "
                        + "Do not crop, distort, move, or repaint the original image region."
                : "Expand the centered source image naturally to fill the full canvas. Match its lighting, "
                        + "perspective, texture, colors, and scene continuity. Preserve the subject, composition, "
                        + "and meaning while allowing subtle refinements inside the original image region.");

        ImageExpansionResponse response = modelGateway.expandImage(new ImageExpansionRequest(
                context.tenantId(),
                context.runId(),
                MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.IMAGE_GENERATION),
                prompt,
                sourceAssetId,
                aspectRatio,
                expansionScale,
                preservationMode,
                Map.of(
                        "featureCode", FEATURE_CODE,
                        "runId", context.runId().toString(),
                        "operationMode", operationMode.code()
                )
        ));
        if (response.generation().images().size() != 1) {
            throw new ModelProviderException(
                    "PROVIDER_INVALID_RESPONSE",
                    "扩图模型必须且仅返回一张有效图片",
                    false
            );
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceAssetId", sourceAssetId.toString());
        metadata.put("operationMode", operationMode.code());
        metadata.put("preservationMode", preservationMode.name().toLowerCase(Locale.ROOT));
        metadata.put(
                "requestedPreservationMode",
                requestedPreservationMode.name().toLowerCase(Locale.ROOT)
        );
        metadata.put("subjectLocked", operationMode == OperationMode.CHANGE_RATIO);
        metadata.put(
                "targetAspectRatio",
                normalizedRatio(response.targetWidth(), response.targetHeight())
        );
        metadata.put("expansionScale", expansionScale);
        metadata.put("sourceWidth", response.sourceWidth());
        metadata.put("sourceHeight", response.sourceHeight());
        metadata.put("targetWidth", response.targetWidth());
        metadata.put("targetHeight", response.targetHeight());
        if (context.baseArtifact() != null) {
            metadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
            metadata.put("basedOnVersion", context.baseArtifact().versionNumber());
        }

        String artifactTitle = operationMode == OperationMode.CHANGE_RATIO
                ? "改比例结果"
                : "扩图结果";
        ArtifactDraft artifact =
                ArtifactDrafts.generatedImages(artifactTitle, response.generation(), metadata);
        return FeatureExecutionResult.of(artifact);
    }

    private static OperationMode operationMode(FeatureExecutionContext context) {
        return switch (stringParameter(context, "operationMode")) {
            case "change_ratio" -> OperationMode.CHANGE_RATIO;
            case "expand" -> OperationMode.EXPAND;
            case "" -> OperationMode.LEGACY;
            default -> throw new FeatureValidationException(
                    "operationMode", "请选择改比例或扩图"
            );
        };
    }

    private static ImagePreservationMode preservationMode(FeatureExecutionContext context) {
        return switch (stringParameter(context, "preservationMode")) {
            case "strict" -> ImagePreservationMode.STRICT;
            case "flexible" -> ImagePreservationMode.FLEXIBLE;
            default -> throw new FeatureValidationException(
                    "preservationMode", "请选择严格保留或自然重绘"
            );
        };
    }

    private static String aspectRatio(FeatureExecutionContext context) {
        String ratioMode = stringParameter(context, "ratioMode");
        String ratio;
        if ("preset".equals(ratioMode)) {
            ratio = stringParameter(context, "presetAspectRatio");
            if (!PRESET_RATIOS.contains(ratio)) {
                throw new FeatureValidationException("presetAspectRatio", "请选择有效的预设比例");
            }
        } else if ("custom".equals(ratioMode)) {
            ratio = stringParameter(context, "customAspectRatio");
            if (!CUSTOM_RATIO.matcher(ratio).matches()) {
                throw new FeatureValidationException(
                        "customAspectRatio", "自定义比例必须使用宽:高格式，例如 7:5"
                );
            }
        } else {
            throw new FeatureValidationException("ratioMode", "请选择预设比例或自定义比例");
        }

        String[] parts = ratio.split(":");
        int width = Integer.parseInt(parts[0]);
        int height = Integer.parseInt(parts[1]);
        if ((long) width > (long) height * 3 || (long) height > (long) width * 3) {
            throw new FeatureValidationException("customAspectRatio", "目标比例必须在 1:3 至 3:1 之间");
        }
        int divisor = gcd(width, height);
        return (width / divisor) + ":" + (height / divisor);
    }

    private static UUID sourceAssetId(FeatureExecutionContext context) {
        if (context.baseArtifact() != null) {
            return baseAssetId(context.baseArtifact());
        }
        return context.inputAssetIds().get(0);
    }

    private static double expansionScale(FeatureExecutionContext context) {
        String mode = stringParameter(context, "expansionScaleMode");
        if (mode.isBlank()) {
            return 1.25;
        }
        double scale;
        if ("preset".equals(mode)) {
            String value = stringParameter(context, "presetExpansionScale");
            if (!PRESET_SCALES.contains(value)) {
                throw new FeatureValidationException("presetExpansionScale", "请选择有效的预设扩展倍数");
            }
            scale = Double.parseDouble(value);
        } else if ("custom".equals(mode)) {
            Object value = context.parameters().get("customExpansionScale");
            try {
                scale = value instanceof Number number
                        ? number.doubleValue()
                        : Double.parseDouble(value == null ? "" : value.toString().trim());
            } catch (NumberFormatException exception) {
                throw new FeatureValidationException("customExpansionScale", "请输入有效的自定义扩展倍数");
            }
            double steps = scale * 20.0;
            if (Math.abs(steps - Math.rint(steps)) > 0.000_001) {
                throw new FeatureValidationException("customExpansionScale", "自定义倍数必须以 0.05 为步长");
            }
        } else {
            throw new FeatureValidationException("expansionScaleMode", "请选择预设倍数或自定义倍数");
        }
        if (!Double.isFinite(scale) || scale < 1.0) {
            throw new FeatureValidationException("customExpansionScale", "扩展倍数必须是不小于 1.0 的有效数值");
        }
        return scale;
    }

    private static void validateSourceAsset(InputAssetReference asset) {
        String mediaType = asset.mediaType() == null
                ? ""
                : asset.mediaType().toLowerCase(Locale.ROOT);
        String fileName = asset.fileName() == null
                ? ""
                : asset.fileName().toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPES.contains(mediaType) || EXTENSIONS.stream().noneMatch(fileName::endsWith)) {
            throw new FeatureValidationException("sourceImage", "原图仅支持 PNG、JPG、JPEG 和 WebP 格式");
        }
        if (asset.sizeBytes() <= 0 || asset.sizeBytes() > MAX_SOURCE_IMAGE_BYTES) {
            throw new FeatureValidationException("sourceImage", "原图大小不能超过 10 MB");
        }
    }

    private static void validateBaseArtifact(ArtifactReference baseArtifact) {
        if (!"image".equals(baseArtifact.kind())
                && (baseArtifact.mimeType() == null || !baseArtifact.mimeType().startsWith("image/"))) {
            throw new FeatureValidationException("baseArtifactId", "只能基于图片成果继续扩图");
        }
        baseAssetId(baseArtifact);
    }

    private static UUID baseAssetId(ArtifactReference baseArtifact) {
        Object value = baseArtifact.content().get("assetId");
        if (value == null || value.toString().isBlank()) {
            throw new FeatureValidationException("baseArtifactId", "上一版图片成果缺少可复用的图片资源");
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            throw new FeatureValidationException("baseArtifactId", "上一版图片资源标识无效");
        }
    }

    private static String stringParameter(FeatureExecutionContext context, String name) {
        Object value = context.parameters().get(name);
        return value == null ? "" : value.toString().trim();
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

    private static String normalizedRatio(int width, int height) {
        int divisor = gcd(width, height);
        return (width / divisor) + ":" + (height / divisor);
    }

    private enum OperationMode {
        CHANGE_RATIO("change_ratio"),
        EXPAND("expand"),
        LEGACY("legacy");

        private final String code;

        OperationMode(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
