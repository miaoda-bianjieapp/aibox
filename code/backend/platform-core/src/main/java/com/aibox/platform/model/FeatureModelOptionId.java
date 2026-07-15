package com.aibox.platform.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class FeatureModelOptionId implements Serializable {
    private UUID policyId;
    private String deploymentCode;
    public FeatureModelOptionId() {}
    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof FeatureModelOptionId other)) return false;
        return Objects.equals(policyId, other.policyId) && Objects.equals(deploymentCode, other.deploymentCode);
    }
    @Override public int hashCode() { return Objects.hash(policyId, deploymentCode); }
}

