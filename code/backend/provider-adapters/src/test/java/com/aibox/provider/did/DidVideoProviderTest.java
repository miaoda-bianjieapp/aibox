package com.aibox.provider.did;

import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.VideoGenerationRequest;
import com.aibox.feature.spi.VideoGenerationResponse;
import com.aibox.provider.openai.ModelProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DidVideoProviderTest {

    @Test
    void uploadsConfirmedImageAndAudioCreatesTalkPollsAndDownloadsVideo() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> imageUpload = new AtomicReference<>();
        AtomicReference<String> audioUpload = new AtomicReference<>();
        AtomicReference<JsonNode> createTalk = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/images", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            imageUpload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            respondJson(exchange, "{\"url\":\"https://assets.example/avatar.png\"}");
        });
        server.createContext("/audios", exchange -> {
            audioUpload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            respondJson(exchange, "{\"url\":\"https://assets.example/speech.wav\"}");
        });
        server.createContext("/talks/talk-1", exchange -> respondJson(
                exchange,
                "{\"id\":\"talk-1\",\"status\":\"done\",\"result_url\":\"http://127.0.0.1:"
                        + server.getAddress().getPort() + "/result.mp4\"}"
        ));
        server.createContext("/talks", exchange -> {
            createTalk.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            respondJson(exchange, "{\"id\":\"talk-1\",\"status\":\"created\"}");
        });
        server.createContext("/result.mp4", exchange -> {
            byte[] response = new byte[]{1, 2, 3, 4};
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DidVideoProvider provider = provider(server);
            ModelCallTarget target = target();
            VideoGenerationRequest request = new VideoGenerationRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "digital_human.video", "did-talks-v1-video",
                    "口播内容：Only the confirmed audio should drive speech.\\n负面提示：Do not read this",
                    List.of(), 12, "9:16", "720p", 1,
                    Map.of(
                            "voiceGenerationMode", "TTS",
                            "performancePrompt", "Natural expression",
                            "negativePrompt", "No subtitles"
                    )
            );
            List<ModelAsset> assets = List.of(
                    new ModelAsset(UUID.randomUUID(), "avatar.png", "image/png", new byte[]{9, 8, 7}),
                    new ModelAsset(UUID.randomUUID(), "speech.wav", "audio/wav", new byte[]{6, 5, 4})
            );

            VideoGenerationResponse response = provider.generateVideo(target, request, assets);

            assertEquals("Basic test-api-key", authorization.get());
            assertTrue(imageUpload.get().contains("name=\"image\""));
            assertTrue(imageUpload.get().contains("filename=\"avatar.png\""));
            assertTrue(audioUpload.get().contains("name=\"audio\""));
            assertTrue(audioUpload.get().contains("filename=\"speech.wav\""));
            assertEquals("https://assets.example/avatar.png", createTalk.get().path("source_url").asText());
            assertEquals("audio", createTalk.get().path("script").path("type").asText());
            assertEquals("https://assets.example/speech.wav", createTalk.get().path("script").path("audio_url").asText());
            assertFalse(createTalk.get().toString().contains("Only the confirmed audio should drive speech"));
            assertEquals("talk-1", response.providerRequestId());
            assertEquals("talks", response.model());
            assertEquals(4, response.videos().get(0).content().length);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNativeVoiceAndRequiresExactlyOneImageAndAudio() {
        DidVideoProvider provider = provider(null);
        ModelCallTarget target = target();
        VideoGenerationRequest nativeVoice = new VideoGenerationRequest(
                UUID.randomUUID(), UUID.randomUUID(), "digital_human.video", "did-talks-v1-video",
                "script", List.of(), 5, "9:16", "720p", 1,
                Map.of("voiceGenerationMode", "VIDEO_NATIVE")
        );

        ModelProviderException nativeException = assertThrows(
                ModelProviderException.class,
                () -> provider.generateVideo(target, nativeVoice, List.of(
                        new ModelAsset(UUID.randomUUID(), "avatar.png", "image/png", new byte[]{1})
                ))
        );
        assertEquals("MODEL_NATIVE_AUDIO_NOT_SUPPORTED", nativeException.code());

        VideoGenerationRequest externalAudio = new VideoGenerationRequest(
                UUID.randomUUID(), UUID.randomUUID(), "digital_human.video", "did-talks-v1-video",
                "script", List.of(), 5, "9:16", "720p", 1,
                Map.of("voiceGenerationMode", "TTS")
        );
        ModelProviderException missingAudio = assertThrows(
                ModelProviderException.class,
                () -> provider.generateVideo(target, externalAudio, List.of(
                        new ModelAsset(UUID.randomUUID(), "avatar.png", "image/png", new byte[]{1})
                ))
        );
        assertEquals("MODEL_AUDIO_INPUT_REQUIRED", missingAudio.code());
    }

    @Test
    void rejectsUnsupportedImageTypeAndOversizedAudioBeforeUpload() {
        DidVideoProvider provider = provider(null);
        ModelCallTarget target = target();
        VideoGenerationRequest request = new VideoGenerationRequest(
                UUID.randomUUID(), UUID.randomUUID(), "digital_human.video", "did-talks-v1-video",
                "script", List.of(), 5, "9:16", "720p", 1,
                Map.of("voiceGenerationMode", "TTS")
        );

        ModelProviderException imageType = assertThrows(
                ModelProviderException.class,
                () -> provider.generateVideo(target, request, List.of(
                        new ModelAsset(UUID.randomUUID(), "avatar.webp", "image/webp", new byte[]{1}),
                        new ModelAsset(UUID.randomUUID(), "speech.wav", "audio/wav", new byte[]{2})
                ))
        );
        assertEquals("PROVIDER_ASSET_TYPE_UNSUPPORTED", imageType.code());

        ModelProviderException audioSize = assertThrows(
                ModelProviderException.class,
                () -> provider.generateVideo(target, request, List.of(
                        new ModelAsset(UUID.randomUUID(), "avatar.png", "image/png", new byte[]{1}),
                        new ModelAsset(UUID.randomUUID(), "speech.wav", "audio/wav", new byte[6 * 1024 * 1024 + 1])
                ))
        );
        assertEquals("PROVIDER_FILE_TOO_LARGE", audioSize.code());
    }

    private static DidVideoProvider provider(HttpServer server) {
        ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
        configuration.setProtocol(DidVideoProvider.PROTOCOL);
        configuration.setBaseUrl(server == null ? "http://127.0.0.1:1" : "http://127.0.0.1:" + server.getAddress().getPort());
        configuration.setApiKey("test-api-key");
        configuration.setApiKeyHeader("Authorization");
        configuration.setApiKeyPrefix("Basic ");
        configuration.setImagePath("/images");
        configuration.setAudioPath("/audios");
        configuration.setVideoPath("/talks");
        configuration.setVideoStatusPath("/talks/{taskId}");
        ModelProviderProperties properties = new ModelProviderProperties();
        properties.setProviders(Map.of("d-id-official", configuration));
        return new DidVideoProvider(properties);
    }

    private static ModelCallTarget target() {
        return new ModelCallTarget(
                "did-talks-v1-video", "d-id-official", "talks", ModelCapability.VIDEO_GENERATION,
                Map.of(
                        "videoPollIntervalMillis", 1,
                        "videoPollTimeoutSeconds", 2,
                        "stitch", true
                )
        );
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
