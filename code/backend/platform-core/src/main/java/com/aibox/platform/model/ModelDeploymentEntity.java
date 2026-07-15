package com.aibox.platform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "model_deployment")
public class ModelDeploymentEntity {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 120) private String code;
    @Column(name = "provider_code", nullable = false, length = 80) private String providerCode;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(nullable = false, length = 500) private String description;
    @Column(nullable = false, length = 80) private String capability;
    @Column(name = "provider_model", nullable = false, length = 160) private String providerModel;
    @Column(nullable = false) private boolean enabled;
    @Column(nullable = false) private boolean selectable;
    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> config;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected ModelDeploymentEntity() {}
    public String getCode() { return code; }
    public String getProviderCode() { return providerCode; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getCapability() { return capability; }
    public String getProviderModel() { return providerModel; }
    public boolean isEnabled() { return enabled; }
    public boolean isSelectable() { return selectable; }
    public Map<String, Object> getConfig() { return config == null ? Map.of() : Map.copyOf(config); }
}

