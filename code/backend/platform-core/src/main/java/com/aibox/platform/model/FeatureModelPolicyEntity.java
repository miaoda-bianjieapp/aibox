package com.aibox.platform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "feature_model_policy")
public class FeatureModelPolicyEntity {
    @Id private UUID id;
    @Column(name = "feature_code", nullable = false, length = 120) private String featureCode;
    @Column(nullable = false, length = 80) private String capability;
    @Column(name = "default_deployment_code", nullable = false, length = 120) private String defaultDeploymentCode;
    @Column(name = "allow_user_selection", nullable = false) private boolean allowUserSelection;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected FeatureModelPolicyEntity() {}
    public UUID getId() { return id; }
    public String getFeatureCode() { return featureCode; }
    public String getCapability() { return capability; }
    public String getDefaultDeploymentCode() { return defaultDeploymentCode; }
    public boolean isAllowUserSelection() { return allowUserSelection; }
}

