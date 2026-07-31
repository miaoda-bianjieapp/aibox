package com.aibox.provider.assemblyai;

import com.aibox.feature.spi.AudioTranscriptionRequest;
import com.aibox.feature.spi.AudioTranscriptionResponse;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblyAiModelProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void uploadsSubmitsAndPollsForACompletedTranscript() throws IOException {
        byte[] audio = "test-audio".getBytes(StandardCharsets.UTF_8);
        List<String> authorizationHeaders = new ArrayList<>();
        AtomicInteger pollCount = new AtomicInteger();
        JsonNode[] submittedBody = new JsonNode[1];
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/upload", exchange -> {
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("application/octet-stream", exchange.getRequestHeaders().getFirst("Content-Type"));
            assertArrayEquals(audio, exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "{\"upload_url\":\"https://cdn.assemblyai.test/audio\"}");
        });
        server.createContext("/v2/transcript", exchange -> {
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if ("POST".equals(exchange.getRequestMethod())) {
                submittedBody[0] = MAPPER.readTree(exchange.getRequestBody());
                respond(exchange, 200, "{\"id\":\"transcript-1\",\"status\":\"queued\"}");
                return;
            }
            if (pollCount.getAndIncrement() == 0) {
                respond(exchange, 200, "{\"id\":\"transcript-1\",\"status\":\"processing\"}");
                return;
            }
            respond(exchange, 200, """
                    {
                      "id":"transcript-1",
                      "status":"completed",
                      "text":"Transcribed text",
                      "speech_model_used":"universal-3-5-pro",
                      "audio_duration":13.2
                    }
                    """);
        });
        server.start();
        try {
            AssemblyAiModelProvider provider = provider(server);
            ModelCallTarget target = target(
                    "universal-3-5-pro",
                    Map.of(
                            "pollIntervalMillis", 1,
                            "pollTimeoutSeconds", 2,
                            "promptMode", "contextual",
                            "maxKeyterms", 1000,
                            "speechModels", List.of("universal-3-5-pro", "universal-2")
                    )
            );

            AudioTranscriptionResponse response = provider.transcribeAudio(
                    target,
                    request("auto", "Cardiology consultation", Map.of(
                            "keyterms", List.of("Yuanzuo AI", "ECG")
                    )),
                    new ModelAsset(UUID.randomUUID(), "recording.wav", "audio/wav", audio)
            );

            assertEquals("Transcribed text", response.text());
            assertEquals("assemblyai-official", response.provider());
            assertEquals("universal-3-5-pro", response.model());
            assertEquals("transcript-1", response.providerRequestId());
            assertEquals(14, response.inputUnits());
            assertTrue(authorizationHeaders.stream().allMatch("test-key"::equals));
            assertEquals(
                    List.of("universal-3-5-pro", "universal-2"),
                    MAPPER.convertValue(submittedBody[0].path("speech_models"), List.class)
            );
            assertTrue(submittedBody[0].path("language_detection").asBoolean());
            assertFalse(submittedBody[0].has("language_code"));
            assertEquals("Cardiology consultation", submittedBody[0].path("prompt").asText());
            assertEquals(
                    List.of("Yuanzuo AI", "ECG"),
                    MAPPER.convertValue(submittedBody[0].path("keyterms_prompt"), List.class)
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsExplicitLanguageAndMapsUniversalTwoPromptToKeyterms() throws IOException {
        JsonNode[] submittedBody = new JsonNode[1];
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"upload_url\":\"https://cdn.assemblyai.test/audio\"}"));
        server.createContext("/v2/transcript", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                submittedBody[0] = MAPPER.readTree(exchange.getRequestBody());
            }
            respond(exchange, 200, """
                    {
                      "id":"transcript-2",
                      "status":"completed",
                      "text":"完整文字",
                      "speech_model_used":"universal-2"
                    }
                    """);
        });
        server.start();
        try {
            AssemblyAiModelProvider provider = provider(server);
            provider.transcribeAudio(
                    target("universal-2", Map.of("promptMode", "keyterms", "maxKeyterms", 200)),
                    request("zh", "元作 AI，AssemblyAI\n专有名词", Map.of()),
                    new ModelAsset(UUID.randomUUID(), "recording.mp3", "audio/mpeg", new byte[]{1, 2, 3})
            );

            assertEquals("zh", submittedBody[0].path("language_code").asText());
            assertFalse(submittedBody[0].has("language_detection"));
            assertFalse(submittedBody[0].has("prompt"));
            assertEquals(
                    List.of("元作 AI", "AssemblyAI", "专有名词"),
                    MAPPER.convertValue(submittedBody[0].path("keyterms_prompt"), List.class)
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsProviderTranscriptFailureWithoutLeakingItsMessage() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"upload_url\":\"https://cdn.assemblyai.test/audio\"}"));
        server.createContext("/v2/transcript", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"id\":\"transcript-3\",\"status\":\"queued\"}");
                return;
            }
            respond(exchange, 200, """
                    {
                      "id":"transcript-3",
                      "status":"error",
                      "error":"secret user audio details"
                    }
                    """);
        });
        server.start();
        try {
            AssemblyAiModelProvider provider = provider(server);
            ModelProviderException exception = assertThrows(
                    ModelProviderException.class,
                    () -> provider.transcribeAudio(
                            target("universal-3-5-pro", Map.of("pollIntervalMillis", 1)),
                            request("auto", null, Map.of()),
                            new ModelAsset(UUID.randomUUID(), "recording.wav", "audio/wav", new byte[]{1})
                    )
            );

            assertEquals("PROVIDER_TRANSCRIPTION_FAILED", exception.code());
            assertFalse(exception.retryable());
            assertFalse(exception.getMessage().contains("secret user audio details"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsRateLimitsAsRetryable() {
        ModelProviderException exception = AssemblyAiModelProvider.mapHttpFailure(429, null);
        ModelProviderException httpLimit = AssemblyAiModelProvider.mapHttpFailure(403, null);

        assertEquals("PROVIDER_RATE_LIMITED", exception.code());
        assertTrue(exception.retryable());
        assertEquals("PROVIDER_RATE_LIMITED", httpLimit.code());
        assertTrue(httpLimit.retryable());
    }

    @Test
    void retriesPollingWithoutSubmittingAnotherBillableTranscript() throws IOException {
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"upload_url\":\"https://cdn.assemblyai.test/audio\"}"));
        server.createContext("/v2/transcript", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                submissions.incrementAndGet();
                respond(exchange, 200, "{\"id\":\"transcript-4\",\"status\":\"queued\"}");
                return;
            }
            if (polls.getAndIncrement() == 0) {
                respond(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
                return;
            }
            respond(exchange, 200, """
                    {
                      "id":"transcript-4",
                      "status":"completed",
                      "text":"Recovered",
                      "speech_model_used":"universal-3-5-pro"
                    }
                    """);
        });
        server.start();
        try {
            AudioTranscriptionResponse response = provider(server).transcribeAudio(
                    target(
                            "universal-3-5-pro",
                            Map.of("pollIntervalMillis", 1, "pollTimeoutSeconds", 2)
                    ),
                    request("auto", null, Map.of()),
                    new ModelAsset(UUID.randomUUID(), "recording.wav", "audio/wav", new byte[]{1})
            );

            assertEquals("Recovered", response.text());
            assertEquals(1, submissions.get());
            assertEquals(2, polls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotAutomaticallyRetryAnUncertainSubmission() throws IOException {
        AtomicInteger submissions = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"upload_url\":\"https://cdn.assemblyai.test/audio\"}"));
        server.createContext("/v2/transcript", exchange -> {
            submissions.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"temporarily unavailable\"}");
        });
        server.start();
        try {
            ModelProviderException exception = assertThrows(
                    ModelProviderException.class,
                    () -> provider(server).transcribeAudio(
                            target("universal-3-5-pro", Map.of()),
                            request("auto", null, Map.of()),
                            new ModelAsset(UUID.randomUUID(), "recording.wav", "audio/wav", new byte[]{1})
                    )
            );

            assertEquals("PROVIDER_SUBMISSION_UNCERTAIN", exception.code());
            assertFalse(exception.retryable());
            assertEquals(1, submissions.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsKnownSubmissionRateLimitsRetryable() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"upload_url\":\"https://cdn.assemblyai.test/audio\"}"));
        server.createContext("/v2/transcript", exchange ->
                respond(exchange, 403, "{\"error\":\"HTTP request limit exceeded\"}"));
        server.start();
        try {
            ModelProviderException exception = assertThrows(
                    ModelProviderException.class,
                    () -> provider(server).transcribeAudio(
                            target("universal-3-5-pro", Map.of()),
                            request("auto", null, Map.of()),
                            new ModelAsset(UUID.randomUUID(), "recording.wav", "audio/wav", new byte[]{1})
                    )
            );

            assertEquals("PROVIDER_RATE_LIMITED", exception.code());
            assertTrue(exception.retryable());
        } finally {
            server.stop(0);
        }
    }

    private static AssemblyAiModelProvider provider(HttpServer server) {
        ModelProviderProperties.Provider configuration = new ModelProviderProperties.Provider();
        configuration.setProtocol(AssemblyAiModelProvider.PROTOCOL);
        configuration.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        configuration.setApiKey("test-key");
        ModelProviderProperties properties = new ModelProviderProperties();
        properties.setProviders(Map.of("assemblyai-official", configuration));
        return new AssemblyAiModelProvider(properties);
    }

    private static ModelCallTarget target(String model, Map<String, Object> settings) {
        return new ModelCallTarget(
                "assemblyai-" + model + "-audio",
                "assemblyai-official",
                model,
                ModelCapability.AUDIO_TRANSCRIPTION,
                settings
        );
    }

    private static AudioTranscriptionRequest request(
            String language,
            String prompt,
            Map<String, Object> metadata
    ) {
        return new AudioTranscriptionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "audio.transcription.default",
                "assemblyai-universal-audio",
                UUID.randomUUID(),
                language,
                prompt,
                metadata
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
