package com.aibox.provider.openai;

import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiCompatibleTextProviderTest {

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
}
