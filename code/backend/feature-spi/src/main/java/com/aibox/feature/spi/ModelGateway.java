package com.aibox.feature.spi;

@FunctionalInterface
public interface ModelGateway {

    TextGenerationResponse generateText(TextGenerationRequest request);

    default TextGenerationResponse generateTextStream(
            TextGenerationRequest request,
            TextGenerationListener listener
    ) {
        TextGenerationResponse response = generateText(request);
        listener.onDelta(response.text());
        return response;
    }

    default TextGenerationResponse generateMultimodalText(MultimodalTextGenerationRequest request) {
        throw unsupported(ModelCapability.VISION);
    }

    default AudioTranscriptionResponse transcribeAudio(AudioTranscriptionRequest request) {
        throw unsupported(ModelCapability.AUDIO_TRANSCRIPTION);
    }

    default ImageGenerationResponse generateImage(ImageGenerationRequest request) {
        throw unsupported(ModelCapability.IMAGE_GENERATION);
    }

    default ImageExpansionResponse expandImage(ImageExpansionRequest request) {
        throw unsupported(ModelCapability.IMAGE_GENERATION);
    }

    default TextToSpeechResponse synthesizeSpeech(TextToSpeechRequest request) {
        throw unsupported(ModelCapability.TEXT_TO_SPEECH);
    }

    default VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
        throw unsupported(ModelCapability.VIDEO_GENERATION);
    }

    private static ModelProviderException unsupported(ModelCapability capability) {
        return new ModelProviderException(
                "MODEL_CAPABILITY_NOT_SUPPORTED", "Model gateway does not support " + capability, false
        );
    }
}
