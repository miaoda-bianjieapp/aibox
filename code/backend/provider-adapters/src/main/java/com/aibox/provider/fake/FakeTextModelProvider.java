package com.aibox.provider.fake;

import com.aibox.feature.spi.AudioTranscriptionRequest;
import com.aibox.feature.spi.AudioTranscriptionResponse;
import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelCallTarget;
import com.aibox.feature.spi.ModelProviderClient;
import com.aibox.feature.spi.MultimodalTextGenerationRequest;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "yuanzuo.model.fake-enabled", havingValue = "true")
public final class FakeTextModelProvider implements ModelProviderClient {

    @Override
    public String adapterCode() {
        return "fake";
    }

    @Override
    public boolean supports(ModelCallTarget target) {
        return "local-fake".equals(target.providerCode());
    }

    @Override
    public TextGenerationResponse generateText(ModelCallTarget target, TextGenerationRequest request) {
        String result = "# Local test draft\n\n" + request.userPrompt();
        return textResponse(target, result);
    }

    @Override
    public TextGenerationResponse generateMultimodalText(
            ModelCallTarget target,
            MultimodalTextGenerationRequest request,
            List<ModelAsset> assets
    ) {
        String files = assets.stream().map(ModelAsset::fileName).reduce((left, right) -> left + ", " + right)
                .orElse("none");
        return textResponse(target, "# Local multimodal result\n\nFiles: " + files + "\n\n" + request.userPrompt());
    }

    @Override
    public AudioTranscriptionResponse transcribeAudio(
            ModelCallTarget target,
            AudioTranscriptionRequest request,
            ModelAsset asset
    ) {
        return new AudioTranscriptionResponse(
                "Received audio file: " + asset.fileName(), target.providerCode(), target.providerModel(),
                UUID.randomUUID().toString(), 0, 0
        );
    }

    @Override
    public ImageGenerationResponse generateImage(ModelCallTarget target, ImageGenerationRequest request) {
        return new ImageGenerationResponse(
                List.of(new GeneratedImage(
                        null,
                        "image/png",
                        request.prompt(),
                        java.util.Base64.getDecoder().decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nWQAAAAASUVORK5CYII="
                        )
                )),
                target.providerCode(), target.providerModel(), UUID.randomUUID().toString(), 0, 0
        );
    }

    private static TextGenerationResponse textResponse(ModelCallTarget target, String text) {
        return new TextGenerationResponse(
                text, target.providerCode(), target.providerModel(), UUID.randomUUID().toString(), 0, 0
        );
    }
}
