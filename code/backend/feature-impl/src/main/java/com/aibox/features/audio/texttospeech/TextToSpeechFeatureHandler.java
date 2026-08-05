package com.aibox.features.audio.texttospeech;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.ArtifactDrafts;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureHandler;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.GeneratedAudio;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextToSpeechRequest;
import com.aibox.feature.spi.TextToSpeechResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public final class TextToSpeechFeatureHandler implements FeatureHandler {

    public static final String FEATURE_CODE = "audio.text_to_speech";

    private static final String MODEL_ALIAS = "speech.default";
    private static final String OUTPUT_FORMAT = "wav";
    private static final String OUTPUT_MIME_TYPE = "audio/wav";
    private static final int MAX_TEXT_CHARACTERS = 500;
    private static final Set<String> PARAMETERS = Set.of(
            "text", "voice", "speed", "emotion"
    );
    private static final Set<String> VOICES = Set.of("science_female", "gentle_female");
    private static final Map<String, String> VOICE_LABELS = Map.of(
            "science_female", "科普视频女声",
            "gentle_female", "温柔女声"
    );
    private static final double MIN_SPEED = 0.5;
    private static final double MAX_SPEED = 2.0;
    private static final double SPEED_STEP = 0.05;
    private static final Map<String, Double> LEGACY_SPEEDS = Map.of(
            "slow", 0.75,
            "normal", 1.0,
            "fast", 1.25,
            "very_fast", 1.5
    );
    private static final Set<String> EMOTIONS = Set.of("natural");
    private static final Map<String, String> EMOTION_LABELS = Map.of(
            "natural", "自然"
    );

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        if (!PARAMETERS.containsAll(context.parameters().keySet())) {
            throw new FeatureValidationException(
                    "parameters", "文字转语音包含不支持的参数"
            );
        }
        text(context);
        enumParameter(context, "voice", VOICES);
        speed(context);
        enumParameter(context, "emotion", EMOTIONS);
        if (!context.inputAssetIds().isEmpty() || !context.inputAssets().isEmpty()) {
            throw new FeatureValidationException(
                    "inputAssetIds", "文字转语音不接受附件"
            );
        }
        String deployment = context.selectedModelCode(ModelCapability.TEXT_TO_SPEECH);
        if (deployment == null || deployment.isBlank()) {
            throw new FeatureValidationException(
                    "selectedModels", "文字转语音模型配置不完整"
            );
        }
        if (context.baseArtifact() != null
                && !"audio".equals(context.baseArtifact().kind())
                && (context.baseArtifact().mimeType() == null
                || !context.baseArtifact().mimeType().startsWith("audio/"))) {
            throw new FeatureValidationException(
                    "baseArtifactId", "只能基于音频成果生成新版本"
            );
        }
    }

    @Override
    public FeatureExecutionResult execute(
            FeatureExecutionContext context,
            ModelGateway modelGateway
    ) {
        validate(context);
        String text = text(context);
        String voice = enumParameter(context, "voice", VOICES);
        double speed = speed(context);
        String emotion = enumParameter(context, "emotion", EMOTIONS);
        String deployment = context.selectedModelCode(ModelCapability.TEXT_TO_SPEECH);
        TextToSpeechResponse response = modelGateway.synthesizeSpeech(
                new TextToSpeechRequest(
                        context.tenantId(),
                        context.runId(),
                        MODEL_ALIAS,
                        deployment,
                        text,
                        voice,
                        speed,
                        OUTPUT_FORMAT,
                        Map.of(
                                "featureCode", FEATURE_CODE,
                                "language", "zh",
                                "emotion", emotion
                        )
                )
        );
        validateResponse(response);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("featureCode", FEATURE_CODE);
        metadata.put("textCharacters", text.codePointCount(0, text.length()));
        metadata.put("deploymentCode", deployment);
        metadata.put("voice", voice);
        metadata.put("voiceLabel", VOICE_LABELS.get(voice));
        metadata.put("speed", speed);
        metadata.put("speedLabel", speedLabel(speed));
        metadata.put("emotion", emotion);
        metadata.put("emotionLabel", EMOTION_LABELS.get(emotion));
        metadata.put("outputFormat", OUTPUT_FORMAT);
        if (context.baseArtifact() != null) {
            metadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
            metadata.put("basedOnVersion", context.baseArtifact().versionNumber());
        }

        ArtifactDraft artifact = ArtifactDrafts.generatedAudio(
                "文字转语音",
                response,
                Map.copyOf(metadata)
        );
        return FeatureExecutionResult.of(artifact);
    }

    private static String text(FeatureExecutionContext context) {
        Object value = context.parameters().get("text");
        if (!(value instanceof String stringValue) || stringValue.trim().isEmpty()) {
            throw new FeatureValidationException("text", "请输入需要合成的文字");
        }
        String text = stringValue.trim();
        if (text.codePointCount(0, text.length()) > MAX_TEXT_CHARACTERS) {
            throw new FeatureValidationException("text", "输入文字不能超过 500 字");
        }
        return text;
    }

    private static double speed(FeatureExecutionContext context) {
        Object value = context.parameters().get("speed");
        Double requested = null;
        if (value instanceof Number number) {
            requested = number.doubleValue();
        } else if (value instanceof String stringValue) {
            String normalized = stringValue.trim();
            requested = LEGACY_SPEEDS.get(normalized);
            if (requested == null && !normalized.isEmpty()) {
                try {
                    requested = Double.parseDouble(normalized);
                } catch (NumberFormatException ignored) {
                    // Report the stable validation error below.
                }
            }
        }
        if (requested == null || !Double.isFinite(requested)
                || requested < MIN_SPEED || requested > MAX_SPEED) {
            throw new FeatureValidationException(
                    "speed", "语速必须在 0.5 到 2.0 之间"
            );
        }
        double steps = (requested - MIN_SPEED) / SPEED_STEP;
        if (Math.abs(steps - Math.rint(steps)) > 0.000001) {
            throw new FeatureValidationException(
                    "speed", "语速必须按 0.05 调整"
            );
        }
        return Math.round(requested * 100.0) / 100.0;
    }

    private static String speedLabel(double speed) {
        String value = BigDecimal.valueOf(speed).stripTrailingZeros().toPlainString();
        if (!value.contains(".")) value += ".0";
        return value + "×";
    }

    private static String enumParameter(
            FeatureExecutionContext context,
            String field,
            Set<String> allowed
    ) {
        Object value = context.parameters().get(field);
        String normalized = value == null ? "" : value.toString().trim();
        if (!allowed.contains(normalized)) {
            throw new FeatureValidationException(field, "参数值无效");
        }
        return normalized;
    }

    private static void validateResponse(TextToSpeechResponse response) {
        GeneratedAudio audio = response == null ? null : response.audio();
        byte[] content = audio == null ? new byte[0] : audio.content();
        if (content.length == 0) {
            throw new ModelProviderException(
                    "PROVIDER_INVALID_RESPONSE",
                    "语音模型没有返回可用音频",
                    false
            );
        }
        if (audio.fileName() == null || audio.fileName().isBlank()
                || !OUTPUT_MIME_TYPE.equalsIgnoreCase(audio.mediaType())
                || !isWav(content)) {
            throw new ModelProviderException(
                    "PROVIDER_INVALID_AUDIO_FORMAT",
                    "语音模型没有返回有效的 WAV 音频",
                    false
            );
        }
    }

    private static boolean isWav(byte[] content) {
        return content.length >= 12
                && content[0] == 'R'
                && content[1] == 'I'
                && content[2] == 'F'
                && content[3] == 'F'
                && content[8] == 'W'
                && content[9] == 'A'
                && content[10] == 'V'
                && content[11] == 'E';
    }
}
