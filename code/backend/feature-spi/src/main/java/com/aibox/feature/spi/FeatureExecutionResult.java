package com.aibox.feature.spi;

import java.util.List;

public record FeatureExecutionResult(List<ArtifactDraft> artifacts) {
    public FeatureExecutionResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("A successful feature execution must create at least one artifact");
        }
    }

    public static FeatureExecutionResult of(ArtifactDraft artifact) {
        return new FeatureExecutionResult(List.of(artifact));
    }
}

