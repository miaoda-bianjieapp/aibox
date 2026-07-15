package com.aibox.platform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "model_provider")
public class ModelProviderEntity {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 80) private String code;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(nullable = false, length = 80) private String protocol;
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_kind", nullable = false, length = 20)
    private ModelProviderKind providerKind;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected ModelProviderEntity() {}
    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
    public String getProtocol() { return protocol; }
    public ModelProviderKind getProviderKind() { return providerKind; }
    public boolean isEnabled() { return enabled; }
}
