package com.aibox.features.video.digital_human;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.ArtifactDrafts;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureHandler;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.GeneratedAudio;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextToSpeechRequest;
import com.aibox.feature.spi.TextToSpeechResponse;
import com.aibox.feature.spi.VideoGenerationRequest;
import com.aibox.feature.spi.VideoGenerationResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public final class DigitalHumanFeatureHandler implements FeatureHandler {

    public static final String FEATURE_CODE = "video.digital_human";
    private static final String IMAGE_ALIAS = "digital_human.avatar_image";
    private static final String SPEECH_ALIAS = "digital_human.speech";
    private static final String VIDEO_ALIAS = "digital_human.video";
    private static final String DID_VIDEO_DEPLOYMENT = "did-talks-v1-video";
    private static final int MAX_SCRIPT_LENGTH = 300;
    private static final int MAX_PROMPT_LENGTH = 500;
    private static final int MAX_NEGATIVE_PROMPT_LENGTH = 500;
    private static final long DID_MAX_AUDIO_BYTES = 6L * 1024 * 1024;
    private static final Set<String> AVATAR_SOURCES = Set.of("UPLOAD", "HISTORY", "AI_GENERATED", "NAVTALK_BUILTIN", "HEYGEN_BUILTIN");
    private static final Set<String> UPLOAD_AVATAR_SOURCES = Set.of("UPLOAD", "HISTORY", "AI_GENERATED");
    private static final Set<String> NATIVE_VOICE_VIDEO_DEPLOYMENTS = Set.of(
            "openai2api-sora-2-video",
            "openai2api-grok-imagine-video-1-5-video",
            "agnes-ai-video-v2-0-video"
    );
    private static final Set<String> AUDIO_SOURCES = Set.of("TEXT_TO_SPEECH", "UPLOAD_AUDIO");
    private static final Set<String> VOICE_MODES = Set.of("TTS", "VIDEO_NATIVE");
    private static final Set<String> ASPECT_RATIOS = Set.of("9:16", "16:9", "21:9", "1:1");
    private static final Set<String> RESOLUTIONS = Set.of("720p", "1080p");
    private static final Set<String> VOICES = Set.of("science_female", "gentle_female");
    private static final Set<String> EMOTIONS = Set.of("natural");
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> DID_IMAGE_TYPES = Set.of("image/png", "image/jpeg");
    private static final Set<String> AUDIO_TYPES = Set.of("audio/wav", "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg");

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        requireKnownParameters(context);
        String avatarSource = enumValue(context, "avatarSource", AVATAR_SOURCES);
        String videoDeployment = context.selectedModelCode(ModelCapability.VIDEO_GENERATION);
        UUID avatarId = null;
        if ("heygen-avatar-v-video".equals(videoDeployment) && !"HEYGEN_BUILTIN".equals(avatarSource)) {
            throw new FeatureValidationException(
                    "avatarSource",
                    "HeyGen \u89c6\u9891\u6a21\u578b\u5fc5\u987b\u4f7f\u7528 HeyGen \u5b98\u65b9\u4eba\u7269"
            );
        }
        if ("navtalk-video-compose-custom".equals(videoDeployment)
                && !UPLOAD_AVATAR_SOURCES.contains(avatarSource)) {
            throw new FeatureValidationException(
                    "avatarSource",
                    "NavTalk \u81ea\u5b9a\u4e49\u4eba\u7269\u6a21\u578b\u5fc5\u987b\u4f7f\u7528\u4e0a\u4f20\u3001\u5386\u53f2\u6216 AI \u751f\u6210\u4eba\u7269\u56fe\u7247"
            );
        }
        if (DID_VIDEO_DEPLOYMENT.equals(videoDeployment)
                && !UPLOAD_AVATAR_SOURCES.contains(avatarSource)) {
            throw new FeatureValidationException(
                    "avatarSource",
                    "D-ID \u6570\u5b57\u4eba\u5fc5\u987b\u4f7f\u7528\u4e0a\u4f20\u3001\u5386\u53f2\u6216 AI \u751f\u6210\u4eba\u7269\u56fe\u7247"
            );
        }
        if (NATIVE_VOICE_VIDEO_DEPLOYMENTS.contains(videoDeployment)
                && !UPLOAD_AVATAR_SOURCES.contains(avatarSource)) {
            throw new FeatureValidationException(
                    "avatarSource",
                    "\u539f\u751f\u8bed\u97f3\u89c6\u9891\u6a21\u578b\u5fc5\u987b\u4f7f\u7528\u4e0a\u4f20\u3001\u5386\u53f2\u6216 AI \u751f\u6210\u4eba\u7269\u56fe\u7247"
            );
        }
        if ("NAVTALK_BUILTIN".equals(avatarSource)
                && !"navtalk-video-compose".equals(videoDeployment)) {
            throw new FeatureValidationException("selectedModels", "NavTalk \u5185\u7f6e\u4eba\u7269\u53ea\u80fd\u4f7f\u7528 NavTalk \u5185\u7f6e\u4eba\u7269\u6a21\u578b");
        }
        if ("HEYGEN_BUILTIN".equals(avatarSource)) {
            if (!"heygen-avatar-v-video".equals(videoDeployment)) {
                throw new FeatureValidationException("selectedModels", "HeyGen \u4eba\u7269\u53ea\u80fd\u4f7f\u7528 HeyGen \u89c6\u9891\u6a21\u578b");
            }
            String heygenAvatarId = stringValue(context, "heygenAvatarId");
            String heygenVoiceId = stringValue(context, "heygenVoiceId");
            if (heygenAvatarId.isBlank() || heygenAvatarId.length() > 160) {
                throw new FeatureValidationException("heygenAvatarId", "\u8bf7\u9009\u62e9\u6709\u6548\u7684 HeyGen \u4eba\u7269 ID");
            }
            if (heygenVoiceId.isBlank() || heygenVoiceId.length() > 160) {
                throw new FeatureValidationException("heygenVoiceId", "\u8bf7\u9009\u62e9\u6709\u6548\u7684 HeyGen \u58f0\u97f3 ID");
            }
            if (!Boolean.TRUE.equals(context.parameters().get("avatarConfirmed"))) {
                throw new FeatureValidationException("avatarConfirmed", "\u8bf7\u5148\u786e\u8ba4 HeyGen \u4eba\u7269");
            }
        } else if ("NAVTALK_BUILTIN".equals(avatarSource)) {
            String navTalkAvatarId = stringValue(context, "navTalkAvatarId");
            if (navTalkAvatarId.isBlank() || navTalkAvatarId.length() > 120) {
                throw new FeatureValidationException("navTalkAvatarId", "\u8bf7\u9009\u62e9\u6709\u6548\u7684 NavTalk \u5185\u7f6e\u4eba\u7269");
            }
            if (!Boolean.TRUE.equals(context.parameters().get("avatarConfirmed"))) {
                throw new FeatureValidationException("avatarConfirmed", "\u8bf7\u5148\u786e\u8ba4 NavTalk \u5185\u7f6e\u4eba\u7269");
            }
        } else {
            avatarId = uuidParameter(context, "avatarImage", "请选择一张人物图片");
            if (!Boolean.TRUE.equals(context.parameters().get("avatarConfirmed"))) {
                throw new FeatureValidationException("avatarConfirmed", "请先预览并确认人物形象");
            }
            validateAsset(context, avatarId, IMAGE_TYPES, "avatarImage", "人物图片");
            if ("AI_GENERATED".equals(avatarSource)) {
                String prompt = stringValue(context, "avatarPrompt");
                if (prompt.isBlank() || prompt.length() > MAX_PROMPT_LENGTH) {
                    throw new FeatureValidationException("avatarPrompt", "人物描述不能为空且不能超过 500 个字符");
                }
            }
        }

        String audioSource = enumValue(context, "audioSource", AUDIO_SOURCES);
        String voiceMode = "TEXT_TO_SPEECH".equals(audioSource)
                ? enumValue(context, "voiceGenerationMode", VOICE_MODES)
                : "TTS";
        if (("NAVTALK_BUILTIN".equals(avatarSource) || "HEYGEN_BUILTIN".equals(avatarSource))
                && (!"TEXT_TO_SPEECH".equals(audioSource) || !"VIDEO_NATIVE".equals(voiceMode))) {
            throw new FeatureValidationException("audioSource", "\u5185\u7f6e\u4eba\u7269\u5fc5\u987b\u4f7f\u7528\u6587\u6848\u548c\u89c6\u9891\u6a21\u578b\u539f\u751f\u58f0\u97f3");
        }
        if ("navtalk-video-compose-custom".equals(videoDeployment) && "VIDEO_NATIVE".equals(voiceMode)) {
            throw new FeatureValidationException("voiceGenerationMode", "\u4e0a\u4f20\u4eba\u7269\u56fe\u7247\u65f6\u5fc5\u987b\u4f7f\u7528\u72ec\u7acb TTS \u6216\u4e0a\u4f20\u97f3\u9891");
        }
        if (DID_VIDEO_DEPLOYMENT.equals(videoDeployment) && "VIDEO_NATIVE".equals(voiceMode)) {
            throw new FeatureValidationException(
                    "voiceGenerationMode",
                    "D-ID \u5f53\u524d\u672a\u63d0\u4f9b\u6700\u7ec8\u89c6\u9891\u63d0\u4ea4\u524d\u53ef\u786e\u8ba4\u7684\u72ec\u7acb\u97f3\u9891\u9884\u89c8\uff0c\u8bf7\u4f7f\u7528 TTS \u6216\u4e0a\u4f20\u97f3\u9891"
            );
        }
        if (NATIVE_VOICE_VIDEO_DEPLOYMENTS.contains(videoDeployment)
                && (!"TEXT_TO_SPEECH".equals(audioSource) || !"VIDEO_NATIVE".equals(voiceMode))) {
            throw new FeatureValidationException(
                    "voiceGenerationMode",
                    "\u5f53\u524d\u89c6\u9891\u6a21\u578b\u5fc5\u987b\u4f7f\u7528\u53e3\u64ad\u6587\u6848\u5e76\u7531\u89c6\u9891\u6a21\u578b\u76f4\u63a5\u751f\u6210\u8bed\u97f3"
            );
        }
        if (!Boolean.TRUE.equals(context.parameters().get("audioConfirmed"))) {
            throw new FeatureValidationException("audioConfirmed", "请先试听并确认使用音频");
        }
        if ("TEXT_TO_SPEECH".equals(audioSource)) {
            String script = stringValue(context, "script");
            if (script.isBlank() || script.length() > MAX_SCRIPT_LENGTH) {
                throw new FeatureValidationException("script", "口播文案不能为空且最多 300 个字符");
            }
            if (!VOICE_MODES.contains(voiceMode)) {
                throw new FeatureValidationException("voiceGenerationMode", "语音生成方式不受支持");
            }
            if ("TTS".equals(voiceMode)) {
                enumValue(context, "voice", VOICES);
                enumValue(context, "emotion", EMOTIONS);
                double speed = numberValue(context, "speed", 1.0);
                if (speed < 0.5 || speed > 2.0) {
                    throw new FeatureValidationException("speed", "语速必须在 0.5 到 2.0 之间");
                }
            }
        } else {
            UUID audioId = uuidParameter(context, "audioFile", "请选择一个音频文件");
            validateAsset(context, audioId, AUDIO_TYPES, "audioFile", "音频文件");
        }

        enumValue(context, "aspectRatio", ASPECT_RATIOS);
        enumValue(context, "resolution", RESOLUTIONS);
        int durationSeconds = intValue(context, "durationSeconds", 5);
        if (durationSeconds < 1 || durationSeconds > 60) {
            throw new FeatureValidationException("durationSeconds", "视频时长必须在 1 到 60 秒之间");
        }
        int outputCount = intValue(context, "outputCount", 1);
        if (outputCount != 1) throw new FeatureValidationException("outputCount", "每次只能生成 1 条视频");
        int fps = intValue(context, "fps", 30);
        if (fps != 30) throw new FeatureValidationException("fps", "首版固定使用 30fps");
        String negative = stringValue(context, "negativePrompt");
        if (negative.length() > MAX_NEGATIVE_PROMPT_LENGTH) {
            throw new FeatureValidationException("negativePrompt", "负面提示词不能超过 500 个字符");
        }
        String deployment = videoDeployment;
        if (deployment == null || deployment.isBlank()) {
            throw new FeatureValidationException("selectedModels", "视频模型配置不完整");
        }
        if (context.baseArtifact() != null
                && !"video".equals(context.baseArtifact().kind())
                && (context.baseArtifact().mimeType() == null || !context.baseArtifact().mimeType().startsWith("video/"))) {
            throw new FeatureValidationException("baseArtifactId", "只能基于视频成果继续修改");
        }
    }

    @Override
    public FeatureExecutionResult execute(FeatureExecutionContext context, ModelGateway modelGateway) {
        validate(context);
        String avatarSource = enumValue(context, "avatarSource", AVATAR_SOURCES);
        UUID avatarId = ("NAVTALK_BUILTIN".equals(avatarSource) || "HEYGEN_BUILTIN".equals(avatarSource))
                ? null
                : uuidParameter(context, "avatarImage", "\u8bf7\u9009\u62e9\u4e00\u5f20\u4eba\u7269\u56fe\u7247");
        String audioSource = enumValue(context, "audioSource", AUDIO_SOURCES);
        String voiceMode = "TEXT_TO_SPEECH".equals(audioSource)
                ? enumValue(context, "voiceGenerationMode", VOICE_MODES)
                : "TTS";
        List<UUID> videoAssetIds = new ArrayList<>();
        if (avatarId != null) videoAssetIds.add(avatarId);
        List<ModelAsset> inlineAssets = new ArrayList<>();
        String script = stringValue(context, "script");

        if ("TEXT_TO_SPEECH".equals(audioSource) && "TTS".equals(voiceMode)) {
            String speechDeployment = context.selectedModelCode(ModelCapability.TEXT_TO_SPEECH);
            if (speechDeployment == null || speechDeployment.isBlank()) {
                throw new FeatureValidationException("selectedModels", "语音模型配置不完整");
            }
            TextToSpeechResponse speech = modelGateway.synthesizeSpeech(new TextToSpeechRequest(
                    context.tenantId(), context.runId(), SPEECH_ALIAS, speechDeployment,
                    script, enumValue(context, "voice", VOICES), numberValue(context, "speed", 1.0), "wav",
                    Map.of("featureCode", FEATURE_CODE, "emotion", enumValue(context, "emotion", EMOTIONS), "language", "zh")
            ));
            if (speech == null || speech.audio() == null || speech.audio().content().length == 0) {
                throw new ModelProviderException("PROVIDER_INVALID_RESPONSE", "语音模型没有返回可用音频", false);
            }
            GeneratedAudio audio = speech.audio();
            inlineAssets.add(new ModelAsset(UUID.randomUUID(), audio.fileName(), audio.mediaType(), audio.content()));
        } else if ("UPLOAD_AUDIO".equals(audioSource)) {
            videoAssetIds.add(uuidParameter(context, "audioFile", "请选择一个音频文件"));
        }

        String videoDeployment = context.selectedModelCode(ModelCapability.VIDEO_GENERATION);
        boolean heyGenVideo = "heygen-avatar-v-video".equals(videoDeployment);
        String prompt = heyGenVideo ? script : buildPrompt(context, script);
        String aspectRatio = enumValue(context, "aspectRatio", ASPECT_RATIOS);
        String resolution = enumValue(context, "resolution", RESOLUTIONS);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("featureCode", FEATURE_CODE);
        metadata.put("avatarSource", avatarSource);
        if (avatarId != null) metadata.put("avatarAssetId", avatarId.toString());
        if ("NAVTALK_BUILTIN".equals(avatarSource)) {
            metadata.put("navTalkAvatarId", stringValue(context, "navTalkAvatarId"));
        }
        if ("HEYGEN_BUILTIN".equals(avatarSource)) {
            metadata.put("avatarId", stringValue(context, "heygenAvatarId"));
            metadata.put("voiceId", stringValue(context, "heygenVoiceId"));
        }
        metadata.put("audioSource", audioSource);
        metadata.put("voiceGenerationMode", voiceMode);
        if ("TEXT_TO_SPEECH".equals(audioSource) && "VIDEO_NATIVE".equals(voiceMode)) {
            metadata.put("narrationText", script);
        }
        metadata.put("aspectRatio", aspectRatio);
        metadata.put("resolution", resolution);
        metadata.put("durationSeconds", durationSeconds(context, audioSource, script));
        metadata.put("fps", 30);
        metadata.put("negativePrompt", stringValue(context, "negativePrompt"));
        metadata.put("performancePrompt", stringValue(context, "performancePrompt"));
        metadata.put("inputAssetCount", videoAssetIds.size() + inlineAssets.size());

        VideoGenerationResponse response = modelGateway.generateVideo(new VideoGenerationRequest(
                context.tenantId(), context.runId(), VIDEO_ALIAS,
                videoDeployment, prompt,
                videoAssetIds, inlineAssets, durationSeconds(context, audioSource, script), aspectRatio, resolution, 1, metadata
        ));
        if (response == null || response.videos().isEmpty()) {
            throw new ModelProviderException("PROVIDER_INVALID_RESPONSE", "视频模型没有返回可保存的视频", false);
        }
        Map<String, Object> artifactMetadata = new LinkedHashMap<>(metadata);
        artifactMetadata.put("provider", response.provider());
        artifactMetadata.put("model", response.model());
        if (response.providerRequestId() != null) artifactMetadata.put("providerRequestId", response.providerRequestId());
        if (context.baseArtifact() != null) {
            artifactMetadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
            artifactMetadata.put("basedOnVersion", context.baseArtifact().versionNumber());
        }
        ArtifactDraft artifact = ArtifactDrafts.generatedVideos("数字人口播", response, artifactMetadata);
        return FeatureExecutionResult.of(artifact);
    }

    private static int durationSeconds(
            FeatureExecutionContext context,
            String audioSource,
            String script
    ) {
        int requested = intValue(context, "durationSeconds", 5);
        if ("TEXT_TO_SPEECH".equals(audioSource)
                && "VIDEO_NATIVE".equals(stringValue(context, "voiceGenerationMode"))) {
            int characters = script.codePointCount(0, script.length());
            int estimated = (int) Math.ceil(characters / 4.0);
            return Math.max(1, Math.min(60, Math.max(requested, estimated)));
        }
        return Math.max(1, Math.min(60, requested));
    }

    private static String buildPrompt(FeatureExecutionContext context, String script) {
        StringBuilder prompt = new StringBuilder();
        if (!script.isBlank()) prompt.append("口播内容：").append(script);
        String performance = stringValue(context, "performancePrompt");
        if (!performance.isBlank()) prompt.append("\n角色表现：").append(performance);
        String negative = stringValue(context, "negativePrompt");
        if (!negative.isBlank()) prompt.append("\n负面提示：").append(negative);
        return prompt.isEmpty() ? "生成自然、稳定、适合口播的数字人视频" : prompt.toString();
    }

    private static void requireKnownParameters(FeatureExecutionContext context) {
        Set<String> known = Set.of("avatarSource", "navTalkAvatarId", "heygenAvatarId", "heygenVoiceId", "avatarImage", "avatarPrompt", "avatarConfirmed", "audioSource", "script", "audioFile", "audioConfirmed", "voiceGenerationMode", "voice", "speed", "emotion", "aspectRatio", "resolution", "durationSeconds", "performancePrompt", "negativePrompt", "outputCount", "fps");
        if (!known.containsAll(context.parameters().keySet())) throw new FeatureValidationException("parameters", "数字人口播包含不支持的参数");
    }

    private static void validateAsset(FeatureExecutionContext context, UUID id, Set<String> mediaTypes, String field, String label) {
        var reference = context.inputAssets().stream().filter(asset -> id.equals(asset.id())).findFirst().orElse(null);
        if (reference == null) throw new FeatureValidationException(field, label + "没有包含在本次任务附件中");
        if (!mediaTypes.contains(reference.mediaType())) throw new FeatureValidationException(field, label + "格式不受支持");
    }

    private static void validateAssetSize(
            FeatureExecutionContext context,
            UUID id,
            long maximumBytes,
            String field,
            String message
    ) {
        var reference = context.inputAssets().stream().filter(asset -> id.equals(asset.id())).findFirst().orElse(null);
        if (reference != null && reference.sizeBytes() > maximumBytes) {
            throw new FeatureValidationException(field, message);
        }
    }

    private static String stringValue(FeatureExecutionContext context, String name) {
        Object value = context.parameters().get(name);
        return value == null ? "" : value.toString().trim();
    }

    private static String enumValue(FeatureExecutionContext context, String name, Set<String> allowed) {
        String value = stringValue(context, name);
        if (!allowed.contains(value)) throw new FeatureValidationException(name, "请选择有效的" + name);
        return value;
    }

    private static UUID uuidParameter(FeatureExecutionContext context, String name, String message) {
        String value = stringValue(context, name);
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException exception) { throw new FeatureValidationException(name, message); }
    }

    private static double numberValue(FeatureExecutionContext context, String name, double fallback) {
        Object value = context.parameters().get(name);
        if (value == null) return fallback;
        try { return value instanceof Number number ? number.doubleValue() : Double.parseDouble(value.toString()); }
        catch (NumberFormatException exception) { throw new FeatureValidationException(name, "请输入有效的数字"); }
    }

    private static int intValue(FeatureExecutionContext context, String name, int fallback) {
        Object value = context.parameters().get(name);
        if (value == null) return fallback;
        try { return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString()); }
        catch (NumberFormatException exception) { throw new FeatureValidationException(name, "请输入有效的整数"); }
    }
}
