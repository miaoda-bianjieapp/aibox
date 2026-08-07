package com.aibox.provider.did;

import com.aibox.feature.spi.GeneratedVideo;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.VideoGenerationRequest;
import com.aibox.feature.spi.VideoGenerationResponse;
import com.aibox.provider.openai.ModelProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public final class DidVideoProvider implements ModelProviderClient {

    public static final String PROTOCOL = "d-id";
    private static final int MAX_AUDIO_BYTES = 6 * 1024 * 1024;

    private final Map<String, ProviderContext> providers;

    public DidVideoProvider(ModelProviderProperties properties) {
        Map<String, ProviderContext> configured = new LinkedHashMap<>();
        properties.getProviders().forEach((code, config) -> {
            if (!PROTOCOL.equalsIgnoreCase(config.getProtocol())) return;
            if (isBlank(config.getBaseUrl()) || isBlank(config.getApiKey())) {
                throw new IllegalStateException("Provider " + code + " requires base-url and api-key");
            }
            String header = isBlank(config.getApiKeyHeader()) ? "Authorization" : config.getApiKeyHeader().trim();
            String prefix = config.getApiKeyPrefix() == null ? "Basic " : config.getApiKeyPrefix();
            RestClient client = RestClient.builder()
                    .baseUrl(stripTrailingSlash(config.getBaseUrl()))
                    .defaultHeader(header, prefix + config.getApiKey())
                    .defaultHeaders(headers -> config.getHeaders().forEach(headers::set))
                    .build();
            configured.put(code, new ProviderContext(code, client, RestClient.create(), config));
        });
        this.providers = Map.copyOf(configured);
    }

    @Override
    public String adapterCode() {
        return PROTOCOL;
    }

    @Override
    public boolean supports(ModelCallTarget target) {
        return providers.containsKey(target.providerCode());
    }

    @Override
    public TextGenerationResponse generateText(ModelCallTarget target, TextGenerationRequest request) {
        throw unsupported("D-ID official adapter only exposes asynchronous Talks video generation");
    }

    @Override
    public VideoGenerationResponse generateVideo(
            ModelCallTarget target,
            VideoGenerationRequest request,
            List<ModelAsset> assets
    ) {
        ProviderContext provider = requireProvider(target);
        if (request.count() != 1) {
            throw new ModelProviderException("PROVIDER_COUNT_UNSUPPORTED", "D-ID Talks supports one video per request", false);
        }
        String voiceMode = metadataString(request, "voiceGenerationMode");
        if ("VIDEO_NATIVE".equalsIgnoreCase(voiceMode)) {
            throw new ModelProviderException(
                    "MODEL_NATIVE_AUDIO_NOT_SUPPORTED",
                    "D-ID native text-to-video voice is disabled because no separately confirmable audio preview is available",
                    false
            );
        }
        ModelAsset image = singleAsset(assets, "image/", "MODEL_REFERENCE_IMAGE_REQUIRED", "D-ID requires exactly one avatar image");
        ModelAsset audio = singleAsset(assets, "audio/", "MODEL_AUDIO_INPUT_REQUIRED", "D-ID requires exactly one confirmed audio input");
        if (!("image/png".equals(image.mediaType()) || "image/jpeg".equals(image.mediaType()))) {
            throw new ModelProviderException(
                    "PROVIDER_ASSET_TYPE_UNSUPPORTED",
                    "D-ID image upload supports PNG and JPEG only",
                    false
            );
        }
        if (audio.content().length > MAX_AUDIO_BYTES) {
            throw new ModelProviderException(
                    "PROVIDER_FILE_TOO_LARGE",
                    "D-ID audio upload cannot exceed 6 MB",
                    false
            );
        }
        String imageUrl = upload(provider, target, image, "image", "imageUploadPath", provider.config().getImagePath());
        String audioUrl = upload(provider, target, audio, "audio", "audioUploadPath", provider.config().getAudioPath());

        Map<String, Object> script = new LinkedHashMap<>();
        script.put("type", "audio");
        script.put("audio_url", audioUrl);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("stitch", booleanSetting(target, "stitch", true));
        config.put("fluent", booleanSetting(target, "fluent", true));
        config.put("pad_audio", numberSetting(target, "padAudioSeconds", 0.0));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_url", imageUrl);
        body.put("script", script);
        body.put("config", config);
        String title = metadataString(request, "title");
        if (!title.isBlank()) body.put("name", title);

        JsonNode submitted = postJson(
                provider.client(),
                setting(target, "videoPath", provider.config().getVideoPath()),
                body,
                "D-ID talk submission failed"
        );
        String talkId = firstText(submitted, "id", "talk_id", "data.id");
        if (talkId.isBlank()) throw invalidResponse("D-ID talk submission response has no id");
        try {
            return poll(provider, target, talkId);
        } catch (ModelProviderException exception) {
            throw exception.withProviderRequestId(talkId, false);
        }
    }

    private VideoGenerationResponse poll(ProviderContext provider, ModelCallTarget target, String talkId) {
        String statusPath = setting(target, "videoStatusPath", provider.config().getVideoStatusPath());
        if (!statusPath.contains("{taskId}")) {
            throw new ModelProviderException(
                    "PROVIDER_VIDEO_STATUS_NOT_CONFIGURED",
                    "D-ID status path must contain {taskId}",
                    false
            );
        }
        int intervalMillis = intSetting(target, "videoPollIntervalMillis", 5_000, 1, 60_000);
        int timeoutSeconds = intSetting(target, "videoPollTimeoutSeconds", 900, 1, 3_600);
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        String path = statusPath.replace("{taskId}", talkId);
        while (System.nanoTime() < deadline) {
            JsonNode result = getJson(provider.client(), path, "D-ID talk status query failed");
            String status = firstText(result, "status", "data.status").toLowerCase(Locale.ROOT);
            if ("done".equals(status) || "completed".equals(status)) {
                String resultUrl = firstText(result, "result_url", "resultUrl", "data.result_url");
                if (resultUrl.isBlank()) throw invalidResponse("D-ID completed talk has no result_url");
                byte[] content = download(provider.downloadClient(), resultUrl);
                return new VideoGenerationResponse(
                        List.of(new GeneratedVideo(
                                resultUrl,
                                "d-id-" + safeFileSegment(talkId) + ".mp4",
                                "video/mp4",
                                content
                        )),
                        provider.code(),
                        target.providerModel(),
                        talkId,
                        null,
                        null
                );
            }
            if ("error".equals(status) || "failed".equals(status) || "rejected".equals(status)) {
                String message = firstText(result, "error.description", "error.message", "error", "message");
                throw new ModelProviderException(
                        "PROVIDER_VIDEO_FAILED",
                        message.isBlank() ? "D-ID talk generation failed" : message,
                        false
                );
            }
            sleep(intervalMillis);
        }
        throw new ModelProviderException(
                "PROVIDER_VIDEO_TIMEOUT",
                "D-ID talk generation did not finish before the polling timeout",
                false
        );
    }

    private static String upload(
            ProviderContext provider,
            ModelCallTarget target,
            ModelAsset asset,
            String partName,
            String settingName,
            String configuredPath
    ) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part(partName, resource(asset)).contentType(safeMediaType(asset.mediaType()));
        JsonNode response = postMultipart(
                provider.client(),
                setting(target, settingName, configuredPath),
                body,
                "D-ID " + partName + " upload failed"
        );
        String url = firstText(response, "url", partName + "_url", "data.url");
        if (url.isBlank()) throw invalidResponse("D-ID " + partName + " upload response has no url");
        return url;
    }

    private static JsonNode postMultipart(
            RestClient client,
            String path,
            MultipartBodyBuilder body,
            String message
    ) {
        try {
            JsonNode response = client.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) throw invalidResponse(message + ": empty response");
            return response;
        } catch (RestClientResponseException exception) {
            throw httpError(message, exception);
        } catch (ResourceAccessException exception) {
            throw unavailable(message, exception);
        }
    }

    private static JsonNode postJson(RestClient client, String path, Map<String, Object> body, String message) {
        try {
            JsonNode response = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) throw invalidResponse(message + ": empty response");
            return response;
        } catch (RestClientResponseException exception) {
            throw httpError(message, exception);
        } catch (ResourceAccessException exception) {
            throw unavailable(message, exception);
        }
    }

    private static JsonNode getJson(RestClient client, String path, String message) {
        try {
            JsonNode response = client.get().uri(path).retrieve().body(JsonNode.class);
            if (response == null) throw invalidResponse(message + ": empty response");
            return response;
        } catch (RestClientResponseException exception) {
            throw httpError(message, exception);
        } catch (ResourceAccessException exception) {
            throw unavailable(message, exception);
        }
    }

    private static byte[] download(RestClient client, String url) {
        try {
            byte[] content = client.get().uri(url).retrieve().body(byte[].class);
            if (content == null || content.length == 0) throw invalidResponse("D-ID video download is empty");
            return content;
        } catch (RestClientResponseException exception) {
            throw httpError("D-ID video download failed", exception);
        } catch (ResourceAccessException exception) {
            throw unavailable("D-ID video download failed", exception);
        }
    }

    private static ModelAsset singleAsset(
            List<ModelAsset> assets,
            String mediaPrefix,
            String code,
            String message
    ) {
        List<ModelAsset> matches = assets == null ? List.of() : assets.stream()
                .filter(asset -> asset.mediaType() != null && asset.mediaType().startsWith(mediaPrefix))
                .toList();
        if (matches.size() != 1) throw new ModelProviderException(code, message, false);
        return matches.get(0);
    }

    private static ByteArrayResource resource(ModelAsset asset) {
        return new ByteArrayResource(asset.content()) {
            @Override
            public String getFilename() {
                return safeUploadFileName(asset.fileName());
            }
        };
    }

    private static MediaType safeMediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String setting(ModelCallTarget target, String name, String fallback) {
        Object value = target.settings().get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString().trim();
    }

    private static boolean booleanSetting(ModelCallTarget target, String name, boolean fallback) {
        Object value = target.settings().get(name);
        if (value instanceof Boolean enabled) return enabled;
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static double numberSetting(ModelCallTarget target, String name, double fallback) {
        Object value = target.settings().get(name);
        if (value == null) return fallback;
        try {
            return value instanceof Number number ? number.doubleValue() : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int intSetting(ModelCallTarget target, String name, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(setting(target, name, Integer.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String metadataString(VideoGenerationRequest request, String name) {
        Object value = request.metadata().get(name);
        return value == null ? "" : value.toString().trim();
    }

    private static String firstText(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode value = node;
            for (String part : path.split("\\.")) value = value == null ? null : value.path(part);
            if (value != null && !value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private static ModelProviderException httpError(String message, RestClientResponseException exception) {
        return new ModelProviderException(
                "PROVIDER_HTTP_" + exception.getStatusCode().value(),
                message + " with HTTP " + exception.getStatusCode().value(),
                exception.getStatusCode().is5xxServerError() || exception.getStatusCode().value() == 429,
                exception
        );
    }

    private static ModelProviderException unavailable(String message, ResourceAccessException exception) {
        return new ModelProviderException("PROVIDER_UNAVAILABLE", message, true, exception);
    }

    private static ModelProviderException invalidResponse(String message) {
        return new ModelProviderException("PROVIDER_INVALID_RESPONSE", message, false);
    }

    private static ModelProviderException unsupported(String message) {
        return new ModelProviderException("MODEL_CAPABILITY_NOT_SUPPORTED", message, false);
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException(
                    "PROVIDER_VIDEO_POLL_INTERRUPTED",
                    "D-ID video polling interrupted",
                    false,
                    exception
            );
        }
    }

    private ProviderContext requireProvider(ModelCallTarget target) {
        ProviderContext provider = providers.get(target.providerCode());
        if (provider == null) {
            throw new ModelProviderException(
                    "MODEL_PROVIDER_NOT_CONFIGURED",
                    "D-ID provider is not configured",
                    false
            );
        }
        return provider;
    }

    private static String safeUploadFileName(String value) {
        String safe = safeFileSegment(value == null ? "asset" : value);
        return safe.length() <= 50 ? safe : safe.substring(0, 50);
    }

    private static String safeFileSegment(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ProviderContext(
            String code,
            RestClient client,
            RestClient downloadClient,
            ModelProviderProperties.Provider config
    ) {
    }
}
