package com.aibox.platform.catalog;

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
@Table(name = "feature_version")
public class FeatureVersionEntity {

    @Id
    private UUID id;

    @Column(name = "feature_id", nullable = false)
    private UUID featureId;

    @Column(nullable = false)
    private int version;

    @Column(name = "input_schema_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> inputSchema;

    @Column(name = "ui_schema_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> uiSchema;

    @Column(name = "output_schema_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> outputSchema;

    @Column(name = "config_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> config;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FeatureVersionEntity() {
    }

    public int getVersion() {
        return version;
    }

    public Map<String, Object> getInputSchema() {
        return Map.copyOf(inputSchema);
    }

    public Map<String, Object> getUiSchema() {
        return Map.copyOf(uiSchema);
    }

    public Map<String, Object> getOutputSchema() {
        return Map.copyOf(outputSchema);
    }

    public Map<String, Object> getConfig() {
        return Map.copyOf(config);
    }
}
