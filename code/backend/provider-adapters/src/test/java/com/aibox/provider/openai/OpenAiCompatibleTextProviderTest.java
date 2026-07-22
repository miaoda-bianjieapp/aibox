package com.aibox.provider.openai;

import com.aibox.feature.spi.ImageExpansionRequest;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImagePreservationMode;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleTextProviderTest {

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
