package com.aibox.feature.spi;

public interface StreamingFeatureHandler extends FeatureHandler {

    FeatureExecutionResult execute(
            FeatureExecutionContext context,
            ModelGateway modelGateway,
            FeatureOutputEmitter outputEmitter
    );

    @Override
    default FeatureExecutionResult execute(
            FeatureExecutionContext context,
            ModelGateway modelGateway
    ) {
        return execute(context, modelGateway, FeatureOutputEmitter.noop());
    }
}
