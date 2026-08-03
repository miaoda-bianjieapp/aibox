package com.aibox.features.audio.enhancement;

import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.AudioEnhancementRequest;
import com.aibox.feature.spi.AudioEnhancementResponse;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.GeneratedAudio;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioEnhancementFeatureHandlerTest {

    @Test
    void returnsStoredAudioAndUsesTheOriginalInputForARevision() {
        UUID assetId = UUID.randomUUID();
        ArtifactReference base = new ArtifactReference(
                UUID.randomUUID(),
                1,
                "audio",
                "audio/mpeg",
                Map.of("assetId", UUID.randomUUID().toString(), "name", "voice-enhanced.mp3"),
                Map.of()
        );
        CapturingGateway gateway = new CapturingGateway();
        FeatureExecutionContext context = context(
                assetId,
                Map.of(
                        "audioFile", assetId.toString(),
                        "keepBackgroundMusic", true
                ),
                base
        );

        var artifact = new AudioEnhancementFeatureHandler()
                .execute(context, gateway)
                .artifacts()
                .get(0);

        assertEquals(assetId, gateway.request.inputAssetId());
        assertEquals(true, gateway.request.keepBackgroundMusic());
        assertEquals("auto", gateway.request.format());
        assertEquals("audio.enhancement.default", gateway.request.modelAlias());
        assertEquals(
                "cleanvoice-studio-sound-audio",
                gateway.request.deploymentCode()
        );
        assertEquals("audio", artifact.kind());
        assertEquals("audio/mpeg", artifact.mimeType());
        assertEquals("voice 人声降噪", artifact.title());
        assertEquals("voice-enhanced.mp3", artifact.content().get("name"));
        assertEquals(1, artifact.outputAssets().size());
        assertArrayEquals(new byte[]{4, 5, 6}, artifact.outputAssets().get(0).content());
        assertEquals(base.id().toString(), artifact.metadata().get("basedOnArtifactId"));
        assertEquals(assetId.toString(), artifact.metadata().get("sourceAssetId"));
    }

    @Test
    void rejectsUnsupportedOrMissingAudioInput() {
        UUID assetId = UUID.randomUUID();
        FeatureExecutionContext unsupported = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AudioEnhancementFeatureHandler.FEATURE_CODE,
                1,
                Map.of(
                        "audioFile", assetId.toString(),
                        "keepBackgroundMusic", false
                ),
                List.of(assetId),
                List.of(new InputAssetReference(
                        assetId,
                        "notes.txt",
                        "text/plain",
                        128
                )),
                Map.of(
                        ModelCapability.AUDIO_ENHANCEMENT.name(),
                        "cleanvoice-studio-sound-audio"
                ),
                null,
                null
        );

        assertThrows(
                FeatureValidationException.class,
                () -> new AudioEnhancementFeatureHandler().validate(unsupported)
        );
    }

    private static FeatureExecutionContext context(
            UUID assetId,
            Map<String, Object> parameters,
            ArtifactReference base
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AudioEnhancementFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                List.of(assetId),
                List.of(new InputAssetReference(
                        assetId,
                        "voice.wav",
                        "audio/wav",
                        1_024
                )),
                Map.of(
                        ModelCapability.AUDIO_ENHANCEMENT.name(),
                        "cleanvoice-studio-sound-audio"
                ),
                null,
                base
        );
    }

    private static final class CapturingGateway implements ModelGateway {
        private AudioEnhancementRequest request;

        @Override
        public TextGenerationResponse generateText(TextGenerationRequest request) {
            throw new AssertionError("Text generation was not expected");
        }

        @Override
        public AudioEnhancementResponse enhanceAudio(AudioEnhancementRequest request) {
            this.request = request;
            return new AudioEnhancementResponse(
                    new GeneratedAudio(
                            "voice-enhanced.mp3",
                            "audio/mpeg",
                            new byte[]{4, 5, 6}
                    ),
                    "cleanvoice-official",
                    "studio-sound",
                    "edit-1",
                    null,
                    null
            );
        }
    }
}
