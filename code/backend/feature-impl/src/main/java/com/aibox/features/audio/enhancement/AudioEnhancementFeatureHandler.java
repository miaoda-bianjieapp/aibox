package com.aibox.features.audio.enhancement;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.ArtifactDrafts;
import com.aibox.feature.spi.AudioEnhancementRequest;
import com.aibox.feature.spi.AudioEnhancementResponse;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureHandler;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.GeneratedAudio;
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

@Component
public final class AudioEnhancementFeatureHandler implements FeatureHandler {

    public static final String FEATURE_CODE = "audio.enhancement";

    private static final String MODEL_ALIAS = "audio.enhancement.default";
    private static final String OUTPUT_FORMAT = "auto";
    private static final long MAX_AUDIO_BYTES = 200L * 1024 * 1024;
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".aac", ".m4a", ".wav", ".flac", ".ogg"
    );

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        UUID audioId = audioId(context);
        if (context.inputAssetIds().size() != 1 || !context.inputAssetIds().get(0).equals(audioId)) {
            throw new FeatureValidationException("audioFile", "请选择一个音频文件");
        }
        validateAudioAsset(inputAsset(context, audioId));
        keepBackgroundMusic(context);
        String deployment = context.selectedModelCode(ModelCapability.AUDIO_ENHANCEMENT);
        if (deployment == null || deployment.isBlank()) {
            throw new FeatureValidationException("selectedModels", "人声降噪模型配置不完整");
        }
        if (context.baseArtifact() != null
                && !"audio".equals(context.baseArtifact().kind())
                && (context.baseArtifact().mimeType() == null
                || !context.baseArtifact().mimeType().startsWith("audio/"))) {
            throw new FeatureValidationException("baseArtifactId", "只能基于音频成果生成新版本");
        }
    }

    @Override
    public FeatureExecutionResult execute(FeatureExecutionContext context, ModelGateway modelGateway) {
        validate(context);
        UUID audioId = audioId(context);
        InputAssetReference input = inputAsset(context, audioId);
        boolean keepMusic = keepBackgroundMusic(context);
        AudioEnhancementResponse response = modelGateway.enhanceAudio(new AudioEnhancementRequest(
                context.tenantId(),
                context.runId(),
                MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.AUDIO_ENHANCEMENT),
                audioId,
                keepMusic,
                OUTPUT_FORMAT,
                Map.of(
                        "featureCode", FEATURE_CODE,
                        "sourceAssetId", audioId.toString()
                )
        ));
        GeneratedAudio audio = response.audio();
        if (audio == null || audio.content().length == 0) {
            throw new ModelProviderException(
                    "PROVIDER_INVALID_RESPONSE",
                    "音频增强模型没有返回可用音频",
                    false
            );
        }
        if (audio.fileName() == null || audio.fileName().isBlank()
                || audio.mediaType() == null || audio.mediaType().isBlank()) {
            throw new ModelProviderException(
                    "PROVIDER_INVALID_RESPONSE",
                    "音频增强模型返回的文件信息不完整",
                    false
            );
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("featureCode", FEATURE_CODE);
        metadata.put("sourceAssetId", audioId.toString());
        metadata.put("keepBackgroundMusic", keepMusic);
        metadata.put("outputFormat", OUTPUT_FORMAT);
        putIfPresent(metadata, "provider", response.provider());
        putIfPresent(metadata, "model", response.model());
        putIfPresent(metadata, "providerRequestId", response.providerRequestId());
        if (context.baseArtifact() != null) {
            metadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
            metadata.put("basedOnVersion", context.baseArtifact().versionNumber());
        }

        ArtifactDraft artifact = ArtifactDrafts.generatedMedia(
                "audio",
                limitedTitle(baseName(input.fileName()) + " 人声降噪"),
                audio.fileName(),
                audio.mediaType(),
                audio.content(),
                Map.copyOf(metadata)
        );
        return FeatureExecutionResult.of(artifact);
    }

    private static UUID audioId(FeatureExecutionContext context) {
        Object value = context.parameters().get("audioFile");
        try {
            return UUID.fromString(value == null ? "" : value.toString());
        } catch (IllegalArgumentException exception) {
            throw new FeatureValidationException("audioFile", "请选择一个音频文件");
        }
    }

    private static InputAssetReference inputAsset(FeatureExecutionContext context, UUID assetId) {
        return context.inputAssets().stream()
                .filter(asset -> asset.id().equals(assetId))
                .findFirst()
                .orElseThrow(() -> new FeatureValidationException(
                        "audioFile", "音频文件不存在或不可用"
                ));
    }

    private static void validateAudioAsset(InputAssetReference asset) {
        String extension = extension(asset.fileName());
        if (!AUDIO_EXTENSIONS.contains(extension)) {
            throw new FeatureValidationException("audioFile", "不支持该音频格式");
        }
        String mediaType = asset.mediaType() == null
                ? ""
                : asset.mediaType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!mediaType.isBlank()
                && !"application/octet-stream".equals(mediaType)
                && !"application/ogg".equals(mediaType)
                && !mediaType.startsWith("audio/")) {
            throw new FeatureValidationException("audioFile", "音频文件类型与扩展名不匹配");
        }
        if (asset.sizeBytes() <= 0 || asset.sizeBytes() > MAX_AUDIO_BYTES) {
            throw new FeatureValidationException("audioFile", "音频文件不能超过 200 MB");
        }
    }

    private static boolean keepBackgroundMusic(FeatureExecutionContext context) {
        if (!context.parameters().containsKey("keepBackgroundMusic")) {
            throw new FeatureValidationException(
                    "keepBackgroundMusic", "请选择是否保留背景音乐"
            );
        }
        Object value = context.parameters().get("keepBackgroundMusic");
        if (value instanceof Boolean flag) return flag;
        throw new FeatureValidationException(
                "keepBackgroundMusic", "保留背景音乐必须是布尔值"
        );
    }

    private static String extension(String value) {
        if (value == null) return "";
        int index = value.lastIndexOf('.');
        return index < 0 ? "" : value.substring(index).toLowerCase(Locale.ROOT);
    }

    private static String baseName(String value) {
        if (value == null || value.isBlank()) return "音频";
        int index = value.lastIndexOf('.');
        return index <= 0 ? value : value.substring(0, index);
    }

    private static String limitedTitle(String value) {
        int[] codePoints = value.codePoints().limit(240).toArray();
        return new String(codePoints, 0, codePoints.length);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
