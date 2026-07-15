package com.aibox.feature.spi;

public interface FeatureHandler {

    String featureCode();

    default void validate(FeatureExecutionContext context) {
    }

    FeatureExecutionResult execute(FeatureExecutionContext context, ModelGateway modelGateway);
}

