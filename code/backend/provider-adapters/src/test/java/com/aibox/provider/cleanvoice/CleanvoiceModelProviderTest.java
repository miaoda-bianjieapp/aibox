package com.aibox.provider.cleanvoice;

import com.aibox.feature.spi.AudioEnhancementRequest;
import com.aibox.feature.spi.AudioEnhancementResponse;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.provider.openai.ModelProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CleanvoiceModelProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void uploadsCreatesStableEditPollsAndDownloadsEnhancedAudio() throws IOException {
        byte[] input = "noisy-audio".getBytes(StandardCharsets.UTF_8);
        byte[] output = "clean-audio".getBytes(StandardCharsets.UTF_8);
        JsonNode[] submittedBody = new JsonNode[1];
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v2/upload", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
            assertTrue(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8)
                    .contains("filename=meeting room.wav"));
            respond(exchange, 200, "{\"signedUrl\":\"" + baseUrl + "/signed-upload?token=test\"}");
        });
        server.createContext("/signed-upload", exchange -> {
            assertEquals("PUT", exchange.getRequestMethod());
            assertEquals("audio/wav", exchange.getRequestHeaders().getFirst("Content-Type"));
            assertArrayEquals(input, exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "");
        });
        server.createContext("/v2/edits", exchange -> {
            assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
            submittedBody[0] = MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, 200, "{\"id\":\"edit-1\"}");
        });
        server.createContext("/v2/edits/edit-1", exchange -> respond(exchange, 200, """
                {
                  "status":"SUCCESS",
                  "result":{
                    "download_url":"%s/download/enhanced.mp3"
                  }
                }
                """.formatted(baseUrl)));
        server.createContext("/download/enhanced.mp3", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
            exchange.close();
        });
        server.start();
        try {
            CleanvoiceModelProvider provider = provider(baseUrl);
            AudioEnhancementResponse response = provider.enhanceAudio(
                    target(Map.of(
                            "initialPollDelayMillis", 0,
                            "pollIntervalMillis", 1,
                            "pollTimeoutSeconds", 2
                    )),
                    new AudioEnhancementRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "audio.enhancement.default",
                            "cleanvoice-studio-sound-audio",
                            UUID.randomUUID(),
                            true,
                            "mp3",
                            Map.of()
                    ),
                    new ModelAsset(UUID.randomUUID(), "meeting room.wav", "audio/wav", input)
            );

            assertArrayEquals(output, response.audio().content());
            assertEquals("meeting room-enhanced.mp3", response.audio().fileName());
            assertEquals("audio/mpeg", response.audio().mediaType());
            assertEquals("cleanvoice-official", response.provider());
            assertEquals("studio-sound", response.model());
            assertEquals("edit-1", response.providerRequestId());

            JsonNode inputPayload = submittedBody[0].path("input");
            JsonNode config = inputPayload.path("config");
            assertFalse(submittedBody[0].has("config"));
            assertTrue(config.path("remove_noise").asBoolean());
            assertTrue(config.path("studio_sound").asBoolean());
            assertTrue(config.path("normalize").asBoolean());
            assertTrue(config.path("keep_music").asBoolean());
            assertEquals(-16.0, config.path("target_lufs").asDouble());
            assertEquals("mp3", config.path("export_format").asText());
            assertFalse(config.has("nightly"));
            assertEquals(
                    baseUrl + "/signed-upload",
                    inputPayload.path("files").get(0).asText()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pollsUntilSuccessWithoutSubmittingAnotherBillableEdit() throws IOException {
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"signedUrl\":\"" + baseUrl + "/signed-upload?token=test\"}"));
        server.createContext("/signed-upload", exchange -> respond(exchange, 200, ""));
        server.createContext("/v2/edits", exchange -> {
            submissions.incrementAndGet();
            respond(exchange, 200, "{\"task_id\":\"edit-poll\"}");
        });
        server.createContext("/v2/edits/edit-poll", exchange -> {
            if (polls.getAndIncrement() == 0) {
                respond(exchange, 200, "{\"status\":\"PROGRESS\"}");
                return;
            }
            respond(exchange, 200, """
                    {
                      "status":"SUCCESS",
                      "result":{"download_url":"%s/download/result.wav"}
                    }
                    """.formatted(baseUrl));
        });
        server.createContext("/download/result.wav", exchange -> {
            byte[] output = new byte[]{4, 5};
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
            exchange.close();
        });
        server.start();
        try {
            provider(baseUrl).enhanceAudio(
                    target(Map.of(
                            "initialPollDelayMillis", 0,
                            "pollIntervalMillis", 1,
                            "pollTimeoutSeconds", 2
                    )),
                    request(),
                    audio()
            );

            assertEquals(1, submissions.get());
            assertEquals(2, polls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsFailedEditWithoutLeakingProviderDetails() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"signedUrl\":\"" + baseUrl + "/signed-upload?token=test\"}"));
        server.createContext("/signed-upload", exchange -> respond(exchange, 200, ""));
        server.createContext("/v2/edits", exchange ->
                respond(exchange, 200, "{\"task_id\":\"edit-failed\"}"));
        server.createContext("/v2/edits/edit-failed", exchange -> respond(exchange, 200, """
                {
                  "status":"FAILURE",
                  "error":"private customer audio details"
                }
                """));
        server.start();
        try {
            ModelProviderException exception = assertThrows(
                    ModelProviderException.class,
                    () -> provider(baseUrl).enhanceAudio(
                            target(Map.of("initialPollDelayMillis", 0)),
                            request(),
                            audio()
                    )
            );

            assertEquals("PROVIDER_AUDIO_ENHANCEMENT_FAILED", exception.code());
            assertFalse(exception.retryable());
            assertFalse(exception.getMessage().contains("private customer audio details"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsRateLimitsAsRetryableAndUncertainSubmissionsAsNonRetryable() throws IOException {
        ModelProviderException rateLimit = CleanvoiceModelProvider.mapHttpFailure(429, null);
        assertEquals("PROVIDER_RATE_LIMITED", rateLimit.code());
        assertTrue(rateLimit.retryable());

        AtomicInteger submissions = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"signedUrl\":\"" + baseUrl + "/signed-upload?token=test\"}"));
        server.createContext("/signed-upload", exchange -> respond(exchange, 200, ""));
        server.createContext("/v2/edits", exchange -> {
            submissions.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
        });
        server.start();
        try {
            ModelProviderException uncertain = assertThrows(
                    ModelProviderException.class,
                    () -> provider(baseUrl).enhanceAudio(
                            target(Map.of()),
                            request(),
                            audio()
                    )
            );

            assertEquals("PROVIDER_SUBMISSION_UNCERTAIN", uncertain.code());
            assertFalse(uncertain.retryable());
            assertEquals(1, submissions.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTheCompletedEditDownloadWithoutSubmittingAnotherPaidEdit() throws IOException {
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();
        byte[] output = new byte[]{7, 8, 9};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"signedUrl\":\"" + baseUrl + "/signed-upload?token=test\"}"));
        server.createContext("/signed-upload", exchange -> respond(exchange, 200, ""));
        server.createContext("/v2/edits", exchange -> {
            submissions.incrementAndGet();
            respond(exchange, 200, "{\"task_id\":\"edit-download\"}");
        });
        server.createContext("/v2/edits/edit-download", exchange -> respond(exchange, 200, """
                {
                  "status":"SUCCESS",
                  "result":{"download_url":"%s/download/retry.mp3"}
                }
                """.formatted(baseUrl)));
        server.createContext("/download/retry.mp3", exchange -> {
            if (downloads.getAndIncrement() == 0) {
                respond(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
            exchange.close();
        });
        server.start();
        try {
            AudioEnhancementResponse response = provider(baseUrl).enhanceAudio(
                    target(Map.of(
                            "initialPollDelayMillis", 0,
                            "pollIntervalMillis", 1,
                            "pollTimeoutSeconds", 2,
                            "downloadRetryAttempts", 2,
                            "downloadRetryDelayMillis", 1
                    )),
                    request(),
                    audio()
            );

            assertArrayEquals(output, response.audio().content());
            assertEquals(1, submissions.get());
            assertEquals(2, downloads.get());
        } finally {
            server.stop(0);
        }
    }

    private static CleanvoiceModelProvider provider(String baseUrl) {
        ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
        configuration.setProtocol(CleanvoiceModelProvider.PROTOCOL);
        configuration.setBaseUrl(baseUrl);
        configuration.setApiKey("test-key");
        ModelProviderProperties properties = new ModelProviderProperties();
        properties.setProviders(Map.of("cleanvoice-official", configuration));
        return new CleanvoiceModelProvider(properties);
    }

    private static ModelCallTarget target(Map<String, Object> settings) {
        return new ModelCallTarget(
                "cleanvoice-studio-sound-audio",
                "cleanvoice-official",
                "studio-sound",
                ModelCapability.AUDIO_ENHANCEMENT,
                settings
        );
    }

    private static AudioEnhancementRequest request() {
        return new AudioEnhancementRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "audio.enhancement.default",
                "cleanvoice-studio-sound-audio",
                UUID.randomUUID(),
                false,
                "auto",
                Map.of()
        );
    }

    private static ModelAsset audio() {
        return new ModelAsset(
                UUID.randomUUID(),
                "recording.wav",
                "audio/wav",
                new byte[]{1, 2, 3}
        );
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
