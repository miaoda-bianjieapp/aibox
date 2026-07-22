package com.aibox.platform.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "task_run")
public class TaskRunEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "run_number", nullable = false)
    private int runNumber;

    @Column(name = "feature_code", nullable = false, length = 120)
    private String featureCode;

    @Column(name = "feature_version", nullable = false)
    private int featureVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RunStatus status;

    @Column(name = "parameters_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> parameters;

    @Column(name = "input_asset_ids_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<UUID> inputAssetIds;

    @Column(name = "base_artifact_id")
    private UUID baseArtifactId;

    @Column(name = "selected_model_code", length = 120)
    private String selectedModelCode;

    @Column(name = "selected_models_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> selectedModels;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 2_000)
    private String errorMessage;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected TaskRunEntity() {
    }

    public TaskRunEntity(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID taskId,
            int runNumber,
            String featureCode,
            int featureVersion,
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            UUID baseArtifactId,
            String selectedModelCode,
            Instant now
    ) {
        this(id, tenantId, userId, taskId, runNumber, featureCode, featureVersion,
                parameters, inputAssetIds, baseArtifactId, selectedModelCode, Map.of(), now);
    }

    public TaskRunEntity(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID taskId,
            int runNumber,
            String featureCode,
            int featureVersion,
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            UUID baseArtifactId,
            String selectedModelCode,
            Map<String, String> selectedModels,
            Instant now
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.taskId = taskId;
        this.runNumber = runNumber;
        this.featureCode = featureCode;
        this.featureVersion = featureVersion;
        this.status = RunStatus.QUEUED;
        this.parameters = Map.copyOf(parameters);
        this.inputAssetIds = List.copyOf(inputAssetIds);
        this.baseArtifactId = baseArtifactId;
        this.selectedModelCode = selectedModelCode;
        this.selectedModels = Map.copyOf(selectedModels == null ? Map.of() : selectedModels);
        this.queuedAt = now;
        this.createdAt = now;
    }

    public TaskRunEntity(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID taskId,
            int runNumber,
            String featureCode,
            int featureVersion,
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            UUID baseArtifactId,
            Instant now
    ) {
        this(id, tenantId, userId, taskId, runNumber, featureCode, featureVersion,
                parameters, inputAssetIds, baseArtifactId, null, now);
    }

    public TaskRunEntity(
            UUID id,
            UUID tenantId,
            UUID userId,
            UUID taskId,
            int runNumber,
            String featureCode,
            int featureVersion,
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            Instant now
    ) {
        this(id, tenantId, userId, taskId, runNumber, featureCode, featureVersion,
                parameters, inputAssetIds, null, null, now);
    }

    public void markRunning(Instant now) {
        requireStatus(RunStatus.QUEUED);
        this.status = RunStatus.RUNNING;
        this.startedAt = now;
    }

    public void markSucceeded(Instant now) {
        requireStatus(RunStatus.RUNNING);
        this.status = RunStatus.SUCCEEDED;
        this.finishedAt = now;
    }

    public void markPartial(Instant now) {
        if (status != RunStatus.RUNNING && status != RunStatus.CANCELLED) {
            throw new IllegalStateException("Run cannot become partial from status " + status);
        }
        this.status = RunStatus.PARTIAL;
        this.finishedAt = now;
    }

    public void markFailed(String code, String message, Instant now) {
        if (status.isTerminal()) {
            return;
        }
        this.status = RunStatus.FAILED;
        this.errorCode = code;
        this.errorMessage = message;
        this.finishedAt = now;
    }

    public void cancel(Instant now) {
        if (status.isTerminal()) {
            return;
        }
        this.cancelRequested = true;
        this.status = RunStatus.CANCELLED;
        this.finishedAt = now;
    }

    private void requireStatus(RunStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected run status " + expected + " but was " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public int getRunNumber() {
        return runNumber;
    }

    public String getFeatureCode() {
        return featureCode;
    }

    public int getFeatureVersion() {
        return featureVersion;
    }

    public RunStatus getStatus() {
        return status;
    }

    public Map<String, Object> getParameters() {
        return Map.copyOf(parameters);
    }

    public List<UUID> getInputAssetIds() {
        return List.copyOf(inputAssetIds);
    }

    public UUID getBaseArtifactId() {
        return baseArtifactId;
    }

    public String getSelectedModelCode() {
        return selectedModelCode;
    }

    public Map<String, String> getSelectedModels() {
        return selectedModels == null ? Map.of() : Map.copyOf(selectedModels);
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
