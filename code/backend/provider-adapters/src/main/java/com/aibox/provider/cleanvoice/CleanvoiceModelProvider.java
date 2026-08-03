package com.aibox.provider.cleanvoice;

import com.aibox.feature.spi.AudioEnhancementRequest;
import com.aibox.feature.spi.AudioEnhancementResponse;
import com.aibox.feature.spi.GeneratedAudio;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.provider.openai.ModelProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public final class CleanvoiceModelProvider implements ModelProviderClient {

    public static final String PROTOCOL = "cleanvoice";

    private static final String UPLOAD_PATH = "/v2/upload";
    private static final String EDITS_PATH = "/v2/edits";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final int DEFAULT_INITIAL_POLL_DELAY_MILLIS = 30_000;
    private static final int DEFAULT_POLL_INTERVAL_MILLIS = 10_000;
    private static final int DEFAULT_POLL_TIMEOUT_SECONDS = 1_200;
    private static final int DEFAULT_MAX_INPUT_BYTES = 200 * 1024 * 1024;
    private static final int DEFAULT_DOWNLOAD_RETRY_ATTEMPTS = 3;
    private static final int DEFAULT_DOWNLOAD_RETRY_DELAY_MILLIS = 1_000;
    private static final Set<String> WAITING_STATUSES =
            Set.of(
                    "PENDING",
                    "STARTED",
                    "PROGRESS",
                    "QUEUED",
                    "PROCESSING",
                    "PREPROCESSING",
                    "CLASSIFICATION",
                    "EDITING",
                    "POSTPROCESSING",
                    "EXPORT",
                    "RETRY"
            );
    private static final Set<String> OUTPUT_FORMATS = Set.of("auto", "mp3", "wav", "flac", "m4a");

    private final Map<String, ProviderContext> providers;
    private final RestClient transferClient;

    public CleanvoiceModelProvider(ModelProviderProperties properties) {
        Map<String, ProviderContext> configured = new LinkedHashMap<>();
        properties.getProviders().forEach((code, config) -> {
            if (!PROTOCOL.equalsIgnoreCase(config.getProtocol())) return;
            if (isBlank(config.getBaseUrl()) || isBlank(config.getApiKey())) {
                throw new IllegalStateException("Provider " + code + " requires base-url and api-key");
            }
            RestClient client = RestClient.builder()
                    .baseUrl(stripTrailingSlash(config.getBaseUrl()))
                    .defaultHeader(API_KEY_HEADER, config.getApiKey())
                    .defaultHeaders(headers -> config.getHeaders().forEach(headers::set))
                    .build();
            configured.put(code, new ProviderContext(code, client));
        });
        this.providers = Map.copyOf(configured);
        this.transferClient = RestClient.create();
    }

    @Override
    public String adapterCode() {
        return PROTOCOL;
    }

    @Override
    public boolean supports(ModelCallTarget target) {
        return target.capability() == ModelCapability.AUDIO_ENHANCEMENT
                && providers.containsKey(target.providerCode());
    }

    @Override
    public TextGenerationResponse generateText(ModelCallTarget target, TextGenerationRequest request) {
        throw new ModelProviderException(
                "MODEL_CAPABILITY_NOT_SUPPORTED",
                "Cleanvoice adapter does not support " + target.capability(),
                false
        );
    }

    @Override
    public AudioEnhancementResponse enhanceAudio(
            ModelCallTarget target,
            AudioEnhancementRequest request,
            ModelAsset asset
    ) {
        ProviderContext provider = requireProvider(target);
        validateInput(target, asset);

        JsonNode upload = execute(() -> provider.client().post()
                .uri(builder -> builder.path(UPLOAD_PATH)
                        .queryParam("filename", safeFileName(asset.fileName()))
                        .build())
                .retrieve()
                .body(JsonNode.class));
        String signedUrl = text(upload, "signedUrl");
        if (isBlank(signedUrl)) {
            throw invalidResponse("Cleanvoice upload response has no signedUrl");
        }

        uploadAsset(signedUrl, asset);

        JsonNode submission = submit(() -> provider.client().post()
                .uri(EDITS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(editBody(target, request, stripQuery(signedUrl)))
                .retrieve()
                .body(JsonNode.class));
        String taskId = text(submission, "task_id");
        if (isBlank(taskId)) {
            taskId = text(submission, "id");
        }
        if (isBlank(taskId)) {
            throw invalidResponse("Cleanvoice edit response has no task_id");
        }

        JsonNode completed = awaitCompletion(provider, target, taskId, submission);
        String downloadUrl = text(completed.path("result"), "download_url");
        if (isBlank(downloadUrl)) {
            throw invalidResponse("Cleanvoice completed edit has no download_url");
        }
        DownloadedAudio downloaded = download(target, downloadUrl, request.format(), asset);
        return new AudioEnhancementResponse(
                new GeneratedAudio(downloaded.fileName(), downloaded.mediaType(), downloaded.content()),
                provider.code(),
                target.providerModel(),
                taskId,
                null,
                null
        );
    }

    private void uploadAsset(String signedUrl, ModelAsset asset) {
        execute(() -> {
            transferClient.put()
                    .uri(signedUrl)
                    .contentType(mediaType(asset.mediaType(), MediaType.APPLICATION_OCTET_STREAM))
                    .body(asset.content())
                    .retrieve()
                    .toBodilessEntity();
            return Boolean.TRUE;
        });
    }

    private DownloadedAudio download(
            ModelCallTarget target,
            String url,
            String requestedFormat,
            ModelAsset source
    ) {
        int attempts = intSetting(
                target,
                "downloadRetryAttempts",
                DEFAULT_DOWNLOAD_RETRY_ATTEMPTS,
                1,
                10
        );
        int delayMillis = intSetting(
                target,
                "downloadRetryDelayMillis",
                DEFAULT_DOWNLOAD_RETRY_DELAY_MILLIS,
                0,
                60_000
        );
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return downloadOnce(url, requestedFormat, source);
            } catch (ModelProviderException exception) {
                if (!exception.retryable()) throw exception;
                if (attempt == attempts) {
                    throw new ModelProviderException(
                            "PROVIDER_AUDIO_DOWNLOAD_FAILED",
                            "Cleanvoice completed the edit but the enhanced audio could not be downloaded",
                            false,
                            exception
                    );
                }
                sleep(delayMillis);
            }
        }
        throw new IllegalStateException("Download retry loop did not return");
    }

    private DownloadedAudio downloadOnce(String url, String requestedFormat, ModelAsset source) {
        ResponseEntity<byte[]> response = execute(() -> transferClient.get()
                .uri(url)
                .retrieve()
                .toEntity(byte[].class));
        byte[] content = response.getBody();
        if (content == null || content.length == 0) {
            throw invalidResponse("Cleanvoice returned an empty enhanced audio file");
        }
        String mediaType = response.getHeaders().getContentType() == null
                ? fallbackMediaType(requestedFormat, source.mediaType(), url)
                : response.getHeaders().getContentType().toString();
        String extension = extension(mediaType, requestedFormat, url);
        return new DownloadedAudio(
                baseName(source.fileName()) + "-enhanced" + extension,
                mediaType,
                content
        );
    }

    private static Map<String, Object> editBody(
            ModelCallTarget target,
            AudioEnhancementRequest request,
            String uploadedFileUrl
    ) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("remove_noise", booleanSetting(target, "removeNoise", true));
        config.put("studio_sound", booleanSetting(target, "studioSound", true));
        config.put("normalize", booleanSetting(target, "normalize", true));
        config.put("keep_music", request.keepBackgroundMusic());
        config.put("target_lufs", doubleSetting(target, "targetLufs", -16.0, -24.0, -8.0));
        config.put("export_format", outputFormat(target, request.format()));
        return Map.of("input", Map.of(
                "files", List.of(uploadedFileUrl),
                "config", Map.copyOf(config)
        ));
    }

    private static JsonNode awaitCompletion(
            ProviderContext provider,
            ModelCallTarget target,
            String taskId,
            JsonNode initial
    ) {
        int initialDelayMillis = intSetting(
                target,
                "initialPollDelayMillis",
                DEFAULT_INITIAL_POLL_DELAY_MILLIS,
                0,
                600_000
        );
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
                7_200
        );
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        JsonNode current = initial;
        if (isBlank(text(current, "status"))) {
            sleep(initialDelayMillis);
        }
        while (true) {
            if (isBlank(text(current, "status"))) {
                current = poll(provider, taskId);
            }
            String status = text(current, "status");
            if ("SUCCESS".equalsIgnoreCase(status)) return current;
            if ("FAILURE".equalsIgnoreCase(status)) {
                throw new ModelProviderException(
                        "PROVIDER_AUDIO_ENHANCEMENT_FAILED",
                        "Cleanvoice could not enhance the submitted audio",
                        false
                );
            }
            String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
            if (!WAITING_STATUSES.contains(normalized)) {
                throw invalidResponse("Cleanvoice edit has an unknown status");
            }
            if (System.nanoTime() >= deadline) {
                throw new ModelProviderException(
                        "PROVIDER_AUDIO_ENHANCEMENT_TIMEOUT",
                        "Cleanvoice audio enhancement did not finish before the polling timeout",
                        false
                );
            }
            sleep(intervalMillis);
            current = poll(provider, taskId);
        }
    }

    private static JsonNode poll(ProviderContext provider, String taskId) {
        try {
            return execute(() -> provider.client().get()
                    .uri(EDITS_PATH + "/{id}", taskId)
                    .retrieve()
                    .body(JsonNode.class));
        } catch (ModelProviderException exception) {
            if (!exception.retryable()) throw exception;
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("status", "PROGRESS");
        }
    }

    private static void validateInput(ModelCallTarget target, ModelAsset asset) {
        int maximum = intSetting(
                target,
                "maxInputBytes",
                DEFAULT_MAX_INPUT_BYTES,
                1,
                Integer.MAX_VALUE
        );
        if (asset.content().length > maximum) {
            throw new ModelProviderException(
                    "PROVIDER_FILE_TOO_LARGE",
                    "The audio file exceeds the configured Cleanvoice limit",
                    false
            );
        }
        String mediaType = asset.mediaType() == null ? "" : asset.mediaType().toLowerCase(Locale.ROOT);
        String fileName = asset.fileName() == null ? "" : asset.fileName().toLowerCase(Locale.ROOT);
        if (!mediaType.startsWith("audio/")
                && !fileName.matches(".*\\.(mp3|wav|m4a|aac|flac|ogg|opus|wma|aiff|aif)$")) {
            throw new ModelProviderException(
                    "PROVIDER_ASSET_TYPE_UNSUPPORTED",
                    "Cleanvoice requires a supported audio file",
                    false
            );
        }
    }

    private ProviderContext requireProvider(ModelCallTarget target) {
        ProviderContext provider = providers.get(target.providerCode());
        if (provider == null) {
            throw new ModelProviderException(
                    "MODEL_PROVIDER_NOT_CONFIGURED",
                    "Cleanvoice provider is not configured",
                    false
            );
        }
        return provider;
    }

    private static <T> T execute(java.util.function.Supplier<T> call) {
        try {
            T result = call.get();
            if (result == null) throw invalidResponse("Cleanvoice returned an empty response");
            return result;
        } catch (RestClientResponseException exception) {
            throw mapHttpFailure(exception.getStatusCode().value(), exception);
        } catch (ResourceAccessException exception) {
            throw new ModelProviderException(
                    "PROVIDER_CONNECTION_FAILED",
                    "Cleanvoice could not be reached",
                    true,
                    exception
            );
        }
    }

    private static <T> T submit(java.util.function.Supplier<T> call) {
        try {
            T result = call.get();
            if (result == null) throw invalidResponse("Cleanvoice returned an empty submission response");
            return result;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            ModelProviderException mapped = mapHttpFailure(status, exception);
            if (!mapped.retryable()) throw mapped;
            throw uncertainSubmission(exception);
        } catch (ResourceAccessException exception) {
            throw uncertainSubmission(exception);
        }
    }

    static ModelProviderException mapHttpFailure(int status, Throwable cause) {
        return switch (status) {
            case 400, 409, 422 -> new ModelProviderException(
                    "PROVIDER_REQUEST_INVALID", "Cleanvoice rejected the audio enhancement request", false, cause
            );
            case 401 -> new ModelProviderException(
                    "PROVIDER_AUTH_FAILED", "Cleanvoice authentication failed", false, cause
            );
            case 402, 403 -> new ModelProviderException(
                    "PROVIDER_QUOTA_EXCEEDED", "Cleanvoice account quota is unavailable", false, cause
            );
            case 408, 429 -> new ModelProviderException(
                    "PROVIDER_RATE_LIMITED", "Cleanvoice is busy; retry later", true, cause
            );
            case 413 -> new ModelProviderException(
                    "PROVIDER_FILE_TOO_LARGE", "The audio file exceeds Cleanvoice limits", false, cause
            );
            case 415 -> new ModelProviderException(
                    "PROVIDER_ASSET_TYPE_UNSUPPORTED", "Cleanvoice does not support this audio format", false, cause
            );
            default -> new ModelProviderException(
                    "PROVIDER_HTTP_" + status,
                    "Cleanvoice request failed with HTTP " + status,
                    status >= 500,
                    cause
            );
        };
    }

    private static ModelProviderException uncertainSubmission(Throwable cause) {
        return new ModelProviderException(
                "PROVIDER_SUBMISSION_UNCERTAIN",
                "Cleanvoice did not confirm whether the audio edit was accepted",
                false,
                cause
        );
    }

    private static String outputFormat(ModelCallTarget target, String requested) {
        String value = isBlank(requested)
                ? stringSetting(target, "exportFormat", "auto")
                : requested.trim().toLowerCase(Locale.ROOT);
        if (!OUTPUT_FORMATS.contains(value)) {
            throw new ModelProviderException(
                    "MODEL_OUTPUT_FORMAT_INVALID",
                    "Cleanvoice output format is unsupported",
                    false
            );
        }
        return value;
    }

    private static String fallbackMediaType(String requested, String source, String url) {
        String format = requested == null ? "" : requested.toLowerCase(Locale.ROOT);
        if (isBlank(format) || "auto".equals(format)) {
            format = extensionFromUrl(url);
        }
        return switch (format) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "m4a" -> "audio/mp4";
            default -> isBlank(source) ? "application/octet-stream" : source;
        };
    }

    private static String extension(String mediaType, String requested, String url) {
        String normalized = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if (normalized.contains("mpeg")) return ".mp3";
        if (normalized.contains("wav")) return ".wav";
        if (normalized.contains("flac")) return ".flac";
        if (normalized.contains("mp4") || normalized.contains("m4a")) return ".m4a";
        String requestedExtension = requested == null ? "" : requested.toLowerCase(Locale.ROOT);
        if (OUTPUT_FORMATS.contains(requestedExtension) && !"auto".equals(requestedExtension)) {
            return "." + requestedExtension;
        }
        String fromUrl = extensionFromUrl(url);
        return fromUrl.isBlank() ? ".audio" : "." + fromUrl;
    }

    private static String extensionFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) return "";
            return path.substring(dot + 1).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static MediaType mediaType(String value, MediaType fallback) {
        if (isBlank(value)) return fallback;
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static boolean booleanSetting(ModelCallTarget target, String name, boolean fallback) {
        Object value = target.settings().get(name);
        if (value == null) return fallback;
        if (value instanceof Boolean flag) return flag;
        return Boolean.parseBoolean(value.toString());
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
                    "Cleanvoice deployment setting " + name + " is invalid",
                    false,
                    exception
            );
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private static double doubleSetting(
            ModelCallTarget target,
            String name,
            double fallback,
            double minimum,
            double maximum
    ) {
        Object value = target.settings().get(name);
        double parsed;
        try {
            parsed = value instanceof Number number
                    ? number.doubleValue()
                    : value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            throw new ModelProviderException(
                    "MODEL_CONFIGURATION_INVALID",
                    "Cleanvoice deployment setting " + name + " is invalid",
                    false,
                    exception
            );
        }
        if (!Double.isFinite(parsed)) {
            throw new ModelProviderException(
                    "MODEL_CONFIGURATION_INVALID",
                    "Cleanvoice deployment setting " + name + " is invalid",
                    false
            );
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }

    private static void sleep(int millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException(
                    "PROVIDER_AUDIO_ENHANCEMENT_INTERRUPTED",
                    "Cleanvoice audio enhancement polling was interrupted",
                    false,
                    exception
            );
        }
    }

    private static String safeFileName(String value) {
        return isBlank(value) ? "audio-input" : value.trim();
    }

    private static String baseName(String value) {
        String fileName = safeFileName(value);
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static String stripQuery(String value) {
        int query = value.indexOf('?');
        return query < 0 ? value : value.substring(0, query);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
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

    private record DownloadedAudio(String fileName, String mediaType, byte[] content) {
    }
}
