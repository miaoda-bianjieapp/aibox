package com.aibox.features.image.generate;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.ArtifactDrafts;
import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureHandler;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public final class ImageGenerateFeatureHandler implements FeatureHandler {

    public static final String FEATURE_CODE = "image.generate";
    static final String MODEL_ALIAS = "image.generation.default";
    static final int MAX_PROMPT_LENGTH = 500;
    static final int MAX_USER_REFERENCE_IMAGES = 3;
    static final int MAX_TOTAL_REFERENCE_IMAGES = 4;
    static final long MAX_REFERENCE_IMAGE_BYTES = 20L * 1024L * 1024L;
    static final long MAX_REFERENCE_IMAGES_TOTAL_BYTES = 30L * 1024L * 1024L;
    static final String REFERENCE_MODE_NONE = "NONE";
    static final String REFERENCE_MODE_USE_BASE = "USE_BASE";

    private static final Set<String> ASPECT_RATIOS = Set.of("1:1", "16:9", "9:16");
    private static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".webp");

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        String prompt = stringParameter(context, "prompt");
        if (prompt.isBlank()) {
            throw new FeatureValidationException("prompt", "请输入画面描述");
        }
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            throw new FeatureValidationException("prompt", "画面描述不能超过 500 个字符");
        }

        String aspectRatio = stringParameter(context, "aspectRatio");
        if (!ASPECT_RATIOS.contains(aspectRatio)) {
            throw new FeatureValidationException("aspectRatio", "请选择 1:1、16:9 或 9:16 图片比例");
        }
        if (context.inputAssetIds().size() > MAX_USER_REFERENCE_IMAGES) {
            throw new FeatureValidationException("referenceImages", "最多上传 3 张参考图片");
        }
        validateInputAssets(context);
        validateBaseArtifactKind(context.baseArtifact());
        String referenceMode = referenceMode(context);
        if (REFERENCE_MODE_USE_BASE.equals(referenceMode) && context.baseArtifact() == null) {
            throw new FeatureValidationException(
                    "generatedReferenceMode",
                    "使用上一版成果时必须选择一个图片成果"
            );
        }
        if (mergeReferenceAssets(context, referenceMode).size() > MAX_TOTAL_REFERENCE_IMAGES) {
            throw new FeatureValidationException("referenceImages", "参考图片总数不能超过 4 张");
        }
    }

    @Override
    public FeatureExecutionResult execute(FeatureExecutionContext context, ModelGateway modelGateway) {
        String prompt = stringParameter(context, "prompt");
        String aspectRatio = stringParameter(context, "aspectRatio");
        String referenceMode = referenceMode(context);
        List<UUID> referenceAssetIds = mergeReferenceAssets(context, referenceMode);

        ImageGenerationResponse response = modelGateway.generateImage(new ImageGenerationRequest(
                context.tenantId(),
                context.runId(),
                MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.IMAGE_GENERATION),
                prompt,
                referenceAssetIds,
                aspectRatio,
                1,
                Map.of(
                        "featureCode", FEATURE_CODE,
                        "runId", context.runId().toString(),
                        "aspectRatio", aspectRatio,
                        "referenceImageCount", referenceAssetIds.size()
                )
        ));
        if (response.images().size() != 1) {
            throw new ModelProviderException(
                    "PROVIDER_INVALID_RESPONSE",
                    "图片模型必须且仅返回一张有效图片",
                    false
            );
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("aspectRatio", aspectRatio);
        metadata.put("userReferenceImageCount", context.inputAssetIds().size());
        metadata.put("referenceImageCount", referenceAssetIds.size());
        metadata.put(
                "usedBaseArtifactAsReference",
                REFERENCE_MODE_USE_BASE.equals(referenceMode)
        );
        if (context.baseArtifact() != null) {
            metadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
            metadata.put("basedOnVersion", context.baseArtifact().versionNumber());
        }

        ArtifactDraft artifact = ArtifactDrafts.generatedImages("AI 生图", response, metadata);
        return FeatureExecutionResult.of(artifact);
    }

    private static void validateInputAssets(FeatureExecutionContext context) {
        if (context.inputAssetIds().isEmpty()) return;
        if (context.inputAssets().size() != context.inputAssetIds().size()) {
            throw new FeatureValidationException("referenceImages", "参考图片信息不完整，请重新选择图片");
        }
        long totalBytes = 0;
        for (InputAssetReference asset : context.inputAssets()) {
            String mediaType = asset.mediaType() == null
                    ? ""
                    : asset.mediaType().toLowerCase(Locale.ROOT);
            String fileName = asset.fileName() == null
                    ? ""
                    : asset.fileName().toLowerCase(Locale.ROOT);
            if (!MEDIA_TYPES.contains(mediaType) || EXTENSIONS.stream().noneMatch(fileName::endsWith)) {
                throw new FeatureValidationException(
                        "referenceImages",
                        "参考图片仅支持 PNG、JPG、JPEG 和 WebP 格式"
                );
            }
            if (asset.sizeBytes() <= 0 || asset.sizeBytes() > MAX_REFERENCE_IMAGE_BYTES) {
                throw new FeatureValidationException("referenceImages", "单张参考图片不能超过 20 MB");
            }
            totalBytes += asset.sizeBytes();
        }
        if (totalBytes > MAX_REFERENCE_IMAGES_TOTAL_BYTES) {
            throw new FeatureValidationException("referenceImages", "参考图片总大小不能超过 30 MB");
        }
    }

    private static void validateBaseArtifactKind(ArtifactReference baseArtifact) {
        if (baseArtifact == null) return;
        if (!"image".equals(baseArtifact.kind())
                && (baseArtifact.mimeType() == null || !baseArtifact.mimeType().startsWith("image/"))) {
            throw new FeatureValidationException("baseArtifactId", "只能基于图片成果继续修改");
        }
    }

    private static List<UUID> mergeReferenceAssets(
            FeatureExecutionContext context,
            String referenceMode
    ) {
        LinkedHashSet<UUID> references = new LinkedHashSet<>(context.inputAssetIds());
        if (REFERENCE_MODE_USE_BASE.equals(referenceMode)) {
            references.add(baseAssetId(context.baseArtifact()));
        }
        return List.copyOf(references);
    }

    private static String referenceMode(FeatureExecutionContext context) {
        Object configured = context.parameters().get("generatedReferenceMode");
        String value = configured == null ? "" : configured.toString().trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return context.baseArtifact() == null ? REFERENCE_MODE_NONE : REFERENCE_MODE_USE_BASE;
        }
        if (!REFERENCE_MODE_NONE.equals(value) && !REFERENCE_MODE_USE_BASE.equals(value)) {
            throw new FeatureValidationException(
                    "generatedReferenceMode",
                    "上一版成果引用方式无效"
            );
        }
        return value;
    }

    private static UUID baseAssetId(ArtifactReference baseArtifact) {
        Object primary = baseArtifact.content().get("assetId");
        if (primary != null && !primary.toString().isBlank()) {
            return parseAssetId(primary);
        }
        Object multiple = baseArtifact.content().get("assetIds");
        if (multiple instanceof List<?> items && !items.isEmpty()) {
            return parseAssetId(items.get(0));
        }
        throw new FeatureValidationException("baseArtifactId", "上一版图片成果缺少可复用的图片资源");
    }

    private static UUID parseAssetId(Object value) {
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
}
