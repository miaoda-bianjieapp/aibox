package com.aibox.feature.spi;

@FunctionalInterface
public interface ModelGateway {

    TextGenerationResponse generateText(TextGenerationRequest request);

    default TextGenerationResponse generateMultimodalText(MultimodalTextGenerationRequest request) {
        throw unsupported(ModelCapability.VISION);
    }

    default AudioTranscriptionResponse transcribeAudio(AudioTranscriptionRequest request) {
        throw unsupported(ModelCapability.AUDIO_TRANSCRIPTION);
    }

    default ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        throw unsupported(ModelCapability.IMAGE_GENERATION);
    }

    private static ModelProviderException unsupported(ModelCapability capability) {
        return new ModelProviderException(
                "MODEL_CAPABILITY_NOT_SUPPORTED", "Model gateway does not support " + capability, false
        );
    }
}
