package com.aibox.platform.prompt;

import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;

@FunctionalInterface
public interface PromptOptimizationModelGateway {

    TextGenerationResponse generatePromptOptimization(TextGenerationRequest request);
}
