package com.aibox.provider.openai;

import com.aibox.feature.spi.AudioTranscriptionRequest;
import com.aibox.feature.spi.AudioTranscriptionResponse;
import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.MultimodalTextGenerationRequest;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public final class OpenAiCompatibleTextProvider implements ModelProviderClient {

    public static final String PROTOCOL = "openai-compatible";

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
    public ImageGenerationResponse generateImage(ModelCallTarget target, ImageGenerationRequest request) {
        ProviderContext provider = requireProvider(target);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", target.providerModel());
        body.put("prompt", request.prompt());
        body.put("n", request.count());
        if (!isBlank(request.size())) body.put("size", request.size());

        return execute(() -> {
            JsonNode response = postJson(
                    provider, provider.config().getImagePath(), request.runId().toString(), body
            );
            if (response == null || !response.path("data").isArray() || response.path("data").isEmpty()) {
                throw invalidResponse("Image generation response has no images");
            }
            List<GeneratedImage> images = new ArrayList<>();
            response.path("data").forEach(item -> {
                String url = item.path("url").asText(null);
                String base64 = item.path("b64_json").asText(null);
                byte[] contentBytes;
                if (!isBlank(base64)) {
                    contentBytes = Base64.getDecoder().decode(base64);
                } else if (!isBlank(url)) {
                    contentBytes = RestClient.create().get().uri(url).retrieve().body(byte[].class);
                } else {
                    throw invalidResponse("Generated image has neither binary data nor URL");
                }
                images.add(new GeneratedImage(
                        url, "image/png", item.path("revised_prompt").asText(null), contentBytes
                ));
            });
            JsonNode usage = response.path("usage");
            return new ImageGenerationResponse(
                    images, provider.code(), response.path("model").asText(target.providerModel()),
                    response.path("id").asText(null), nullableInt(usage, "input_tokens"),
                    nullableInt(usage, "output_tokens")
            );
        });
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
            throw new ModelProviderException(
                    "PROVIDER_HTTP_" + status, "Model provider returned HTTP " + status,
                    status == 408 || status == 429 || status >= 500, exception
            );
        } catch (ResourceAccessException exception) {
            throw new ModelProviderException(
                    "PROVIDER_CONNECTION_FAILED", "Model provider could not be reached", true, exception
            );
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

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.intValue() : null;
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
