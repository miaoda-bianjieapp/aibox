package com.aibox.features.video.digital_human;

import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.GeneratedAudio;
import com.aibox.feature.spi.GeneratedVideo;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.TextToSpeechRequest;
import com.aibox.feature.spi.TextToSpeechResponse;
import com.aibox.feature.spi.VideoGenerationRequest;
import com.aibox.feature.spi.VideoGenerationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DigitalHumanFeatureHandlerMockTest {

    @Test
    void navTalkBuiltinMockPassesSelectedAvatarIdInMetadata() {
        AtomicReference<VideoGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                return new TextGenerationResponse("unused", "mock", "mock-text", "mock-text-request", 0, 0);
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                captured.set(request);
                return new VideoGenerationResponse(
                        List.of(new GeneratedVideo(
                                null, "mock-navtalk.mp4", "video/mp4", new byte[]{1, 2, 3}
                        )),
                        "mock-provider", "navtalk-video-compose", "mock-request", 0, 3
                );
            }
        };
        String navTalkAvatarId = "avatar-selected-by-user";
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DigitalHumanFeatureHandler.FEATURE_CODE, 8,
                Map.ofEntries(
                        Map.entry("avatarSource", "NAVTALK_BUILTIN"),
                        Map.entry("navTalkAvatarId", navTalkAvatarId),
                        Map.entry("avatarConfirmed", true),
                        Map.entry("audioSource", "TEXT_TO_SPEECH"),
                        Map.entry("script", "NavTalk mock narration"),
                        Map.entry("audioConfirmed", true),
                        Map.entry("voiceGenerationMode", "VIDEO_NATIVE"),
                        Map.entry("aspectRatio", "9:16"),
                        Map.entry("resolution", "720p"),
                        Map.entry("durationSeconds", 5),
                        Map.entry("outputCount", 1),
                        Map.entry("fps", 30)
                ),
                List.of(), List.of(),
                Map.of("VIDEO_GENERATION", "navtalk-video-compose"),
                "navtalk-video-compose", null
        );

        FeatureExecutionResult result = new DigitalHumanFeatureHandler().execute(context, gateway);

        assertNotNull(result);
        assertNotNull(captured.get());
        assertEquals(navTalkAvatarId, captured.get().metadata().get("navTalkAvatarId"));
        assertEquals("NAVTALK_BUILTIN", captured.get().metadata().get("avatarSource"));
    }

    @Test
    void heygenBuiltinMockUsesIdsAndDoesNotSendReferenceAsset() {
        AtomicReference<VideoGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                return new TextGenerationResponse("unused", "mock", "mock-text", "mock-text-request", 0, 0);
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                captured.set(request);
                return new VideoGenerationResponse(
                        List.of(new GeneratedVideo(null, "mock-heygen.mp4", "video/mp4", new byte[]{9, 8, 7})),
                        "mock-provider", "avatar_v", "heygen-task-1", 0, 3
                );
            }
        };
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DigitalHumanFeatureHandler.FEATURE_CODE, 9,
                Map.ofEntries(
                        Map.entry("avatarSource", "HEYGEN_BUILTIN"),
                        Map.entry("heygenAvatarId", "heygen-avatar-1"),
                        Map.entry("heygenVoiceId", "heygen-voice-1"),
                        Map.entry("avatarConfirmed", true),
                        Map.entry("audioSource", "TEXT_TO_SPEECH"),
                        Map.entry("script", "HeyGen mock digital human"),
                        Map.entry("audioConfirmed", true),
                        Map.entry("voiceGenerationMode", "VIDEO_NATIVE"),
                        Map.entry("aspectRatio", "16:9"),
                        Map.entry("resolution", "720p"),
                        Map.entry("durationSeconds", 5),
                        Map.entry("outputCount", 1),
                        Map.entry("fps", 30)
                ),
                List.of(), List.of(),
                Map.of("VIDEO_GENERATION", "heygen-avatar-v-video"),
                "heygen-avatar-v-video", null
        );

        FeatureExecutionResult result = new DigitalHumanFeatureHandler().execute(context, gateway);

        assertNotNull(result);
        assertNotNull(captured.get());
        assertEquals(List.of(), captured.get().inputAssetIds());
        assertEquals("heygen-avatar-1", captured.get().metadata().get("avatarId"));
        assertEquals("heygen-voice-1", captured.get().metadata().get("voiceId"));
    }

    @Test
    void heygenBuiltinAcceptsSquareAspectRatio() {
        AtomicReference<VideoGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                return new TextGenerationResponse("unused", "mock", "mock-text", "mock-text-request", 0, 0);
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                captured.set(request);
                return new VideoGenerationResponse(
                        List.of(new GeneratedVideo(null, "mock-heygen-square.mp4", "video/mp4", new byte[]{1})),
                        "mock-provider", "avatar_v", "heygen-square-task", 0, 1
                );
            }
        };
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DigitalHumanFeatureHandler.FEATURE_CODE, 9,
                Map.ofEntries(
                        Map.entry("avatarSource", "HEYGEN_BUILTIN"),
                        Map.entry("heygenAvatarId", "heygen-avatar-square"),
                        Map.entry("heygenVoiceId", "heygen-voice-square"),
                        Map.entry("avatarConfirmed", true),
                        Map.entry("audioSource", "TEXT_TO_SPEECH"),
                        Map.entry("script", "Square video narration"),
                        Map.entry("audioConfirmed", true),
                        Map.entry("voiceGenerationMode", "VIDEO_NATIVE"),
                        Map.entry("aspectRatio", "1:1"),
                        Map.entry("resolution", "1080p"),
                        Map.entry("durationSeconds", 5),
                        Map.entry("outputCount", 1),
                        Map.entry("fps", 30)
                ),
                List.of(), List.of(),
                Map.of("VIDEO_GENERATION", "heygen-avatar-v-video"),
                "heygen-avatar-v-video", null
        );

        new DigitalHumanFeatureHandler().execute(context, gateway);

        assertNotNull(captured.get());
        assertEquals("1:1", captured.get().aspectRatio());
    }

    @Test
    void heygenBuiltinKeepsNarrationSeparateFromDirectingPrompts() {
        AtomicReference<VideoGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                return new TextGenerationResponse("unused", "mock", "mock-text", "mock-text-request", 0, 0);
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                captured.set(request);
                return new VideoGenerationResponse(
                        List.of(new GeneratedVideo(null, "mock-heygen-safe.mp4", "video/mp4", new byte[]{1})),
                        "mock-provider", "avatar_v", "heygen-safe-task", 0, 1
                );
            }
        };
        String narration = "Only this sentence should be spoken.";
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DigitalHumanFeatureHandler.FEATURE_CODE, 9,
                Map.ofEntries(
                        Map.entry("avatarSource", "HEYGEN_BUILTIN"),
                        Map.entry("heygenAvatarId", "heygen-avatar-safe"),
                        Map.entry("heygenVoiceId", "heygen-voice-safe"),
                        Map.entry("avatarConfirmed", true),
                        Map.entry("audioSource", "TEXT_TO_SPEECH"),
                        Map.entry("script", narration),
                        Map.entry("audioConfirmed", true),
                        Map.entry("voiceGenerationMode", "VIDEO_NATIVE"),
                        Map.entry("aspectRatio", "16:9"),
                        Map.entry("resolution", "720p"),
                        Map.entry("durationSeconds", 5),
                        Map.entry("performancePrompt", "Smile and wave to the camera"),
                        Map.entry("negativePrompt", "Do not show subtitles"),
                        Map.entry("outputCount", 1),
                        Map.entry("fps", 30)
                ),
                List.of(), List.of(),
                Map.of("VIDEO_GENERATION", "heygen-avatar-v-video"),
                "heygen-avatar-v-video", null
        );

        new DigitalHumanFeatureHandler().execute(context, gateway);

        assertNotNull(captured.get());
        assertEquals(narration, captured.get().prompt());
        assertEquals(narration, captured.get().metadata().get("narrationText"));
        assertEquals("Smile and wave to the camera", captured.get().metadata().get("performancePrompt"));
        assertEquals("Do not show subtitles", captured.get().metadata().get("negativePrompt"));
    }

    @Test
    void customNavTalkAcceptsUploadedAvatarAndAudio() {
        UUID avatarId = UUID.randomUUID();
        UUID audioId = UUID.randomUUID();
        AtomicReference<VideoGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                return new TextGenerationResponse("unused", "mock", "mock-text", "mock-text-request", 0, 0);
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                captured.set(request);
                return new VideoGenerationResponse(
                        List.of(new GeneratedVideo(null, "mock-uploaded-media.mp4", "video/mp4", new byte[]{1, 2})),
                        "mock-provider", "video-compose", "upload-media-task", 0, 2
                );
            }
        };
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DigitalHumanFeatureHandler.FEATURE_CODE, 10,
                Map.ofEntries(
                        Map.entry("avatarSource", "UPLOAD"),
                        Map.entry("avatarImage", avatarId.toString()),
                        Map.entry("avatarConfirmed", true),
                        Map.entry("audioSource", "UPLOAD_AUDIO"),
                        Map.entry("audioFile", audioId.toString()),
                        Map.entry("audioConfirmed", true),
                        Map.entry("aspectRatio", "16:9"),
                        Map.entry("resolution", "720p"),
                        Map.entry("durationSeconds", 5),
                        Map.entry("outputCount", 1),
                        Map.entry("fps", 30)
                ),
                List.of(avatarId, audioId),
                List.of(
                        new InputAssetReference(avatarId, "avatar.png", "image/png", 1024),
                        new InputAssetReference(audioId, "speech.wav", "audio/wav", 2048)
                ),
                Map.of("VIDEO_GENERATION", "navtalk-video-compose-custom"),
                "navtalk-video-compose-custom", null
        );

        FeatureExecutionResult result = new DigitalHumanFeatureHandler().execute(context, gateway);

        assertNotNull(result);
        assertNotNull(captured.get());
        assertEquals(List.of(avatarId, audioId), captured.get().inputAssetIds());
        assertEquals("UPLOAD", captured.get().metadata().get("avatarSource"));
        assertEquals("UPLOAD_AUDIO", captured.get().metadata().get("audioSource"));
    }

    @Test
    void customNavTalkRejectsNativeAudioBecauseItRequiresExternalAudio() {
        UUID avatarId = UUID.randomUUID();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                return new TextGenerationResponse("unused", "mock", "mock-text", "mock-text-request", 0, 0);
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                throw new AssertionError("custom NavTalk must reject native audio before provider invocation");
            }
        };
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DigitalHumanFeatureHandler.FEATURE_CODE, 10,
                Map.ofEntries(
                        Map.entry("avatarSource", "UPLOAD"),
                        Map.entry("avatarImage", avatarId.toString()),
                        Map.entry("avatarConfirmed", true),
                        Map.entry("audioSource", "TEXT_TO_SPEECH"),
                        Map.entry("script", "Uploaded avatar narration"),
                        Map.entry("audioConfirmed", true),
                        Map.entry("voiceGenerationMode", "VIDEO_NATIVE"),
                        Map.entry("aspectRatio", "16:9"),
                        Map.entry("resolution", "720p"),
                        Map.entry("durationSeconds", 5),
                        Map.entry("outputCount", 1),
                        Map.entry("fps", 30)
                ),
                List.of(avatarId),
                List.of(new InputAssetReference(avatarId, "avatar.png", "image/png", 1024)),
                Map.of("VIDEO_GENERATION", "navtalk-video-compose-custom"),
                "navtalk-video-compose-custom", null
        );

        assertThrows(FeatureValidationException.class, () -> new DigitalHumanFeatureHandler().execute(context, gateway));
    }

    @Test
    void didUsesUploadedAvatarAndConfirmedTtsAudio() {
        UUID avatarId = UUID.randomUUID();
        AtomicInteger ttsCalls = new AtomicInteger();
        AtomicReference<VideoGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                return new TextGenerationResponse("unused", "mock", "mock-text", "mock-text-request", 0, 0);
            }

            @Override
            public TextToSpeechResponse synthesizeSpeech(TextToSpeechRequest request) {
                ttsCalls.incrementAndGet();
                return new TextToSpeechResponse(
                        new GeneratedAudio("confirmed.wav", "audio/wav", new byte[]{4, 5, 6}),
                        "mock-tts", "mock-voice", "tts-request", 0, 3
                );
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                captured.set(request);
                return new VideoGenerationResponse(
                        List.of(new GeneratedVideo(null, "did.mp4", "video/mp4", new byte[]{1, 2, 3})),
                        "d-id-official", "talks", "did-talk-1", 0, 3
                );
            }
        };
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DigitalHumanFeatureHandler.FEATURE_CODE, 13,
                Map.ofEntries(
                        Map.entry("avatarSource", "UPLOAD"),
                        Map.entry("avatarImage", avatarId.toString()),
                        Map.entry("avatarConfirmed", true),
                        Map.entry("audioSource", "TEXT_TO_SPEECH"),
                        Map.entry("script", "D-ID confirmed narration"),
                        Map.entry("audioConfirmed", true),
                        Map.entry("voiceGenerationMode", "TTS"),
                        Map.entry("voice", "gentle_female"),
                        Map.entry("speed", 1.0),
                        Map.entry("emotion", "natural"),
                        Map.entry("aspectRatio", "9:16"),
                        Map.entry("resolution", "720p"),
                        Map.entry("durationSeconds", 5),
                        Map.entry("outputCount", 1),
                        Map.entry("fps", 30)
                ),
                List.of(avatarId),
                List.of(new InputAssetReference(avatarId, "avatar.png", "image/png", 1024)),
                Map.of(
                        "TEXT_TO_SPEECH", "mock-tts-model",
                        "VIDEO_GENERATION", "did-talks-v1-video"
                ),
                "did-talks-v1-video", null
        );

        new DigitalHumanFeatureHandler().execute(context, gateway);

        assertEquals(1, ttsCalls.get());
        assertNotNull(captured.get());
        assertEquals("did-talks-v1-video", captured.get().deploymentCode());
        assertEquals(List.of(avatarId), captured.get().inputAssetIds());
        assertEquals(1, captured.get().inlineInputAssets().size());
        assertEquals("audio/wav", captured.get().inlineInputAssets().get(0).mediaType());
        assertEquals("TTS", captured.get().metadata().get("voiceGenerationMode"));
    }

    @Test
    void didRejectsVideoNativeVoiceUntilAudioPreviewCapabilityIsConfirmed() {
        UUID avatarId = UUID.randomUUID();
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DigitalHumanFeatureHandler.FEATURE_CODE, 13,
                Map.ofEntries(
                        Map.entry("avatarSource", "UPLOAD"),
                        Map.entry("avatarImage", avatarId.toString()),
                        Map.entry("avatarConfirmed", true),
                        Map.entry("audioSource", "TEXT_TO_SPEECH"),
                        Map.entry("script", "Do not enable native voice yet"),
                        Map.entry("audioConfirmed", true),
                        Map.entry("voiceGenerationMode", "VIDEO_NATIVE"),
                        Map.entry("aspectRatio", "9:16"),
                        Map.entry("resolution", "720p"),
                        Map.entry("durationSeconds", 5),
                        Map.entry("outputCount", 1),
                        Map.entry("fps", 30)
                ),
                List.of(avatarId),
                List.of(new InputAssetReference(avatarId, "avatar.png", "image/png", 1024)),
                Map.of("VIDEO_GENERATION", "did-talks-v1-video"),
                "did-talks-v1-video", null
        );

        assertThrows(
                FeatureValidationException.class,
                () -> new DigitalHumanFeatureHandler().execute(context, request -> {
                    throw new AssertionError("D-ID native voice must be rejected before provider invocation");
                })
        );
    }

    @Test
    void soraNativeVoiceRejectsUploadedAudioAndUsesScriptInstead() {
        UUID avatarId = UUID.randomUUID();
        UUID audioId = UUID.randomUUID();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                return new TextGenerationResponse("unused", "mock", "mock-text", "mock-text-request", 0, 0);
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                throw new AssertionError("Sora native voice must reject external audio before provider invocation");
            }
        };
        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DigitalHumanFeatureHandler.FEATURE_CODE, 11,
                Map.ofEntries(
                        Map.entry("avatarSource", "UPLOAD"),
                        Map.entry("avatarImage", avatarId.toString()),
                        Map.entry("avatarConfirmed", true),
                        Map.entry("audioSource", "UPLOAD_AUDIO"),
                        Map.entry("audioFile", audioId.toString()),
                        Map.entry("audioConfirmed", true),
                        Map.entry("aspectRatio", "16:9"),
                        Map.entry("resolution", "720p"),
                        Map.entry("durationSeconds", 5),
                        Map.entry("outputCount", 1),
                        Map.entry("fps", 30)
                ),
                List.of(avatarId, audioId),
                List.of(
                        new InputAssetReference(avatarId, "avatar.png", "image/png", 1024),
                        new InputAssetReference(audioId, "speech.wav", "audio/wav", 2048)
                ),
                Map.of("VIDEO_GENERATION", "openai2api-sora-2-video"),
                "openai2api-sora-2-video", null
        );

        assertThrows(FeatureValidationException.class, () -> new DigitalHumanFeatureHandler().execute(context, gateway));
    }

    @Test
    void soraNativeAudioUsesUploadedAvatarAndCreatesVideoWithoutTts() {
        UUID avatarId = UUID.randomUUID();
        AtomicInteger videoCalls = new AtomicInteger();
        AtomicInteger ttsCalls = new AtomicInteger();
        AtomicReference<VideoGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                return new TextGenerationResponse("unused", "mock", "mock-text", "mock-text-request", 0, 0);
            }

            @Override
            public TextToSpeechResponse synthesizeSpeech(TextToSpeechRequest request) {
                ttsCalls.incrementAndGet();
                throw new AssertionError("VIDEO_NATIVE must not invoke TTS");
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                videoCalls.incrementAndGet();
                captured.set(request);
                return new VideoGenerationResponse(
                        List.of(new GeneratedVideo(
                                null,
                                "mock-digital-human.mp4",
                                "video/mp4",
                                new byte[]{1, 2, 3, 4}
                        )),
                        "mock-provider",
                        "sora-2",
                        "mock-video-request",
                        0,
                        4
                );
            }
        };

        FeatureExecutionContext context = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DigitalHumanFeatureHandler.FEATURE_CODE,
                11,
                Map.ofEntries(
                        Map.entry("avatarSource", "UPLOAD"),
                        Map.entry("avatarImage", avatarId.toString()),
                        Map.entry("avatarConfirmed", true),
                        Map.entry("audioSource", "TEXT_TO_SPEECH"),
                        Map.entry("script", "Speak this narration directly"),
                        Map.entry("audioConfirmed", true),
                        Map.entry("voiceGenerationMode", "VIDEO_NATIVE"),
                        Map.entry("aspectRatio", "16:9"),
                        Map.entry("resolution", "720p"),
                        Map.entry("durationSeconds", 8),
                        Map.entry("performancePrompt", "Natural presenter gestures"),
                        Map.entry("negativePrompt", "No subtitles"),
                        Map.entry("outputCount", 1),
                        Map.entry("fps", 30)
                ),
                List.of(avatarId),
                List.of(new InputAssetReference(
                        avatarId, "avatar.png", "image/png", 1024
                )),
                Map.of(
                        "IMAGE_GENERATION", "mock-image-model",
                        "TEXT_TO_SPEECH", "mock-tts-model",
                        "VIDEO_GENERATION", "openai2api-sora-2-video"
                ),
                "openai2api-sora-2-video",
                null
        );

        FeatureExecutionResult result = new DigitalHumanFeatureHandler().execute(context, gateway);

        assertEquals(0, ttsCalls.get());
        assertEquals(1, videoCalls.get());
        assertNotNull(captured.get());
        assertEquals(8, captured.get().durationSeconds());
        assertEquals(List.of(avatarId), captured.get().inputAssetIds());
        assertEquals("16:9", captured.get().aspectRatio());
        assertEquals("720p", captured.get().resolution());
        assertEquals("openai2api-sora-2-video", captured.get().deploymentCode());
        assertEquals("Speak this narration directly", captured.get().metadata().get("narrationText"));
        assertFalse(result.artifacts().isEmpty());
        assertEquals("video", result.artifacts().get(0).kind());
        assertEquals(1, result.artifacts().get(0).outputAssets().size());
        assertEquals("video/mp4", result.artifacts().get(0).outputAssets().get(0).mediaType());
    }
}
