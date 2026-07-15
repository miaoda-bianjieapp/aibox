package com.aibox.platform.execution;

import com.aibox.feature.spi.FeatureHandler;
import com.aibox.platform.common.NotFoundException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public final class FeatureRegistry {

    private final Map<String, FeatureHandler> handlers;

    public FeatureRegistry(List<FeatureHandler> featureHandlers) {
        Map<String, FeatureHandler> registry = new HashMap<>();
        for (FeatureHandler handler : featureHandlers) {
            FeatureHandler existing = registry.put(handler.featureCode(), handler);
            if (existing != null) {
                throw new IllegalStateException("Duplicate FeatureHandler: " + handler.featureCode());
            }
        }
        this.handlers = Map.copyOf(registry);
    }

    public FeatureHandler require(String featureCode) {
        FeatureHandler handler = handlers.get(featureCode);
        if (handler == null) {
            throw new NotFoundException("feature handler", featureCode);
        }
        return handler;
    }
}

