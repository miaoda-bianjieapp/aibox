package com.aibox.provider.openai;

import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleTextProviderTest {

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
}
