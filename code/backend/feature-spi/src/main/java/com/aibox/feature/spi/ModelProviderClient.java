package com.aibox.feature.spi;

import java.util.List;

public interface ModelProviderClient {

    String adapterCode();

    boolean supports(ModelCallTarget target);

    TextGenerationResponse generateText(ModelCallTarget target, TextGenerationRequest request);

    default TextGenerationResponse generateTextStream(
            ModelCallTarget target,
            TextGenerationRequest request,
            TextGenerationListener listener
    ) {
        TextGenerationResponse response = generateText(target, request);
        listener.onDelta(response.text());
        return response;
    }

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

    default AudioEnhancementResponse enhanceAudio(
            ModelCallTarget target,
            AudioEnhancementRequest request,
            ModelAsset asset
    ) {
        throw unsupported(target);
    }

    default ImageGenerationResponse generateImage(
            ModelCallTarget target,
            ImageGenerationRequest request,
            List<ModelAsset> assets
    ) {
        return generateImage(target, request);
    }

    default ImageGenerationResponse generateImage(ModelCallTarget target, ImageGenerationRequest request) {
        throw unsupported(target);
    }

    default ImageExpansionResponse expandImage(
            ModelCallTarget target,
            ImageExpansionRequest request,
            ModelAsset asset
    ) {
        throw unsupported(target);
    }

    default TextToSpeechResponse synthesizeSpeech(ModelCallTarget target, TextToSpeechRequest request) {
        throw unsupported(target);
    }

    default VideoGenerationResponse generateVideo(
            ModelCallTarget target,
            VideoGenerationRequest request,
            List<ModelAsset> assets
    ) {
        throw unsupported(target);
    }

    default boolean supportsResumableVideo(ModelCallTarget target) {
        return false;
    }

    default VideoGenerationResponse generateVideoResumable(
            ModelCallTarget target,
            VideoGenerationRequest request,
            List<ModelAsset> assets,
            String providerRequestId,
            VideoGenerationLifecycleListener listener
    ) {
        return generateVideo(target, request, assets);
    }

    private ModelProviderException unsupported(ModelCallTarget target) {
        return new ModelProviderException(
                "MODEL_CAPABILITY_NOT_SUPPORTED",
                adapterCode() + " does not support " + target.capability(),
                false
        );
    }
}
