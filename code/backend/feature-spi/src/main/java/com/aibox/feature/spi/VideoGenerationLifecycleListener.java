package com.aibox.feature.spi;

import java.util.Map;

public interface VideoGenerationLifecycleListener {

    VideoGenerationLifecycleListener NOOP = new VideoGenerationLifecycleListener() {
        @Override
        public void onSubmitted(
                String providerRequestId,
                String providerModel,
                Map<String, Object> providerState
        ) {
        }

        @Override
        public void onPhase(String phase, Map<String, Object> providerState) {
        }
    };

    void onSubmitted(
            String providerRequestId,
            String providerModel,
            Map<String, Object> providerState
    );

    void onPhase(String phase, Map<String, Object> providerState);
}
