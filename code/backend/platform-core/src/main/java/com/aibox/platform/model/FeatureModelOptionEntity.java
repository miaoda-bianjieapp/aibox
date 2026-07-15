package com.aibox.platform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@IdClass(FeatureModelOptionId.class)
@Table(name = "feature_model_option")
public class FeatureModelOptionEntity {
    @Id @Column(name = "policy_id") private UUID policyId;
    @Id @Column(name = "deployment_code", length = 120) private String deploymentCode;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(nullable = false, length = 500) private String description;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(nullable = false) private boolean enabled;
    protected FeatureModelOptionEntity() {}
    public String getDeploymentCode() { return deploymentCode; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public int getSortOrder() { return sortOrder; }
}
