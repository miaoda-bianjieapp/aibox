package com.aibox.provider.assemblyai;

import com.aibox.feature.spi.AudioTranscriptionRequest;
import com.aibox.feature.spi.AudioTranscriptionResponse;
import com.aibox.feature.spi.AudioTimestampMode;
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
    void mapsSpeakerUtterancesAndDetectedLanguageToStandardSegments() throws IOException {
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
                      "id":"transcript-segments",
                      "status":"completed",
                      "text":"欢迎参加会议。我们开始吧。",
                      "language_code":"zh",
                      "speech_model_used":"universal-3-5-pro",
                      "audio_duration":8.4,
                      "utterances":[
                        {
                          "speaker":"A",
                          "start":0,
                          "end":3200,
                          "confidence":0.98,
                          "text":"欢迎参加会议。"
                        },
                        {
                          "speaker":"B",
                          "start":3400,
                          "end":8100,
                          "confidence":0.95,
                          "text":"我们开始吧。"
                        }
                      ]
                    }
                    """);
        });
        server.start();
        try {
            AudioTranscriptionResponse response = provider(server).transcribeAudio(
                    target("universal-3-5-pro", Map.of()),
                    new AudioTranscriptionRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "audio.transcription.default",
                            "assemblyai-universal-3-5-pro-audio",
                            UUID.randomUUID(),
                            "auto",
                            "元作 AI",
                            true,
                            AudioTimestampMode.SEGMENT,
                            Map.of("keyterms", List.of("元作 AI"))
                    ),
                    new ModelAsset(UUID.randomUUID(), "meeting.m4a", "audio/mp4", new byte[]{1, 2, 3})
            );

            assertTrue(submittedBody[0].path("speaker_labels").asBoolean());
            assertFalse(submittedBody[0].has("advanced_speaker_segmentation"));
            assertEquals(
                    1,
                    submittedBody[0].path("speaker_options").path("min_speakers_expected").asInt()
            );
            assertEquals(
                    6,
                    submittedBody[0].path("speaker_options").path("max_speakers_expected").asInt()
            );
            assertEquals("zh", response.detectedLanguage());
            assertEquals(8.4, response.audioDurationSeconds());
            assertEquals(2, response.segments().size());
            assertEquals("A", response.segments().get(0).speaker());
            assertEquals(0L, response.segments().get(0).startMs());
            assertEquals(3200L, response.segments().get(0).endMs());
            assertEquals("欢迎参加会议。", response.segments().get(0).text());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void normalizesSpokenDomainDotsWithoutChangingRegularChineseWords() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"upload_url\":\"https://cdn.assemblyai.test/audio\"}"));
        server.createContext("/v2/transcript", exchange -> respond(exchange, 200, """
                {
                  "id":"transcript-domain",
                  "status":"completed",
                  "text":"Please visit LibriVox \u70b9 org, or www \u70b9 example \u70b9 com. \u8fd9\u6709\u4e00\u70b9\u5e2e\u52a9\u3002",
                  "speech_model_used":"universal-3-5-pro",
                  "utterances":[
                    {
                      "speaker":"A",
                      "start":0,
                      "end":5000,
                      "confidence":0.98,
                      "text":"Please visit LibriVox \u70b9 org, or www \u70b9 example \u70b9 com. \u8fd9\u6709\u4e00\u70b9\u5e2e\u52a9\u3002"
                    }
                  ]
                }
                """));
        server.start();
        try {
            AudioTranscriptionResponse response = provider(server).transcribeAudio(
                    target("universal-3-5-pro", Map.of()),
                    new AudioTranscriptionRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "audio.transcription.default",
                            "assemblyai-universal-3-5-pro-audio",
                            UUID.randomUUID(),
                            "auto",
                            null,
                            true,
                            AudioTimestampMode.SEGMENT,
                            Map.of()
                    ),
                    new ModelAsset(UUID.randomUUID(), "website.m4a", "audio/mp4", new byte[]{1})
            );

            assertEquals(
                    "Please visit LibriVox.org, or www.example.com. \u8fd9\u6709\u4e00\u70b9\u5e2e\u52a9\u3002",
                    response.text()
            );
            assertEquals(
                    "Please visit LibriVox.org, or www.example.com. \u8fd9\u6709\u4e00\u70b9\u5e2e\u52a9\u3002",
                    response.segments().get(0).text()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cleansChineseSpacingAndFillersWhileUsingWordLevelSegments() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"upload_url\":\"https://cdn.assemblyai.test/audio\"}"));
        server.createContext("/v2/transcript", exchange -> respond(exchange, 200, """
                {
                  "id":"transcript-readable",
                  "status":"completed",
                  "text":"\u55ef\uff0c \u6211 \u4eec \u8ba8 \u8bba \u4fc3 \u9500\u3002 \u5443\uff0c \u4e0b \u4e00 \u9879\u3002",
                  "language_code":"zh",
                  "speech_model_used":"universal-3-5-pro",
                  "utterances":[
                    {
                      "speaker":"A",
                      "start":0,
                      "end":70000,
                      "confidence":0.95,
                      "text":"\u55ef\uff0c \u6211 \u4eec \u8ba8 \u8bba \u4fc3 \u9500\u3002 \u5443\uff0c \u4e0b \u4e00 \u9879\u3002"
                    }
                  ],
                  "words":[
                    {"speaker":"A","start":0,"end":400,"confidence":0.90,"text":"\u55ef"},
                    {"speaker":"A","start":1000,"end":1200,"confidence":0.98,"text":"\u6211"},
                    {"speaker":"A","start":1200,"end":1400,"confidence":0.98,"text":"\u4eec"},
                    {"speaker":"A","start":1400,"end":1700,"confidence":0.97,"text":"\u8ba8"},
                    {"speaker":"A","start":1700,"end":2000,"confidence":0.97,"text":"\u8bba"},
                    {"speaker":"A","start":2000,"end":2300,"confidence":0.97,"text":"\u4fc3"},
                    {"speaker":"A","start":2300,"end":2800,"confidence":0.97,"text":"\u9500\u3002"},
                    {"speaker":"A","start":35000,"end":35400,"confidence":0.90,"text":"\u5443"},
                    {"speaker":"A","start":36000,"end":36300,"confidence":0.98,"text":"\u4e0b"},
                    {"speaker":"A","start":36300,"end":36600,"confidence":0.98,"text":"\u4e00"},
                    {"speaker":"A","start":36600,"end":37200,"confidence":0.98,"text":"\u9879\u3002"}
                  ]
                }
                """));
        server.start();
        try {
            AudioTranscriptionResponse response = provider(server).transcribeAudio(
                    target("universal-3-5-pro", Map.of()),
                    new AudioTranscriptionRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "audio.transcription.default",
                            "assemblyai-universal-3-5-pro-audio",
                            UUID.randomUUID(),
                            "auto",
                            null,
                            true,
                            AudioTimestampMode.SEGMENT,
                            Map.of()
                    ),
                    new ModelAsset(UUID.randomUUID(), "meeting.flac", "audio/flac", new byte[]{1})
            );

            assertEquals("\u6211\u4eec\u8ba8\u8bba\u4fc3\u9500\u3002\n\u4e0b\u4e00\u9879\u3002", response.text());
            assertEquals(2, response.segments().size());
            assertEquals("\u6211\u4eec\u8ba8\u8bba\u4fc3\u9500\u3002", response.segments().get(0).text());
            assertEquals(1000L, response.segments().get(0).startMs());
            assertEquals(2800L, response.segments().get(0).endMs());
            assertEquals("\u4e0b\u4e00\u9879\u3002", response.segments().get(1).text());
            assertEquals(36000L, response.segments().get(1).startMs());
            assertEquals(37200L, response.segments().get(1).endMs());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void removesLeadingAhButPreservesSemanticSentenceFinalAh() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"upload_url\":\"https://cdn.assemblyai.test/audio\"}"));
        server.createContext("/v2/transcript", exchange -> respond(exchange, 200, """
                {
                  "id":"transcript-semantic-ah",
                  "status":"completed",
                  "text":"\u554a\uff0c \u6211 \u4eec \u5f00 \u59cb\u3002 \u771f \u597d \u554a\uff01",
                  "language_code":"zh",
                  "speech_model_used":"universal-3-5-pro",
                  "words":[
                    {"speaker":"A","start":0,"end":300,"confidence":0.90,"text":"\u554a"},
                    {"speaker":"A","start":500,"end":700,"confidence":0.98,"text":"\u6211"},
                    {"speaker":"A","start":700,"end":900,"confidence":0.98,"text":"\u4eec"},
                    {"speaker":"A","start":900,"end":1100,"confidence":0.98,"text":"\u5f00"},
                    {"speaker":"A","start":1100,"end":1400,"confidence":0.98,"text":"\u59cb\u3002"},
                    {"speaker":"A","start":2000,"end":2200,"confidence":0.98,"text":"\u771f"},
                    {"speaker":"A","start":2200,"end":2400,"confidence":0.98,"text":"\u597d"},
                    {"speaker":"A","start":2400,"end":2700,"confidence":0.98,"text":"\u554a\uff01"}
                  ]
                }
                """));
        server.start();
        try {
            AudioTranscriptionResponse response = provider(server).transcribeAudio(
                    target("universal-3-5-pro", Map.of()),
                    new AudioTranscriptionRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "audio.transcription.default",
                            "assemblyai-universal-3-5-pro-audio",
                            UUID.randomUUID(),
                            "auto",
                            null,
                            false,
                            AudioTimestampMode.SEGMENT,
                            Map.of()
                    ),
                    new ModelAsset(UUID.randomUUID(), "speech.flac", "audio/flac", new byte[]{1})
            );

            assertEquals("\u6211\u4eec\u5f00\u59cb\u3002\n\u771f\u597d\u554a\uff01", response.text());
            assertEquals(2, response.segments().size());
            assertEquals("\u771f\u597d\u554a\uff01", response.segments().get(1).text());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesSemanticSentenceFinalAhWhenOnlyUtterancesAreAvailable() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/upload", exchange ->
                respond(exchange, 200, "{\"upload_url\":\"https://cdn.assemblyai.test/audio\"}"));
        server.createContext("/v2/transcript", exchange -> respond(exchange, 200, """
                {
                  "id":"transcript-utterance-ah",
                  "status":"completed",
                  "text":"\u554a\uff0c \u6211 \u4eec \u5f00 \u59cb\u3002 \u771f \u597d \u554a\uff01",
                  "language_code":"zh",
                  "speech_model_used":"universal-3-5-pro",
                  "utterances":[
                    {
                      "speaker":"A",
                      "start":0,
                      "end":5000,
                      "confidence":0.96,
                      "text":"\u554a\uff0c \u6211 \u4eec \u5f00 \u59cb\u3002 \u771f \u597d \u554a\uff01"
                    }
                  ]
                }
                """));
        server.start();
        try {
            AudioTranscriptionResponse response = provider(server).transcribeAudio(
                    target("universal-3-5-pro", Map.of()),
                    new AudioTranscriptionRequest(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "audio.transcription.default",
                            "assemblyai-universal-3-5-pro-audio",
                            UUID.randomUUID(),
                            "auto",
                            null,
                            true,
                            AudioTimestampMode.SEGMENT,
                            Map.of()
                    ),
                    new ModelAsset(UUID.randomUUID(), "speech.flac", "audio/flac", new byte[]{1})
            );

            assertEquals("\u6211\u4eec\u5f00\u59cb\u3002\u771f\u597d\u554a\uff01", response.text());
            assertEquals(1, response.segments().size());
            assertEquals(
                    "\u6211\u4eec\u5f00\u59cb\u3002\u771f\u597d\u554a\uff01",
                    response.segments().get(0).text()
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
