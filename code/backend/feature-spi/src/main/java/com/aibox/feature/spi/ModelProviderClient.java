package com.aibox.feature.spi;

import java.util.List;

public interface ModelProviderClient {

    String adapterCode();

    boolean supports(ModelCallTarget target);

    TextGenerationResponse generateText(ModelCallTarget target, TextGenerationRequest request);

    default TextGenerationResponse generateMultimodalText(
            ModelCallTarget target,
            MultimodalTextGenerationRequest request,
            List<ModelAsset> assets
    ) {
        throw unsupported(target);
    }

    default AudioTranscriptionResponse transcribeAudio(
            ModelCallTarget target,
            AudioTranscriptionRequest request,
            ModelAsset asset
    ) {
        throw unsupported(target);
    }

    default ImageGenerationResponse generateImage(ModelCallTarget target, ImageGenerationRequest request) {
        throw unsupported(target);
    }

    private ModelProviderException unsupported(ModelCallTarget target) {
        return new ModelProviderException(
                "MODEL_CAPABILITY_NOT_SUPPORTED",
                adapterCode() + " does not support " + target.capability(),
                false
        );
    }
}
