package com.aibox.features.audio.texttospeech;

import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.GeneratedAudio;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.TextToSpeechRequest;
import com.aibox.feature.spi.TextToSpeechResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextToSpeechFeatureHandlerTest {

    @Test
    void generatesStoredWavAudioWithTheSelectedBusinessParameters() {
        ArtifactReference base = new ArtifactReference(
                UUID.randomUUID(),
                2,
                "audio",
                "audio/wav",
                Map.of("assetId", UUID.randomUUID().toString(), "name", "speech.wav"),
                Map.of()
        );
        CapturingGateway gateway = new CapturingGateway();
        FeatureExecutionContext context = context(
                Map.of(
                        "text", "今天适合学习新的知识。",
                        "voice", "science_female",
                        "speed", 1.25,
                        "emotion", "natural"
                ),
                base
        );

        var artifact = new TextToSpeechFeatureHandler()
                .execute(context, gateway)
                .artifacts()
                .get(0);

        assertEquals("speech.default", gateway.request.modelAlias());
        assertEquals(
                "openai2api-gpt-sovits-v2-tts",
                gateway.request.deploymentCode()
        );
        assertEquals("今天适合学习新的知识。", gateway.request.text());
        assertEquals("science_female", gateway.request.voice());
        assertEquals(1.25, gateway.request.speed());
        assertEquals("wav", gateway.request.format());
        assertEquals("natural", gateway.request.metadata().get("emotion"));
        assertEquals("audio", artifact.kind());
        assertEquals("audio/wav", artifact.mimeType());
        assertEquals("文字转语音", artifact.title());
        assertEquals("speech.wav", artifact.content().get("name"));
        assertEquals(1, artifact.outputAssets().size());
        assertEquals("assetId", artifact.outputAssets().get(0).contentField());
        assertArrayEquals(
                new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'},
                artifact.outputAssets().get(0).content()
        );
        assertEquals(base.id().toString(), artifact.metadata().get("basedOnArtifactId"));
        assertEquals(2, artifact.metadata().get("basedOnVersion"));
        assertEquals(
                "openai2api-gpt-sovits-v2-tts",
                artifact.metadata().get("deploymentCode")
        );
        assertEquals("科普视频女声", artifact.metadata().get("voiceLabel"));
        assertEquals(1.25, artifact.metadata().get("speed"));
        assertEquals("1.25×", artifact.metadata().get("speedLabel"));
        assertEquals("自然", artifact.metadata().get("emotionLabel"));
    }

    @Test
    void rejectsTextOverFiveHundredCharacters() {
        String text = "文".repeat(501);
        FeatureExecutionContext context = context(
                Map.of(
                        "text", text,
                        "voice", "gentle_female",
                        "speed", "normal",
                        "emotion", "natural"
                ),
                null
        );

        FeatureValidationException exception = assertThrows(
                FeatureValidationException.class,
                () -> new TextToSpeechFeatureHandler().validate(context)
        );

        assertEquals("text", exception.field());
    }

    @Test
    void rejectsUnsupportedParameters() {
        FeatureExecutionContext context = context(
                Map.of(
                        "text", "你好",
                        "voice", "gentle_female",
                        "speed", "normal",
                        "emotion", "natural",
                        "providerVoiceId", "must-not-be-accepted"
                ),
                null
        );

        FeatureValidationException exception = assertThrows(
                FeatureValidationException.class,
                () -> new TextToSpeechFeatureHandler().validate(context)
        );

        assertEquals("parameters", exception.field());
    }

    @Test
    void acceptsLegacySpeedEnumsForExistingRuns() {
        CapturingGateway gateway = new CapturingGateway();
        new TextToSpeechFeatureHandler().execute(
                context(
                        Map.of(
                                "text", "兼容旧任务",
                                "voice", "gentle_female",
                                "speed", "very_fast",
                                "emotion", "natural"
                        ),
                        null
                ),
                gateway
        );

        assertEquals(1.5, gateway.request.speed());
    }

    @Test
    void rejectsSpeedOutsideTheSliderRange() {
        FeatureExecutionContext context = context(
                Map.of(
                        "text", "你好",
                        "voice", "gentle_female",
                        "speed", 2.05,
                        "emotion", "natural"
                ),
                null
        );

        FeatureValidationException exception = assertThrows(
                FeatureValidationException.class,
                () -> new TextToSpeechFeatureHandler().validate(context)
        );

        assertEquals("speed", exception.field());
    }

    @Test
    void rejectsSpeedThatDoesNotMatchTheSliderStep() {
        FeatureExecutionContext context = context(
                Map.of(
                        "text", "你好",
                        "voice", "gentle_female",
                        "speed", 1.03,
                        "emotion", "natural"
                ),
                null
        );

        FeatureValidationException exception = assertThrows(
                FeatureValidationException.class,
                () -> new TextToSpeechFeatureHandler().validate(context)
        );

        assertEquals("speed", exception.field());
    }

    @Test
    void rejectsInvalidWavContent() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.response = new TextToSpeechResponse(
                new GeneratedAudio("speech.wav", "audio/wav", new byte[]{1, 2, 3}),
                "openai2api-tts-relay",
                "gpt-sovits-v2",
                "speech-1",
                null,
                null
        );

        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> new TextToSpeechFeatureHandler().execute(
                        context(
                                Map.of(
                                        "text", "你好",
                                        "voice", "gentle_female",
                                        "speed", "normal",
                                        "emotion", "natural"
                                ),
                                null
                        ),
                        gateway
                )
        );

        assertEquals("PROVIDER_INVALID_AUDIO_FORMAT", exception.code());
        assertEquals(false, exception.retryable());
    }

    private static FeatureExecutionContext context(
            Map<String, Object> parameters,
            ArtifactReference baseArtifact
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "audio.text_to_speech",
                1,
                parameters,
                List.of(),
                List.of(),
                Map.of(
                        ModelCapability.TEXT_TO_SPEECH.name(),
                        "openai2api-gpt-sovits-v2-tts"
                ),
                null,
                baseArtifact
        );
    }

    private static final class CapturingGateway implements ModelGateway {
        private TextToSpeechRequest request;
        private TextToSpeechResponse response = new TextToSpeechResponse(
                new GeneratedAudio(
                        "speech.wav",
                        "audio/wav",
                        new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'}
                ),
                "openai2api-tts-relay",
                "gpt-sovits-v2",
                "speech-1",
                null,
                null
        );

        @Override
        public TextGenerationResponse generateText(TextGenerationRequest request) {
            throw new AssertionError("Text generation was not expected");
        }

        @Override
        public TextToSpeechResponse synthesizeSpeech(TextToSpeechRequest request) {
            this.request = request;
            return response;
        }
    }
}
