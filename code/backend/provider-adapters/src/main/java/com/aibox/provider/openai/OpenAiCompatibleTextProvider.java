package com.aibox.provider.openai;

import com.aibox.feature.spi.AudioTranscriptionRequest;
import com.aibox.feature.spi.AudioTranscriptionResponse;
import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.GeneratedAudio;
import com.aibox.feature.spi.GeneratedVideo;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.MultimodalTextGenerationRequest;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.TextToSpeechRequest;
import com.aibox.feature.spi.TextToSpeechResponse;
import com.aibox.feature.spi.VideoGenerationRequest;
import com.aibox.feature.spi.VideoGenerationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public final class OpenAiCompatibleTextProvider implements ModelProviderClient {

    public static final String PROTOCOL = "openai-compatible";
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private final Map<String, ProviderContext> providers;

    public OpenAiCompatibleTextProvider(ModelProviderProperties properties) {
        Map<String, ProviderContext> configured = new LinkedHashMap<>();
        properties.getProviders().forEach((code, config) -> {
            if (!PROTOCOL.equalsIgnoreCase(config.getProtocol())) return;
            if (isBlank(config.getBaseUrl()) || isBlank(config.getApiKey())) {
                throw new IllegalStateException("Provider " + code + " requires base-url and api-key");
            }
            RestClient client = RestClient.builder()
                    .baseUrl(stripTrailingSlash(config.getBaseUrl()))
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                    .defaultHeaders(headers -> config.getHeaders().forEach(headers::set))
                    .build();
            configured.put(code, new ProviderContext(code, client, config));
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
        ProviderContext provider = requireProvider(target);
        Map<String, Object> body = chatBody(
                target.providerModel(), request.systemPrompt(), request.userPrompt(),
                request.maxOutputTokens(), request.temperature()
        );
        return execute(() -> parseTextResponse(
                postJson(provider, provider.config().getChatPath(), request.runId().toString(), body),
                provider.code(), target.providerModel()
        ));
    }

    @Override
    public TextGenerationResponse generateMultimodalText(
            ModelCallTarget target,
            MultimodalTextGenerationRequest request,
            List<ModelAsset> assets
    ) {
        ProviderContext provider = requireProvider(target);
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", request.userPrompt()));
        for (ModelAsset asset : assets) {
            if (!asset.mediaType().startsWith("image/")) {
                throw new ModelProviderException(
                        "PROVIDER_ASSET_TYPE_UNSUPPORTED",
                        "OpenAI-compatible vision accepts image assets only: " + asset.fileName(),
                        false
                );
            }
            String dataUrl = "data:" + asset.mediaType() + ";base64,"
                    + Base64.getEncoder().encodeToString(asset.content());
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", target.providerModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", content)
        ));
        addGenerationOptions(body, request.maxOutputTokens(), request.temperature());
        body.put("stream", false);
        return execute(() -> parseTextResponse(
                postJson(provider, provider.config().getChatPath(), request.runId().toString(), body),
                provider.code(), target.providerModel()
        ));
    }

    @Override
    public AudioTranscriptionResponse transcribeAudio(
            ModelCallTarget target,
            AudioTranscriptionRequest request,
            ModelAsset asset
    ) {
        ProviderContext provider = requireProvider(target);
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("model", target.providerModel());
        if (!isBlank(request.language()) && !"auto".equals(request.language())) {
            body.part("language", request.language());
        }
        if (!isBlank(request.prompt())) body.part("prompt", request.prompt());
        body.part("file", new NamedByteArrayResource(asset.content(), asset.fileName()))
                .contentType(safeMediaType(asset.mediaType()));

        return execute(() -> {
            JsonNode response = provider.client().post()
                    .uri(provider.config().getAudioPath())
                    .header("Idempotency-Key", request.runId().toString())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body.build())
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.path("text").asText(null) == null) {
                throw invalidResponse("Audio transcription response has no text");
            }
            return new AudioTranscriptionResponse(
                    response.path("text").asText(), provider.code(),
                    response.path("model").asText(target.providerModel()), response.path("id").asText(null),
                    nullableInt(response.path("usage"), "input_tokens"),
                    nullableInt(response.path("usage"), "output_tokens")
            );
        });
    }

    @Override
    public ImageGenerationResponse generateImage(
            ModelCallTarget target,
            ImageGenerationRequest request,
            List<ModelAsset> assets
    ) {
        ProviderContext provider = requireProvider(target);
        String imageSize = resolveImageSize(target, request.size());
        if (!assets.isEmpty()) {
            ModelAsset maskAsset = null;
            List<ModelAsset> imageAssets = new ArrayList<>();
            for (ModelAsset asset : assets) {
                if (request.maskAssetId() != null && request.maskAssetId().equals(asset.id())) {
                    maskAsset = asset;
                } else {
                    imageAssets.add(asset);
                }
            }
            if (request.maskAssetId() != null && maskAsset == null) {
                throw new ModelProviderException(
                        "MASK_ASSET_MISSING",
                        "The configured image mask asset is unavailable",
                        false
                );
            }
            if (maskAsset != null && !booleanSetting(target, "supportsImageMask", false)) {
                throw new ModelProviderException(
                        "MODEL_MASK_NOT_SUPPORTED",
                        "The selected image model does not support masked editing",
                        false
                );
            }
            MultipartBodyBuilder body = new MultipartBodyBuilder();
            body.part("model", target.providerModel());
            body.part("prompt", request.prompt());
            body.part("n", request.count());
            if (!isBlank(imageSize)) body.part("size", imageSize);
            addImageOutputOptions(body, request);
            String imagePartName = setting(target, "imagePartName", "image");
            for (ModelAsset asset : imageAssets) {
                if (!asset.mediaType().startsWith("image/")) {
                    throw new ModelProviderException(
                            "PROVIDER_ASSET_TYPE_UNSUPPORTED",
                            "OpenAI-compatible image editing accepts image assets only: " + asset.fileName(),
                            false
                    );
                }
                body.part(imagePartName, new NamedByteArrayResource(asset.content(), asset.fileName()))
                        .contentType(safeMediaType(asset.mediaType()));
            }
            if (maskAsset != null) {
                if (!"image/png".equalsIgnoreCase(maskAsset.mediaType())) {
                    throw new ModelProviderException(
                            "MASK_TYPE_UNSUPPORTED",
                            "Image masks must use image/png",
                            false
                    );
                }
                String maskPartName = setting(target, "maskPartName", "mask");
                body.part(maskPartName, new NamedByteArrayResource(
                        maskAsset.content(),
                        maskAsset.fileName()
                )).contentType(MediaType.IMAGE_PNG);
            }
            return execute(() -> {
                JsonNode response = provider.client().post()
                        .uri(provider.config().getImageEditPath())
                        .header("Idempotency-Key", imageIdempotencyKey(request))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body.build())
                        .retrieve()
                        .body(JsonNode.class);
                return parseImageResponse(response, provider.code(), target.providerModel());
            });
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", target.providerModel());
        body.put("prompt", request.prompt());
        body.put("n", request.count());
        if (!isBlank(imageSize)) body.put("size", imageSize);
        addImageOutputOptions(body, request);

        return execute(() -> parseImageResponse(
                postJson(
                    provider, provider.config().getImagePath(), imageIdempotencyKey(request), body
                ), provider.code(), target.providerModel()
        ));
    }

    @Override
    public TextToSpeechResponse synthesizeSpeech(ModelCallTarget target, TextToSpeechRequest request) {
        ProviderContext provider = requireProvider(target);
        String format = isBlank(request.format()) ? "mp3" : request.format();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", target.providerModel());
        body.put("input", request.text());
        body.put("voice", isBlank(request.voice()) ? "alloy" : request.voice());
        body.put("response_format", format);
        if (request.speed() != null) body.put("speed", request.speed());
        return execute(() -> {
            byte[] content = provider.client().post()
                    .uri(provider.config().getSpeechPath())
                    .header("Idempotency-Key", request.runId().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            if (content == null || content.length == 0) {
                throw invalidResponse("Text-to-speech response is empty");
            }
            return new TextToSpeechResponse(
                    new GeneratedAudio("speech." + format, audioMediaType(format), content),
                    provider.code(), target.providerModel(), null, null, null
            );
        });
    }

    @Override
    public VideoGenerationResponse generateVideo(
            ModelCallTarget target,
            VideoGenerationRequest request,
            List<ModelAsset> assets
    ) {
        ProviderContext provider = requireProvider(target);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", target.providerModel());
        body.put("prompt", request.prompt());
        body.put("n", request.count());
        if (request.durationSeconds() != null) body.put("duration", request.durationSeconds());
        if (!isBlank(request.aspectRatio())) body.put("aspect_ratio", request.aspectRatio());
        if (!isBlank(request.resolution())) body.put("resolution", request.resolution());
        if (!assets.isEmpty()) {
            List<String> inputImages = assets.stream().map(asset -> {
                if (!asset.mediaType().startsWith("image/")) {
                    throw new ModelProviderException(
                            "PROVIDER_ASSET_TYPE_UNSUPPORTED",
                            "OpenAI-compatible video generation accepts image references only: " + asset.fileName(),
                            false
                    );
                }
                return "data:" + asset.mediaType() + ";base64,"
                        + Base64.getEncoder().encodeToString(asset.content());
            }).toList();
            body.put("input_images", inputImages);
        }
        return execute(() -> parseVideoResponse(
                postJson(provider, provider.config().getVideoPath(), request.runId().toString(), body),
                provider.code(), target.providerModel()
        ));
    }

    private static ImageGenerationResponse parseImageResponse(
            JsonNode response,
            String providerCode,
            String fallbackModel
    ) {
        if (response == null || !response.path("data").isArray() || response.path("data").isEmpty()) {
            throw invalidResponse("Image generation response has no images");
        }
        List<GeneratedImage> images = new ArrayList<>();
        response.path("data").forEach(item -> {
            BinaryResult binary = binaryResult(item, "Generated image");
            images.add(new GeneratedImage(
                    binary.url(), item.path("media_type").asText("image/png"),
                    item.path("revised_prompt").asText(null), binary.content()
            ));
        });
        JsonNode usage = response.path("usage");
        return new ImageGenerationResponse(
                images, providerCode, response.path("model").asText(fallbackModel),
                response.path("id").asText(null), nullableInt(usage, "input_tokens"),
                nullableInt(usage, "output_tokens")
        );
    }

    private static VideoGenerationResponse parseVideoResponse(
            JsonNode response,
            String providerCode,
            String fallbackModel
    ) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw invalidResponse("Video generation response has no videos");
        }
        List<GeneratedVideo> videos = new ArrayList<>();
        data.forEach(item -> {
            BinaryResult binary = binaryResult(item, "Generated video");
            videos.add(new GeneratedVideo(
                    binary.url(), item.path("filename").asText("generated.mp4"),
                    item.path("media_type").asText("video/mp4"), binary.content()
            ));
        });
        JsonNode usage = response.path("usage");
        return new VideoGenerationResponse(
                videos, providerCode, response.path("model").asText(fallbackModel),
                response.path("id").asText(null), nullableInt(usage, "input_tokens"),
                nullableInt(usage, "output_tokens")
        );
    }

    private static BinaryResult binaryResult(JsonNode item, String label) {
        String url = item.path("url").asText(null);
        String base64 = item.path("b64_json").asText(item.path("base64").asText(null));
        if (!isBlank(base64)) return new BinaryResult(url, Base64.getDecoder().decode(base64));
        if (!isBlank(url)) {
            byte[] content = RestClient.create().get().uri(url).retrieve().body(byte[].class);
            if (content != null && content.length > 0) return new BinaryResult(url, content);
        }
        throw invalidResponse(label + " has neither binary data nor URL");
    }

    private ProviderContext requireProvider(ModelCallTarget target) {
        ProviderContext provider = providers.get(target.providerCode());
        if (provider == null) {
            throw new ModelProviderException(
                    "MODEL_ADAPTER_NOT_CONFIGURED",
                    "OpenAI-compatible provider is not configured: " + target.providerCode(),
                    false
            );
        }
        return provider;
    }

    private static JsonNode postJson(
            ProviderContext provider,
            String path,
            String idempotencyKey,
            Map<String, Object> body
    ) {
        return provider.client().post()
                .uri(path)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private static TextGenerationResponse parseTextResponse(
            JsonNode response,
            String providerCode,
            String fallbackModel
    ) {
        if (response == null || response.path("choices").isEmpty()) {
            throw invalidResponse("Model response has no choices");
        }
        String result = response.path("choices").path(0).path("message").path("content").asText(null);
        if (result == null) throw invalidResponse("Model response has no text");
        JsonNode usage = response.path("usage");
        return new TextGenerationResponse(
                result, providerCode, response.path("model").asText(fallbackModel),
                response.path("id").asText(null), nullableInt(usage, "prompt_tokens"),
                nullableInt(usage, "completion_tokens")
        );
    }

    private static <T> T execute(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw mapHttpFailure(status, exception.getResponseBodyAsString(), exception);
        } catch (ResourceAccessException exception) {
            throw new ModelProviderException(
                    "PROVIDER_CONNECTION_FAILED", "Model provider could not be reached", true, exception
            );
        }
    }

    static ModelProviderException mapHttpFailure(int status, String responseBody, Throwable cause) {
        String upstreamCode = upstreamErrorCode(responseBody);
        boolean retryable = status == 408 || status == 429 || status >= 500;
        if ("no_available_account".equals(upstreamCode)) {
            return new ModelProviderException(
                    "PROVIDER_NO_AVAILABLE_ACCOUNT",
                    "模型服务当前没有可用账号，请稍后重试",
                    true,
                    cause
            );
        }
        if ("account_pool_usage_limit_reached".equals(upstreamCode)) {
            return new ModelProviderException(
                    "PROVIDER_ACCOUNT_POOL_LIMIT_REACHED",
                    "模型服务账号池额度已达上限，请稍后重试",
                    true,
                    cause
            );
        }
        String message = switch (status) {
            case 401, 403 -> "模型服务鉴权失败，请联系管理员检查供应商配置";
            case 408 -> "模型服务请求超时，请稍后重试";
            case 429 -> "模型服务请求过于频繁，请稍后重试";
            case 503 -> "模型服务暂时不可用，请稍后重试";
            default -> "模型供应商请求失败（HTTP " + status + "）";
        };
        return new ModelProviderException("PROVIDER_HTTP_" + status, message, retryable, cause);
    }

    private static String upstreamErrorCode(String responseBody) {
        if (isBlank(responseBody)) return null;
        try {
            JsonNode response = ERROR_MAPPER.readTree(responseBody);
            String code = response.path("error").path("code").asText(null);
            if (isBlank(code)) code = response.path("code").asText(null);
            return isBlank(code) ? null : code.trim().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, Object> chatBody(
            String model,
            String systemPrompt,
            String userPrompt,
            Integer maxOutputTokens,
            Double temperature
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        addGenerationOptions(body, maxOutputTokens, temperature);
        body.put("stream", false);
        return body;
    }

    private static void addGenerationOptions(Map<String, Object> body, Integer maxTokens, Double temperature) {
        if (maxTokens != null) body.put("max_tokens", maxTokens);
        if (temperature != null) body.put("temperature", temperature);
    }

    private static void addImageOutputOptions(
            MultipartBodyBuilder body,
            ImageGenerationRequest request
    ) {
        String outputFormat = imageOption(request, "outputFormat");
        String background = imageOption(request, "background");
        String inputFidelity = imageOption(request, "inputFidelity");
        if (isAllowed(outputFormat, "png", "jpeg", "webp")) {
            body.part("output_format", outputFormat);
        }
        if (isAllowed(background, "transparent", "opaque", "auto")) {
            body.part("background", background);
        }
        if (isAllowed(inputFidelity, "low", "high")) {
            body.part("input_fidelity", inputFidelity);
        }
    }

    private static void addImageOutputOptions(
            Map<String, Object> body,
            ImageGenerationRequest request
    ) {
        String outputFormat = imageOption(request, "outputFormat");
        String background = imageOption(request, "background");
        String inputFidelity = imageOption(request, "inputFidelity");
        if (isAllowed(outputFormat, "png", "jpeg", "webp")) {
            body.put("output_format", outputFormat);
        }
        if (isAllowed(background, "transparent", "opaque", "auto")) {
            body.put("background", background);
        }
        if (isAllowed(inputFidelity, "low", "high")) {
            body.put("input_fidelity", inputFidelity);
        }
    }

    private static String imageOption(ImageGenerationRequest request, String name) {
        Object value = request.metadata().get(name);
        return value == null ? null : value.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String imageIdempotencyKey(ImageGenerationRequest request) {
        Object configured = request.metadata().get("providerInvocationKey");
        if (configured == null || configured.toString().isBlank()) {
            return request.runId().toString();
        }
        return UUID.nameUUIDFromBytes((
                request.runId() + ":" + configured.toString().trim()
        ).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean isAllowed(String value, String... allowedValues) {
        if (isBlank(value)) return false;
        for (String allowed : allowedValues) {
            if (allowed.equals(value)) return true;
        }
        return false;
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.intValue() : null;
    }

    static String resolveImageSize(ModelCallTarget target, String requestedSize) {
        if (isBlank(requestedSize)) return requestedSize;
        Object configured = target.settings().get("imageSizeMap");
        if (configured instanceof Map<?, ?> mapping) {
            Object resolved = mapping.get(requestedSize);
            if (resolved != null && !resolved.toString().isBlank()) {
                return resolved.toString();
            }
        }
        return requestedSize;
    }

    private static String setting(ModelCallTarget target, String name, String fallback) {
        Object value = target.settings().get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static boolean booleanSetting(ModelCallTarget target, String name, boolean fallback) {
        Object value = target.settings().get(name);
        if (value instanceof Boolean booleanValue) return booleanValue;
        if (value == null || value.toString().isBlank()) return fallback;
        return Boolean.parseBoolean(value.toString());
    }

    private static MediaType safeMediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static ModelProviderException invalidResponse(String message) {
        return new ModelProviderException("PROVIDER_INVALID_RESPONSE", message, false);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String audioMediaType(String format) {
        return switch (format.toLowerCase()) {
            case "wav" -> "audio/wav";
            case "opus" -> "audio/opus";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "pcm" -> "audio/L16";
            default -> "audio/mpeg";
        };
    }

    private record BinaryResult(String url, byte[] content) {
    }

    private record ProviderContext(
            String code,
            RestClient client,
            ModelProviderProperties.Provider config
    ) {
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] content, String filename) {
            super(content);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
