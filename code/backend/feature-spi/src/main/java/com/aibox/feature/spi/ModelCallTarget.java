package com.aibox.feature.spi;

import java.util.Map;

public record ModelCallTarget(
        String deploymentCode,
        String providerCode,
        String providerModel,
        ModelCapability capability,
        Map<String, Object> settings
) {
    public ModelCallTarget {
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }
}
