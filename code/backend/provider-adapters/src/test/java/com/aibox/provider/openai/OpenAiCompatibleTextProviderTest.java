package com.aibox.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aibox.feature.spi.ImageExpansionRequest;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImagePreservationMode;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.TextToSpeechRequest;
import com.aibox.feature.spi.TextToSpeechResponse;
import com.aibox.feature.spi.VideoGenerationRequest;
import com.aibox.feature.spi.VideoGenerationResponse;
import com.aibox.feature.spi.VideoGenerationLifecycleListener;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleTextProviderTest {

    private static final ObjectMapper TEST_MAPPER = new ObjectMapper();

    @Test
    void streamsOpenAiCompatibleTextDeltasAndCollectsFinalResponse() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    data: {"id":"chat-1","model":"test-model","choices":[{"delta":{"content":"Hello"}}]}

                    data: {"id":"chat-1","model":"test-model","choices":[{"delta":{"content":" world"}}]}

                    data: {"id":"chat-1","model":"test-model","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("test-provider", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "test-text",
                    "test-provider",
                    "test-model",
                    ModelCapability.TEXT_GENERATION,
                    Map.of()
            );
            List<String> deltas = new ArrayList<>();

            TextGenerationResponse result = provider.generateTextStream(
                    target,
                    new TextGenerationRequest(
                            UUID.randomUUID(), UUID.randomUUID(), "text.default", "test-text",
                            "system", "user", 100, 0.5, Map.of()
                    ),
                    delta -> {
                        deltas.add(delta);
                        return true;
                    }
            );

            assertEquals(List.of("Hello", " world"), deltas);
            assertEquals("Hello world", result.text());
            assertEquals("chat-1", result.providerRequestId());
            assertEquals(3, result.inputTokens());
            assertEquals(2, result.outputTokens());
            assertTrue(capturedBody.get().contains("\"stream\":true"));
            assertTrue(capturedBody.get().contains("\"max_tokens\":100"));
            assertFalse(capturedBody.get().contains("\"max_output_tokens\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void textGenerationUsesDeploymentMaxTokensParameter() throws IOException {
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedBody.set(TEST_MAPPER.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {
                      "id":"chat-1",
                      "model":"test-model",
                      "choices":[{"message":{"role":"assistant","content":"OK"}}]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("test-provider", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "test-text",
                    "test-provider",
                    "test-model",
                    ModelCapability.TEXT_GENERATION,
                    Map.of("maxTokensParameter", "max_output_tokens")
            );

            provider.generateText(
                    target,
                    new TextGenerationRequest(
                            UUID.randomUUID(), UUID.randomUUID(), "text.default", "test-text",
                            "system", "user", 100, 0.5, Map.of()
                    )
            );

            assertEquals(100, capturedBody.get().path("max_output_tokens").asInt());
            assertFalse(capturedBody.get().has("max_tokens"));
            assertEquals(0.5, capturedBody.get().path("temperature").asDouble());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void textGenerationRejectsUnsupportedMaxTokensParameter() {
        ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
        configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
        configuration.setBaseUrl("http://127.0.0.1:1");
        configuration.setApiKey("test-key");
        ModelProviderProperties properties = new ModelProviderProperties();
        properties.setProviders(Map.of("test-provider", configuration));
        OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
        ModelCallTarget target = new ModelCallTarget(
                "test-text",
                "test-provider",
                "test-model",
                ModelCapability.TEXT_GENERATION,
                Map.of("maxTokensParameter", "unexpected_parameter")
        );

        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> provider.generateText(
                        target,
                        new TextGenerationRequest(
                                UUID.randomUUID(), UUID.randomUUID(),
                                "text.default", "test-text",
                                "system", "user", 100, 0.5, Map.of()
                        )
                )
        );

        assertEquals("PROVIDER_CONFIG_INVALID", exception.code());
        assertFalse(exception.retryable());
    }

    @Test
    void agnesImageUsesJsonReferencesAndAspectRatio() throws IOException {
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            capturedBody.set(TEST_MAPPER.readTree(exchange.getRequestBody()));
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = (
                    "{\"data\":[{\"b64_json\":\""
                            + Base64.getEncoder().encodeToString(new byte[]{7, 8, 9})
                            + "\",\"media_type\":\"image/png\"}]}"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            configuration.setImagePath("/v1/images/generations");
            configuration.setImageEditPath("/v1/images/generations");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("agnes-ai-official", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            UUID assetId = UUID.randomUUID();
            ModelCallTarget target = new ModelCallTarget(
                    "agnes-image-2-1",
                    "agnes-ai-official",
                    "agnes-image-2.1-flash",
                    ModelCapability.IMAGE_GENERATION,
                    Map.of(
                            "imageProtocol", "agnes-json",
                            "imageResolution", "2K",
                            "maxReferenceImages", 9
                    )
            );

            var response = provider.generateImage(
                    target,
                    new ImageGenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "image.agnes.default",
                            "agnes-image-2-1",
                            "Keep the subject and change the setting",
                            List.of(assetId),
                            "16:9",
                            1,
                            Map.of()
                    ),
                    List.of(new ModelAsset(
                            assetId,
                            "reference.png",
                            "image/png",
                            new byte[]{1, 2, 3}
                    ))
            );

            assertTrue(capturedContentType.get().startsWith("application/json"));
            assertEquals("agnes-image-2.1-flash", capturedBody.get().path("model").asText());
            assertEquals("2K", capturedBody.get().path("size").asText());
            assertEquals("16:9", capturedBody.get().path("extra_body").path("aspect_ratio").asText());
            assertEquals(
                    Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}),
                    capturedBody.get().path("extra_body").path("image").path(0).asText()
            );
            assertTrue(Arrays.equals(new byte[]{7, 8, 9}, response.images().get(0).content()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unifiedTtsUsesApiKeyHeaderAndVoiceIdPayload() throws IOException {
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuthorization = new AtomicReference<>();
        AtomicReference<String> capturedApiKey = new AtomicReference<>();
        AtomicReference<String> capturedContentLength = new AtomicReference<>();
        AtomicReference<String> capturedTransferEncoding = new AtomicReference<>();
        AtomicReference<String> capturedProtocol = new AtomicReference<>();
        AtomicReference<String> capturedUpgrade = new AtomicReference<>();
        AtomicReference<String> capturedConnection = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/audio/speech", exchange -> {
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            capturedContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            capturedTransferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            capturedProtocol.set(exchange.getProtocol());
            capturedUpgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            capturedConnection.set(exchange.getRequestHeaders().getFirst("Connection"));
            capturedBody.set(TEST_MAPPER.readTree(exchange.getRequestBody()));
            byte[] response = new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'};
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration =
                    new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            configuration.setApiKeyHeader("X-API-Key");
            configuration.setApiKeyPrefix("");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("openai2api-tts-relay", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "openai2api-gpt-sovits-v2-tts",
                    "openai2api-tts-relay",
                    "gpt-sovits-v2",
                    ModelCapability.TEXT_TO_SPEECH,
                    Map.of(
                            "speechProtocol", "unified-tts",
                            "defaultLanguage", "zh",
                            "defaultEndUserId", "codex-test",
                            "voiceMap", Map.of(
                                    "gentle_female",
                                    "voice_4cb4da6d4aaa4e48aab7_v4"
                            )
                    )
            );

            TextToSpeechResponse response = provider.synthesizeSpeech(
                    target,
                    new TextToSpeechRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "speech.default",
                            "openai2api-gpt-sovits-v2-tts",
                            "你好，这是测试。",
                            "gentle_female",
                            1.0,
                            "wav",
                            Map.of()
                    )
            );

            assertEquals("test-key", capturedApiKey.get());
            assertEquals(null, capturedAuthorization.get());
            assertNotNull(capturedContentLength.get());
            assertNull(capturedTransferEncoding.get());
            assertEquals("HTTP/1.1", capturedProtocol.get());
            assertNull(capturedUpgrade.get());
            assertNull(capturedConnection.get());
            assertEquals("gpt-sovits-v2", capturedBody.get().path("model").asText());
            assertEquals("你好，这是测试。", capturedBody.get().path("input").asText());
            assertEquals(
                    "voice_4cb4da6d4aaa4e48aab7_v4",
                    capturedBody.get().path("voice_id").asText()
            );
            assertEquals("zh", capturedBody.get().path("language").asText());
            assertEquals("codex-test", capturedBody.get().path("end_user_id").asText());
            assertEquals("wav", capturedBody.get().path("response_format").asText());
            assertEquals("audio/wav", response.audio().mediaType());
            assertTrue(Arrays.equals(
                    new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'},
                    response.audio().content()
            ));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unifiedTtsRejectsUnknownBusinessVoiceWithoutRetrying() {
        ModelProviderProperties.Provider configuration =
                new ModelProviderProperties.Provider();
        configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
        configuration.setBaseUrl("http://127.0.0.1:1");
        configuration.setApiKey("test-key");
        ModelProviderProperties properties = new ModelProviderProperties();
        properties.setProviders(Map.of("openai2api-tts-relay", configuration));
        OpenAiCompatibleTextProvider provider =
                new OpenAiCompatibleTextProvider(properties);
        ModelCallTarget target = new ModelCallTarget(
                "openai2api-gpt-sovits-v2-tts",
                "openai2api-tts-relay",
                "gpt-sovits-v2",
                ModelCapability.TEXT_TO_SPEECH,
                Map.of(
                        "speechProtocol", "unified-tts",
                        "voiceMap", Map.of(
                                "gentle_female",
                                "voice_4cb4da6d4aaa4e48aab7_v4"
                        )
                )
        );

        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> provider.synthesizeSpeech(
                        target,
                        new TextToSpeechRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "speech.default",
                                "openai2api-gpt-sovits-v2-tts",
                                "你好",
                                "unknown_voice",
                                1.0,
                                "wav",
                                Map.of()
                        )
                )
        );

        assertEquals("PROVIDER_VOICE_UNSUPPORTED", exception.code());
        assertFalse(exception.retryable());
    }

    @Test
    void openAiVideoUsesDeploymentPathOverrideForAsyncTaskLifecycle() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicInteger statusRequests = new AtomicInteger();
        byte[] videoBytes = new byte[]{5, 4, 3, 2, 1};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/videos", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/v1/videos".equals(path)) {
                capturedBody.set(new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.ISO_8859_1
                ));
                capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                byte[] response = """
                        {"id":"video-456","object":"video","status":"queued","model":"sora-2"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/v1/videos/video-456".equals(path)) {
                int poll = statusRequests.incrementAndGet();
                String status = poll == 1 ? "in_progress" : "completed";
                byte[] response = (
                        "{\"id\":\"video-456\",\"status\":\"" + status
                                + "\",\"model\":\"sora-2\"}"
                ).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/v1/videos/video-456/content".equals(path)) {
                exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                exchange.sendResponseHeaders(200, videoBytes.length);
                exchange.getResponseBody().write(videoBytes);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration =
                    new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            configuration.setVideoPath("/v1/videos/generations");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("codex2api-relay", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "codex2api-sora-2-video",
                    "codex2api-relay",
                    "sora-2",
                    ModelCapability.VIDEO_GENERATION,
                    Map.of(
                            "videoProtocol", "openai-videos",
                            "videoPath", "/v1/videos",
                            "videoSizeMap", Map.of("16:9", "1280x720"),
                            "videoPollIntervalMs", 1,
                            "videoPollTimeoutMs", 2_000
                    )
            );

            VideoGenerationResponse response = provider.generateVideo(
                    target,
                    new VideoGenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "video.default",
                            "codex2api-sora-2-video",
                            "A quiet orbit above Earth",
                            List.of(UUID.randomUUID()),
                            12,
                            "16:9",
                            null,
                            1,
                            Map.of()
                    ),
                    List.of(new ModelAsset(
                            UUID.randomUUID(),
                            "reference.png",
                            "image/png",
                            new byte[]{1, 2, 3}
                    ))
            );

            assertTrue(capturedContentType.get().startsWith("multipart/form-data"));
            assertTrue(capturedBody.get().contains("name=\"model\""));
            assertTrue(capturedBody.get().contains("sora-2"));
            assertTrue(capturedBody.get().contains("name=\"seconds\""));
            assertTrue(capturedBody.get().contains("12"));
            assertTrue(capturedBody.get().contains("1280x720"));
            assertTrue(capturedBody.get().contains("name=\"input_reference\""));
            assertEquals(2, statusRequests.get());
            assertEquals("video-456", response.providerRequestId());
            assertEquals("video/mp4", response.videos().get(0).mediaType());
            assertTrue(Arrays.equals(videoBytes, response.videos().get(0).content()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiVideoCanSubmitTextOnlyRequestsAsJsonForRelayCompatibility() throws IOException {
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        byte[] videoBytes = new byte[]{4, 2, 4, 2};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/videos", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/v1/videos".equals(path)) {
                capturedBody.set(TEST_MAPPER.readTree(exchange.getRequestBody()));
                capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                byte[] response = """
                        {"id":"video-json-1","status":"queued","model":"sora-2"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && "/v1/videos/video-json-1".equals(path)) {
                byte[] response = """
                        {"id":"video-json-1","status":"completed","model":"sora-2"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && "/v1/videos/video-json-1/content".equals(path)) {
                exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                exchange.sendResponseHeaders(200, videoBytes.length);
                exchange.getResponseBody().write(videoBytes);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration =
                    new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            configuration.setVideoPath("/v1/videos");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("codex2api-sora-relay", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "codex2api-sora-2-video",
                    "codex2api-sora-relay",
                    "sora-2",
                    ModelCapability.VIDEO_GENERATION,
                    Map.of(
                            "videoProtocol", "openai-videos",
                            "videoRequestFormat", "json",
                            "videoPath", "/v1/videos",
                            "videoSizeMap", Map.of("720p|16:9", "1280x720"),
                            "videoPollIntervalMs", 1,
                            "videoPollTimeoutMs", 2_000
                    )
            );

            VideoGenerationResponse response = provider.generateVideo(
                    target,
                    new VideoGenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "video.default",
                            "codex2api-sora-2-video",
                            "A quiet street after summer rain",
                            List.of(),
                            4,
                            "16:9",
                            "720p",
                            1,
                            Map.of()
                    ),
                    List.of()
            );

            assertTrue(capturedContentType.get().startsWith("application/json"));
            assertEquals("sora-2", capturedBody.get().path("model").asText());
            assertEquals("4", capturedBody.get().path("seconds").asText());
            assertEquals("1280x720", capturedBody.get().path("size").asText());
            assertEquals("video-json-1", response.providerRequestId());
            assertTrue(Arrays.equals(videoBytes, response.videos().get(0).content()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiVideoRejectsLastFrameBeforeSubmitting() {
        ModelProviderProperties.Provider configuration =
                new ModelProviderProperties.Provider();
        configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
        configuration.setBaseUrl("http://127.0.0.1:1");
        configuration.setApiKey("test-key");
        configuration.setVideoPath("/v1/videos");
        ModelProviderProperties properties = new ModelProviderProperties();
        properties.setProviders(Map.of("codex2api-sora-relay", configuration));
        OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
        ModelCallTarget target = new ModelCallTarget(
                "codex2api-sora-2-video",
                "codex2api-sora-relay",
                "sora-2",
                ModelCapability.VIDEO_GENERATION,
                Map.of("videoProtocol", "openai-videos")
        );
        UUID lastFrameId = UUID.randomUUID();

        ModelProviderException exception = assertThrows(
                ModelProviderException.class,
                () -> provider.generateVideo(
                        target,
                        new VideoGenerationRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "video.default",
                                "codex2api-sora-2-video",
                                "End on the supplied image",
                                List.of(lastFrameId),
                                8,
                                "16:9",
                                "720p",
                                1,
                                Map.of("lastFrameAssetId", lastFrameId.toString())
                        ),
                        List.of(new ModelAsset(
                                lastFrameId,
                                "last.png",
                                "image/png",
                                new byte[]{1, 2, 3}
                        ))
                )
        );

        assertEquals("MODEL_LAST_FRAME_NOT_SUPPORTED", exception.code());
    }

    @Test
    void xaiVideoUsesAsyncJsonPollingAndReturnedUrlDownload() throws IOException {
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicInteger statusRequests = new AtomicInteger();
        byte[] videoBytes = new byte[]{0, 1, 2, 3, 4};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/videos", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/v1/videos/generations".equals(path)) {
                capturedBody.set(TEST_MAPPER.readTree(exchange.getRequestBody()));
                capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                byte[] response = """
                        {"request_id":"video-123"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/v1/videos/video-123".equals(path)) {
                int poll = statusRequests.incrementAndGet();
                String responseJson = poll == 1
                        ? "{\"request_id\":\"video-123\",\"status\":\"processing\"}"
                        : "{\"request_id\":\"video-123\",\"status\":\"completed\","
                        + "\"model\":\"grok-imagine-video\","
                        + "\"video\":{\"url\":\"http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/media/video-123.mp4\"}}";
                byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });
        server.createContext("/media/video-123.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, videoBytes.length);
            exchange.getResponseBody().write(videoBytes);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration =
                    new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            configuration.setVideoPath("/v1/videos/generations");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("codex2api-relay", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "codex2api-grok-imagine-video",
                    "codex2api-relay",
                    "grok-imagine-video",
                    ModelCapability.VIDEO_GENERATION,
                    Map.of(
                            "videoProtocol", "xai-videos",
                            "videoReferenceField", "reference_images",
                            "maxReferenceImages", 7,
                            "videoPollIntervalMs", 1,
                            "videoPollTimeoutMs", 2_000
                    )
            );

            VideoGenerationResponse response = provider.generateVideo(
                    target,
                    new VideoGenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "video.default",
                            "codex2api-grok-imagine-video",
                            "A paper airplane crosses a sunlit room",
                            List.of(UUID.randomUUID()),
                            8,
                            "16:9",
                            "720p",
                            1,
                            Map.of()
                    ),
                    List.of(new ModelAsset(
                            UUID.randomUUID(),
                            "reference.png",
                            "image/png",
                            new byte[]{9, 8, 7}
                    ))
            );

            assertTrue(capturedContentType.get().startsWith("application/json"));
            assertEquals("grok-imagine-video", capturedBody.get().path("model").asText());
            assertEquals(8, capturedBody.get().path("duration").asInt());
            assertEquals("16:9", capturedBody.get().path("aspect_ratio").asText());
            assertEquals("720p", capturedBody.get().path("resolution").asText());
            JsonNode reference = capturedBody.get().path("reference_images").path(0);
            assertTrue(reference.path("url").asText().startsWith("data:image/png;base64,"));
            assertEquals(2, statusRequests.get());
            assertEquals(1, response.videos().size());
            assertEquals("video-123", response.providerRequestId());
            assertEquals("video/mp4", response.videos().get(0).mediaType());
            assertTrue(Arrays.equals(videoBytes, response.videos().get(0).content()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void xaiVideoSupportsSeparateNewApiSubmitAndStatusPaths() throws IOException {
        AtomicReference<String> submitPath = new AtomicReference<>();
        AtomicReference<String> statusPath = new AtomicReference<>();
        byte[] videoBytes = new byte[]{7, 6, 5, 4};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/video/generations", exchange -> {
            submitPath.set(exchange.getRequestURI().getPath());
            byte[] response = """
                    {"request_id":"newapi-grok-1"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/videos/newapi-grok-1", exchange -> {
            statusPath.set(exchange.getRequestURI().getPath());
            byte[] response = (
                    "{\"request_id\":\"newapi-grok-1\",\"status\":\"completed\","
                            + "\"model\":\"grok-imagine-video-1.5\","
                            + "\"video\":{\"url\":\"http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/media/newapi-grok-1.mp4\"}}"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/media/newapi-grok-1.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, videoBytes.length);
            exchange.getResponseBody().write(videoBytes);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration =
                    new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            configuration.setVideoPath("/v1/videos/generations");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("newapi-grok-relay", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "newapi-grok-imagine-video",
                    "newapi-grok-relay",
                    "grok-imagine-video-1.5",
                    ModelCapability.VIDEO_GENERATION,
                    Map.of(
                            "videoProtocol", "xai-videos",
                            "videoPath", "/v1/video/generations",
                            "videoStatusPath", "/v1/videos/{requestId}",
                            "videoPollIntervalMs", 1,
                            "videoPollTimeoutMs", 2_000
                    )
            );

            VideoGenerationResponse response = provider.generateVideo(
                    target,
                    new VideoGenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "video.default",
                            "newapi-grok-imagine-video",
                            "Rain falls across an empty bridge",
                            List.of(),
                            4,
                            "16:9",
                            "720p",
                            1,
                            Map.of()
                    ),
                    List.of()
            );

            assertEquals("/v1/video/generations", submitPath.get());
            assertEquals("/v1/videos/newapi-grok-1", statusPath.get());
            assertEquals("newapi-grok-1", response.providerRequestId());
            assertTrue(Arrays.equals(videoBytes, response.videos().get(0).content()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void agnesVideoUsesNestedAsyncPollingAndReturnedUrlDownload() throws IOException {
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        AtomicInteger statusRequests = new AtomicInteger();
        byte[] videoBytes = new byte[]{4, 3, 2, 1};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/videos", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/v1/videos".equals(path)) {
                capturedBody.set(TEST_MAPPER.readTree(exchange.getRequestBody()));
                byte[] response = """
                        {"task_id":"task-agnes-1","video_id":"agnes-video-1","status":"pending"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && "/v1/videos/task-agnes-1".equals(path)) {
                int poll = statusRequests.incrementAndGet();
                String responseJson = poll == 1
                        ? "{\"task_id\":\"task-agnes-1\",\"status\":\"processing\"}"
                        : "{\"task_id\":\"task-agnes-1\",\"status\":\"completed\","
                        + "\"model\":\"agnes-video-v2.0\",\"metadata\":{\"url\":\"http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/media/agnes-video-1.mp4\"}}";
                byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });
        server.createContext("/media/agnes-video-1.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, videoBytes.length);
            exchange.getResponseBody().write(videoBytes);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration =
                    new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            configuration.setVideoPath("/v1/videos");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("agnes-ai-official", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "agnes-video-v2-0",
                    "agnes-ai-official",
                    "agnes-video-v2.0",
                    ModelCapability.VIDEO_GENERATION,
                    Map.of(
                            "videoProtocol", "agnes-videos",
                            "videoPollIntervalMs", 1,
                            "videoPollTimeoutMs", 2_000
                    )
            );

            VideoGenerationResponse response = provider.generateVideo(
                    target,
                    new VideoGenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "video.agnes.default",
                            "agnes-video-v2-0",
                            "A cinematic city at sunrise",
                            List.of(),
                            8,
                            "16:9",
                            "720p",
                            1,
                            Map.of()
                    ),
                    List.of()
            );

            assertTrue(provider.supportsResumableVideo(target));
            assertEquals("agnes-video-v2.0", capturedBody.get().path("model").asText());
            assertEquals(8, capturedBody.get().path("duration").asInt());
            assertEquals("16:9", capturedBody.get().path("aspect_ratio").asText());
            assertEquals("720p", capturedBody.get().path("resolution").asText());
            assertEquals(2, statusRequests.get());
            assertEquals("task-agnes-1", response.providerRequestId());
            assertTrue(Arrays.equals(videoBytes, response.videos().get(0).content()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resumableXaiVideoSkipsSubmissionWhenRequestIdAlreadyExists() throws IOException {
        AtomicInteger submitRequests = new AtomicInteger();
        AtomicInteger statusRequests = new AtomicInteger();
        List<String> phases = new ArrayList<>();
        byte[] videoBytes = new byte[]{5, 4, 3, 2, 1};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/videos", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod())) {
                submitRequests.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && "/v1/videos/video-existing".equals(path)) {
                statusRequests.incrementAndGet();
                byte[] response = (
                        "{\"request_id\":\"video-existing\",\"status\":\"done\","
                                + "\"model\":\"grok-imagine-video\","
                                + "\"video\":{\"url\":\"http://127.0.0.1:"
                                + server.getAddress().getPort()
                                + "/media/video-existing.mp4\"}}"
                ).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });
        server.createContext("/media/video-existing.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, videoBytes.length);
            exchange.getResponseBody().write(videoBytes);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration =
                    new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            configuration.setVideoPath("/v1/videos/generations");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("codex2api-relay", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "codex2api-grok-imagine-video",
                    "codex2api-relay",
                    "grok-imagine-video",
                    ModelCapability.VIDEO_GENERATION,
                    Map.of(
                            "videoProtocol", "xai-videos",
                            "videoPollIntervalMs", 1,
                            "videoPollTimeoutMs", 2_000
                    )
            );

            VideoGenerationResponse response = provider.generateVideoResumable(
                    target,
                    new VideoGenerationRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "video.default",
                            "codex2api-grok-imagine-video",
                            "Resume the existing generation",
                            List.of(),
                            8,
                            "16:9",
                            "720p",
                            1,
                            Map.of()
                    ),
                    List.of(),
                    "video-existing",
                    new VideoGenerationLifecycleListener() {
                        @Override
                        public void onSubmitted(
                                String providerRequestId,
                                String providerModel,
                                Map<String, Object> providerState
                        ) {
                            throw new AssertionError("Existing requests must not be submitted again");
                        }

                        @Override
                        public void onPhase(String phase, Map<String, Object> providerState) {
                            phases.add(phase);
                        }
                    }
            );

            assertEquals(0, submitRequests.get());
            assertEquals(1, statusRequests.get());
            assertEquals(List.of("GENERATING", "DOWNLOADING"), phases);
            assertEquals("video-existing", response.providerRequestId());
            assertTrue(Arrays.equals(videoBytes, response.videos().get(0).content()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stopsStreamingWithoutConsumingTheLastDeltaTwice() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = """
                    data: {"id":"chat-1","model":"test-model","choices":[{"delta":{"content":"first"}}]}

                    data: {"id":"chat-1","model":"test-model","choices":[{"delta":{"content":"second"}}]}

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("test-provider", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "test-text",
                    "test-provider",
                    "test-model",
                    ModelCapability.TEXT_GENERATION,
                    Map.of()
            );
            List<String> deltas = new ArrayList<>();

            TextGenerationResponse result = provider.generateTextStream(
                    target,
                    new TextGenerationRequest(
                            UUID.randomUUID(), UUID.randomUUID(), "text.default", "test-text",
                            "system", "user", 100, 0.5, Map.of()
                    ),
                    delta -> {
                        deltas.add(delta);
                        return false;
                    }
            );

            assertEquals(List.of("first"), deltas);
            assertEquals("first", result.text());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsUnavailableAccountResponseToStableError() {
        ModelProviderException exception = OpenAiCompatibleTextProvider.mapHttpFailure(
                503,
                """
                {"error":{"code":"no_available_account","message":"No available account"}}
                """,
                new RuntimeException("provider failure")
        );

        assertEquals("PROVIDER_NO_AVAILABLE_ACCOUNT", exception.code());
        assertEquals("模型服务当前没有可用账号，请稍后重试", exception.getMessage());
        assertTrue(exception.retryable());
    }

    @Test
    void mapsGenericServiceUnavailableWithoutExposingResponseBody() {
        ModelProviderException exception = OpenAiCompatibleTextProvider.mapHttpFailure(
                503,
                "<html>upstream unavailable</html>",
                new RuntimeException("provider failure")
        );

        assertEquals("PROVIDER_HTTP_503", exception.code());
        assertEquals("模型服务暂时不可用，请稍后重试", exception.getMessage());
        assertTrue(exception.retryable());
    }

    @Test
    void includesSafeUpstreamReasonForBadRequest() {
        ModelProviderException exception = OpenAiCompatibleTextProvider.mapHttpFailure(
                400,
                """
                {"error":{"message":"Invalid value for size: 1152x2048"}}
                """,
                new RuntimeException("provider failure")
        );

        assertEquals("PROVIDER_HTTP_400", exception.code());
        assertEquals(
                "模型供应商请求失败（HTTP 400）：Invalid value for size: 1152x2048",
                exception.getMessage()
        );
    }

    @Test
    void resolvesFixedExpansionSizeByRequestedOrientation() {
        ModelCallTarget target = new ModelCallTarget(
                "gpt-image",
                "codex2api",
                "gpt-image-2",
                ModelCapability.IMAGE_GENERATION,
                Map.of("imageSizeMap", Map.of(
                        "1:1", "1024x1024",
                        "16:9", "1536x864",
                        "9:16", "864x1536"
                ))
        );

        assertEquals(
                "1024x1024",
                OpenAiCompatibleTextProvider.resolveExpansionProviderSize(target, "1:1")
        );
        assertEquals(
                "1536x864",
                OpenAiCompatibleTextProvider.resolveExpansionProviderSize(target, "7:5")
        );
        assertEquals(
                "864x1536",
                OpenAiCompatibleTextProvider.resolveExpansionProviderSize(target, "4:5")
        );
    }

    @Test
    void referenceImagePathLoadsMultipartSupportBeforeValidatingAssets() {
        ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
        configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
        configuration.setBaseUrl("http://localhost");
        configuration.setApiKey("test-key");
        ModelProviderProperties properties = new ModelProviderProperties();
        properties.setProviders(Map.of("test-provider", configuration));
        OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);

        ModelCallTarget target = new ModelCallTarget(
                "test-image",
                "test-provider",
                "test-model",
                ModelCapability.IMAGE_GENERATION,
                Map.of()
        );
        ImageGenerationRequest request = new ImageGenerationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "image.generation.default",
                "test-image",
                "生成一张图片",
                List.of(UUID.randomUUID()),
                "1:1",
                1,
                Map.of()
        );
        ModelAsset invalidAsset = new ModelAsset(
                UUID.randomUUID(),
                "not-an-image.txt",
                "text/plain",
                new byte[]{1}
        );

        assertThrows(
                ModelProviderException.class,
                () -> provider.generateImage(target, request, List.of(invalidAsset))
        );
    }

    @Test
    void transparentImageEditWritesOutputOptionsIntoMultipart() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/images/edits", exchange -> {
            capturedBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.ISO_8859_1
            ));
            byte[] response = (
                    "{\"data\":[{\"b64_json\":\""
                            + Base64.getEncoder().encodeToString(new byte[]{1})
                            + "\",\"media_type\":\"image/png\"}]}"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("test-provider", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "test-image",
                    "test-provider",
                    "test-model",
                    ModelCapability.IMAGE_GENERATION,
                    Map.of()
            );
            UUID assetId = UUID.randomUUID();
            ImageGenerationRequest request = new ImageGenerationRequest(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "image.generation.default",
                    "test-image",
                    "移除背景",
                    List.of(assetId),
                    null,
                    1,
                    Map.of("outputFormat", "png", "background", "transparent")
            );

            provider.generateImage(
                    target,
                    request,
                    List.of(new ModelAsset(assetId, "subject.png", "image/png", new byte[]{1}))
            );

            assertTrue(capturedBody.get().contains("name=\"output_format\""));
            assertTrue(capturedBody.get().contains("name=\"background\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void maskedImageEditWritesSourceAndMaskAsDistinctMultipartFields() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/images/edits", exchange -> {
            capturedBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.ISO_8859_1
            ));
            byte[] response = (
                    "{\"data\":[{\"b64_json\":\""
                            + Base64.getEncoder().encodeToString(new byte[]{1})
                            + "\",\"media_type\":\"image/png\"}]}"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("test-provider", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "test-image",
                    "test-provider",
                    "test-model",
                    ModelCapability.IMAGE_GENERATION,
                    Map.of(
                            "supportsImageMask", true,
                            "imagePartName", "image",
                            "maskPartName", "mask"
                    )
            );
            UUID sourceId = UUID.randomUUID();
            UUID maskId = UUID.randomUUID();
            ImageGenerationRequest request = new ImageGenerationRequest(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "image.generation.default",
                    "test-image",
                    "change the selected area",
                    List.of(sourceId),
                    List.of(),
                    maskId,
                    true,
                    "auto",
                    1,
                    Map.of("outputFormat", "png", "inputFidelity", "high")
            );

            provider.generateImage(
                    target,
                    request,
                    List.of(
                            new ModelAsset(sourceId, "source.png", "image/png", new byte[]{1}),
                            new ModelAsset(maskId, "mask.png", "image/png", new byte[]{2})
                    )
            );

            assertTrue(capturedBody.get().contains("name=\"image\""));
            assertTrue(capturedBody.get().contains("filename=\"source.png\""));
            assertTrue(capturedBody.get().contains("name=\"mask\""));
            assertTrue(capturedBody.get().contains("filename=\"mask.png\""));
            assertTrue(capturedBody.get().contains("name=\"input_fidelity\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void maskedImageEditNormalizesSourceAndMaskToConfiguredProviderCanvas() throws IOException {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/images/edits", exchange -> {
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = (
                    "{\"data\":[{\"b64_json\":\""
                            + Base64.getEncoder().encodeToString(new byte[]{1})
                            + "\",\"media_type\":\"image/png\"}]}"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("test-provider", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "test-image",
                    "test-provider",
                    "test-model",
                    ModelCapability.IMAGE_GENERATION,
                    Map.of(
                            "supportsImageMask", true,
                            "imagePartName", "image[]",
                            "maskPartName", "mask",
                            "imageSizeMap", Map.of(
                                    "1:1", "8x8",
                                    "16:9", "16x9",
                                    "9:16", "9x16"
                            )
                    )
            );
            UUID sourceId = UUID.randomUUID();
            UUID maskId = UUID.randomUUID();
            ImageGenerationRequest request = new ImageGenerationRequest(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "image.generation.default",
                    "test-image",
                    "change the selected area",
                    List.of(sourceId),
                    List.of(),
                    maskId,
                    true,
                    "auto",
                    1,
                    Map.of("outputFormat", "png")
            );

            provider.generateImage(
                    target,
                    request,
                    List.of(
                            new ModelAsset(sourceId, "source.png", "image/png", png(6, 10, false)),
                            new ModelAsset(maskId, "mask.png", "image/png", png(6, 10, true))
                    )
            );

            String boundary = multipartBoundary(capturedContentType.get());
            BufferedImage uploadedSource = ImageIO.read(new ByteArrayInputStream(
                    multipartFile(capturedBody.get(), boundary, "image[]")
            ));
            BufferedImage uploadedMask = ImageIO.read(new ByteArrayInputStream(
                    multipartFile(capturedBody.get(), boundary, "mask")
            ));
            assertEquals(9, uploadedSource.getWidth());
            assertEquals(16, uploadedSource.getHeight());
            assertEquals(9, uploadedMask.getWidth());
            assertEquals(16, uploadedMask.getHeight());
            assertEquals(255, uploadedMask.getRGB(0, 0) >>> 24);
            assertTrue((uploadedMask.getRGB(4, 8) >>> 24) < 255);
            assertTrue(new String(capturedBody.get(), StandardCharsets.ISO_8859_1)
                    .contains("\r\n\r\n9x16\r\n"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void imageEditUsesDistinctIdempotencyKeysForInvocationStages() throws IOException {
        List<String> capturedKeys = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/images/edits", exchange -> {
            capturedKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = (
                    "{\"data\":[{\"b64_json\":\""
                            + Base64.getEncoder().encodeToString(new byte[]{1})
                            + "\",\"media_type\":\"image/png\"}]}"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
            configuration.setProtocol(OpenAiCompatibleTextProvider.PROTOCOL);
            configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            configuration.setApiKey("test-key");
            ModelProviderProperties properties = new ModelProviderProperties();
            properties.setProviders(Map.of("test-provider", configuration));
            OpenAiCompatibleTextProvider provider = new OpenAiCompatibleTextProvider(properties);
            ModelCallTarget target = new ModelCallTarget(
                    "test-image",
                    "test-provider",
                    "test-model",
                    ModelCapability.IMAGE_GENERATION,
                    Map.of()
            );
            UUID runId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();
            ModelAsset asset = new ModelAsset(
                    assetId,
                    "subject.png",
                    "image/png",
                    new byte[]{1}
            );

            provider.generateImage(
                    target,
                    imageRequest(runId, assetId, "alpha_white"),
                    List.of(asset)
            );
            provider.generateImage(
                    target,
                    imageRequest(runId, assetId, "alpha_black"),
                    List.of(asset)
            );

            assertEquals(2, capturedKeys.size());
            assertNotEquals(capturedKeys.get(0), capturedKeys.get(1));
        } finally {
            server.stop(0);
        }
    }

    private static ImageGenerationRequest imageRequest(
            UUID runId,
            UUID assetId,
            String invocationKey
    ) {
        return new ImageGenerationRequest(
                UUID.randomUUID(),
                runId,
                "image.generation.default",
                "test-image",
                "edit image",
                List.of(assetId),
                null,
                1,
                Map.of("providerInvocationKey", invocationKey)
        );
    }

    @Test
    void buildsDashScopeExpansionRequestWithOfficialSizeSyntax() throws Exception {
        ModelCallTarget target = new ModelCallTarget(
                "qwen-image",
                "aliyun-maas",
                "qwen-image-2.0",
                ModelCapability.IMAGE_GENERATION,
                Map.of("imageExpansionProtocol", "dashscope-multimodal")
        );
        ImageExpansionRequest request = new ImageExpansionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "image.generation.default",
                "qwen-image",
                "自然扩展画布",
                UUID.randomUUID(),
                "4:5",
                1.25,
                ImagePreservationMode.STRICT,
                Map.of()
        );
        ImageExpansionSupport.PreparedExpansion prepared =
                new ImageExpansionSupport.PreparedExpansion(
                        new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB),
                        1024,
                        1280,
                        502,
                        630,
                        1024,
                        1280,
                        0,
                        0,
                        1024,
                        1280,
                        new byte[]{1, 2},
                        new byte[]{3, 4}
                );

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                OpenAiCompatibleTextProvider.dashScopeExpansionBody(target, request, prepared)
        );

        assertTrue(json.contains("\"model\":\"qwen-image-2.0\""));
        assertTrue(json.contains("\"size\":\"1024*1280\""));
        assertTrue(json.contains("data:image/png;base64,AQI="));
        assertTrue(json.contains("自然扩展画布"));
    }

    private static byte[] png(int width, int height, boolean mask) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(mask ? Color.WHITE : new Color(40, 80, 120));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        if (mask) image.setRGB(width / 2, height / 2, 0x00ffffff);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String multipartBoundary(String contentType) {
        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        return boundary.replace("\"", "").trim();
    }

    private static byte[] multipartFile(byte[] body, String boundary, String partName) {
        byte[] name = ("name=\"" + partName + "\"").getBytes(StandardCharsets.ISO_8859_1);
        int headerStart = indexOf(body, name, 0);
        int contentStart = indexOf(
                body,
                "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),
                headerStart
        ) + 4;
        int contentEnd = indexOf(
                body,
                ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1),
                contentStart
        );
        return Arrays.copyOfRange(body, contentStart, contentEnd);
    }

    private static int indexOf(byte[] source, byte[] target, int start) {
        for (int index = Math.max(0, start); index <= source.length - target.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) return index;
        }
        throw new IllegalArgumentException("Multipart marker not found");
    }
}
