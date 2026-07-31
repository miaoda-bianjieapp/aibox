package com.aibox.provider.assemblyai;

import com.aibox.feature.spi.AudioTranscriptionRequest;
import com.aibox.feature.spi.AudioTranscriptionResponse;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.provider.openai.ModelProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public final class AssemblyAiModelProvider implements ModelProviderClient {

    public static final String PROTOCOL = "assemblyai";

    private static final String UPLOAD_PATH = "/v2/upload";
    private static final String TRANSCRIPT_PATH = "/v2/transcript";
    private static final int DEFAULT_POLL_INTERVAL_MILLIS = 3_000;
    private static final int DEFAULT_POLL_TIMEOUT_SECONDS = 240;

    private final Map<String, ProviderContext> providers;

    public AssemblyAiModelProvider(ModelProviderProperties properties) {
        Map<String, ProviderContext> configured = new LinkedHashMap<>();
        properties.getProviders().forEach((code, config) -> {
            if (!PROTOCOL.equalsIgnoreCase(config.getProtocol())) return;
            if (isBlank(config.getBaseUrl()) || isBlank(config.getApiKey())) {
                throw new IllegalStateException("Provider " + code + " requires base-url and api-key");
            }
            RestClient client = RestClient.builder()
                    .baseUrl(stripTrailingSlash(config.getBaseUrl()))
                    .defaultHeader(HttpHeaders.AUTHORIZATION, config.getApiKey())
                    .defaultHeaders(headers -> config.getHeaders().forEach(headers::set))
                    .build();
            configured.put(code, new ProviderContext(code, client));
        });
        this.providers = Map.copyOf(configured);
    }

    @Override
    public String adapterCode() {
        return PROTOCOL;
    }

    @Override
    public boolean supports(ModelCallTarget target) {
        return target.capability() == ModelCapability.AUDIO_TRANSCRIPTION
                && providers.containsKey(target.providerCode());
    }

    @Override
    public TextGenerationResponse generateText(ModelCallTarget target, TextGenerationRequest request) {
        throw new ModelProviderException(
                "MODEL_CAPABILITY_NOT_SUPPORTED",
                "AssemblyAI adapter does not support " + target.capability(),
                false
        );
    }

    @Override
    public AudioTranscriptionResponse transcribeAudio(
            ModelCallTarget target,
            AudioTranscriptionRequest request,
            ModelAsset asset
    ) {
        ProviderContext provider = requireProvider(target);
        JsonNode upload = execute(() -> provider.client().post()
                .uri(UPLOAD_PATH)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(asset.content())
                .retrieve()
                .body(JsonNode.class));
        String uploadUrl = text(upload, "upload_url");
        if (isBlank(uploadUrl)) {
            throw invalidResponse("AssemblyAI upload response has no upload_url");
        }

        JsonNode transcript = submit(() -> provider.client().post()
                .uri(TRANSCRIPT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transcriptBody(target, request, uploadUrl))
                .retrieve()
                .body(JsonNode.class));
        String transcriptId = text(transcript, "id");
        if (isBlank(transcriptId)) {
            throw invalidResponse("AssemblyAI transcript response has no id");
        }

        JsonNode completed = awaitCompletion(provider, target, transcriptId, transcript);
        String transcriptText = text(completed, "text");
        if (transcriptText == null) {
            throw invalidResponse("AssemblyAI completed transcript has no text");
        }
        String model = text(completed, "speech_model_used");
        return new AudioTranscriptionResponse(
                transcriptText,
                provider.code(),
                isBlank(model) ? target.providerModel() : model,
                transcriptId,
                durationUnits(completed),
                null
        );
    }

    private static Map<String, Object> transcriptBody(
            ModelCallTarget target,
            AudioTranscriptionRequest request,
            String uploadUrl
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audio_url", uploadUrl);
        body.put("speech_models", speechModels(target));
        if (isBlank(request.language()) || "auto".equalsIgnoreCase(request.language())) {
            body.put("language_detection", true);
        } else {
            body.put("language_code", request.language().trim().toLowerCase(Locale.ROOT));
        }

        String promptMode = stringSetting(target, "promptMode", "keyterms");
        if ("contextual".equalsIgnoreCase(promptMode) && !isBlank(request.prompt())) {
            body.put("prompt", request.prompt().trim());
        } else if (!"keyterms".equalsIgnoreCase(promptMode)) {
            throw new ModelProviderException(
                    "MODEL_CONFIGURATION_INVALID",
                    "AssemblyAI deployment has an unsupported prompt mode",
                    false
            );
        }

        List<String> keyterms = keyterms(request, "keyterms".equalsIgnoreCase(promptMode));
        int maximum = intSetting(
                target,
                "maxKeyterms",
                "universal-2".equalsIgnoreCase(target.providerModel()) ? 200 : 1_000,
                1,
                1_000
        );
        if (keyterms.size() > maximum) {
            throw new ModelProviderException(
                    "MODEL_PROMPT_INVALID",
                    "Professional term hints exceed the selected model limit of " + maximum,
                    false
            );
        }
        if (!keyterms.isEmpty()) body.put("keyterms_prompt", keyterms);
        return Map.copyOf(body);
    }

    private static List<String> speechModels(ModelCallTarget target) {
        Object configured = target.settings().get("speechModels");
        if (configured == null) return List.of(target.providerModel());
        List<String> result = new java.util.ArrayList<>();
        if (configured instanceof Iterable<?> values) {
            values.forEach(value -> {
                if (value != null && !value.toString().isBlank()) result.add(value.toString().trim());
            });
        } else {
            for (String value : configured.toString().split(",")) {
                if (!value.isBlank()) result.add(value.trim());
            }
        }
        if (result.isEmpty()) {
            throw new ModelProviderException(
                    "MODEL_CONFIGURATION_INVALID",
                    "AssemblyAI deployment must configure at least one speech model",
                    false
            );
        }
        return List.copyOf(result);
    }

    private static List<String> keyterms(AudioTranscriptionRequest request, boolean includePrompt) {
        Set<String> result = new LinkedHashSet<>();
        addMetadataTerms(result, request.metadata().get("keyterms"));
        addMetadataTerms(result, request.metadata().get("glossary"));
        if (includePrompt && !isBlank(request.prompt())) {
            for (String term : request.prompt().split("[,，;；\\r\\n]+")) {
                addTerm(result, term);
            }
        }
        return List.copyOf(result);
    }

    private static void addMetadataTerms(Set<String> destination, Object value) {
        if (value instanceof Iterable<?> values) {
            values.forEach(item -> addTerm(destination, item == null ? null : item.toString()));
        } else if (value != null) {
            for (String term : value.toString().split("[,，;；\\r\\n]+")) {
                addTerm(destination, term);
            }
        }
    }

    private static void addTerm(Set<String> destination, String value) {
        if (isBlank(value)) return;
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.split(" ").length > 6) {
            throw new ModelProviderException(
                    "MODEL_PROMPT_INVALID",
                    "Each professional term hint may contain at most six words",
                    false
            );
        }
        destination.add(normalized);
    }

    private static JsonNode awaitCompletion(
            ProviderContext provider,
            ModelCallTarget target,
            String transcriptId,
            JsonNode initial
    ) {
        JsonNode current = initial;
        int intervalMillis = intSetting(
                target,
                "pollIntervalMillis",
                DEFAULT_POLL_INTERVAL_MILLIS,
                1,
                60_000
        );
        int timeoutSeconds = intSetting(
                target,
                "pollTimeoutSeconds",
                DEFAULT_POLL_TIMEOUT_SECONDS,
                1,
                3_600
        );
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (true) {
            String status = text(current, "status");
            if ("completed".equalsIgnoreCase(status)) return current;
            if ("error".equalsIgnoreCase(status)) {
                throw new ModelProviderException(
                        "PROVIDER_TRANSCRIPTION_FAILED",
                        "AssemblyAI could not transcribe the submitted audio",
                        false
                );
            }
            if (!"queued".equalsIgnoreCase(status) && !"processing".equalsIgnoreCase(status)) {
                throw invalidResponse("AssemblyAI transcript has an unknown status");
            }
            if (System.nanoTime() >= deadline) {
                throw new ModelProviderException(
                        "PROVIDER_TRANSCRIPTION_TIMEOUT",
                        "AssemblyAI transcription did not finish before the polling timeout",
                        false
                );
            }
            sleep(intervalMillis);
            try {
                current = execute(() -> provider.client().get()
                        .uri(TRANSCRIPT_PATH + "/{id}", transcriptId)
                        .retrieve()
                        .body(JsonNode.class));
            } catch (ModelProviderException exception) {
                if (!exception.retryable()) throw exception;
            }
        }
    }

    private ProviderContext requireProvider(ModelCallTarget target) {
        ProviderContext provider = providers.get(target.providerCode());
        if (provider == null) {
            throw new ModelProviderException(
                    "MODEL_PROVIDER_NOT_CONFIGURED",
                    "AssemblyAI provider is not configured",
                    false
            );
        }
        return provider;
    }

    private static <T> T execute(java.util.function.Supplier<T> call) {
        try {
            T result = call.get();
            if (result == null) throw invalidResponse("AssemblyAI returned an empty response");
            return result;
        } catch (RestClientResponseException exception) {
            throw mapHttpFailure(exception.getStatusCode().value(), exception);
        } catch (ResourceAccessException exception) {
            throw new ModelProviderException(
                    "PROVIDER_CONNECTION_FAILED",
                    "AssemblyAI could not be reached",
                    true,
                    exception
            );
        }
    }

    private static <T> T submit(java.util.function.Supplier<T> call) {
        try {
            T result = call.get();
            if (result == null) throw invalidResponse("AssemblyAI returned an empty submission response");
            return result;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 403 || status == 429) throw mapHttpFailure(status, exception);
            ModelProviderException mapped = mapHttpFailure(status, exception);
            if (!mapped.retryable()) throw mapped;
            throw uncertainSubmission(exception);
        } catch (ResourceAccessException exception) {
            throw uncertainSubmission(exception);
        }
    }

    static ModelProviderException mapHttpFailure(int status, Throwable cause) {
        return switch (status) {
            case 400, 422 -> new ModelProviderException(
                    "PROVIDER_REQUEST_INVALID", "AssemblyAI rejected the transcription request", false, cause
            );
            case 401 -> new ModelProviderException(
                    "PROVIDER_AUTH_FAILED", "AssemblyAI authentication failed", false, cause
            );
            case 403, 408, 429 -> new ModelProviderException(
                    "PROVIDER_RATE_LIMITED", "AssemblyAI is busy; retry later", true, cause
            );
            case 413 -> new ModelProviderException(
                    "PROVIDER_FILE_TOO_LARGE", "The audio file exceeds AssemblyAI limits", false, cause
            );
            case 415 -> new ModelProviderException(
                    "PROVIDER_ASSET_TYPE_UNSUPPORTED", "AssemblyAI does not support this audio format", false, cause
            );
            default -> new ModelProviderException(
                    "PROVIDER_HTTP_" + status,
                    "AssemblyAI request failed with HTTP " + status,
                    status >= 500,
                    cause
            );
        };
    }

    private static ModelProviderException uncertainSubmission(Throwable cause) {
        return new ModelProviderException(
                "PROVIDER_SUBMISSION_UNCERTAIN",
                "AssemblyAI did not confirm whether the transcription request was accepted",
                false,
                cause
        );
    }

    private static Integer durationUnits(JsonNode response) {
        JsonNode duration = response.path("audio_duration");
        if (!duration.isNumber()) return null;
        double seconds = duration.asDouble();
        if (!Double.isFinite(seconds) || seconds < 0) return null;
        return (int) Math.ceil(seconds);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String stringSetting(ModelCallTarget target, String name, String fallback) {
        Object value = target.settings().get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }

    private static int intSetting(
            ModelCallTarget target,
            String name,
            int fallback,
            int minimum,
            int maximum
    ) {
        Object value = target.settings().get(name);
        int parsed;
        try {
            parsed = value instanceof Number number
                    ? number.intValue()
                    : value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            throw new ModelProviderException(
                    "MODEL_CONFIGURATION_INVALID",
                    "AssemblyAI deployment setting " + name + " is invalid",
                    false,
                    exception
            );
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException(
                    "PROVIDER_TRANSCRIPTION_INTERRUPTED",
                    "AssemblyAI transcription polling was interrupted",
                    false,
                    exception
            );
        }
    }

    private static ModelProviderException invalidResponse(String message) {
        return new ModelProviderException("PROVIDER_INVALID_RESPONSE", message, false);
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ProviderContext(String code, RestClient client) {
    }
}
