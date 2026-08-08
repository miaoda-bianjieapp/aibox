package com.aibox.platform.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "provider_invocation")
public class ProviderInvocationEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "run_id")
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "invocation_scope", nullable = false, length = 30)
    private ProviderInvocationScope invocationScope;

    @Column(nullable = false, length = 80)
    private String capability;

    @Column(name = "provider_code", nullable = false, length = 80)
    private String providerCode;

    @Column(name = "deployment_code", length = 120)
    private String deploymentCode;

    @Column(name = "model_alias", nullable = false, length = 120)
    private String modelAlias;

    @Column(name = "provider_model", length = 160)
    private String providerModel;

    @Column(name = "provider_request_id", length = 240)
    private String providerRequestId;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "input_units")
    private Long inputUnits;

    @Column(name = "output_units")
    private Long outputUnits;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "provider_state_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> providerState = Map.of();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "downloaded_at")
    private Instant downloadedAt;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ProviderInvocationEntity() {
    }

    public ProviderInvocationEntity(
            UUID id,
            UUID tenantId,
            UUID runId,
            String capability,
            String providerCode,
            String deploymentCode,
            String modelAlias,
            String requestFingerprint,
            Instant startedAt
    ) {
        this(
                id,
                tenantId,
                runId,
                ProviderInvocationScope.TASK_RUN,
                capability,
                providerCode,
                deploymentCode,
                modelAlias,
                requestFingerprint,
                startedAt
        );
    }

    public ProviderInvocationEntity(
            UUID id,
            UUID tenantId,
            UUID runId,
            ProviderInvocationScope invocationScope,
            String capability,
            String providerCode,
            String deploymentCode,
            String modelAlias,
            String requestFingerprint,
            Instant startedAt
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.runId = runId;
        this.invocationScope = invocationScope;
        this.capability = capability;
        this.providerCode = providerCode;
        this.deploymentCode = deploymentCode;
        this.modelAlias = modelAlias;
        this.status = "RUNNING";
        this.requestFingerprint = requestFingerprint;
        this.startedAt = startedAt;
    }

    public void succeed(
            String providerModel,
            String providerRequestId,
            Integer inputUnits,
            Integer outputUnits,
            Instant finishedAt
    ) {
        this.status = "SUCCEEDED";
        this.providerModel = providerModel;
        this.providerRequestId = providerRequestId;
        this.inputUnits = inputUnits == null ? null : inputUnits.longValue();
        this.outputUnits = outputUnits == null ? null : outputUnits.longValue();
        this.finishedAt = finishedAt;
    }

    public void markSubmitting() {
        this.status = "SUBMITTING";
        this.errorCode = null;
        this.finishedAt = null;
    }

    public void submitted(
            String providerModel,
            String providerRequestId,
            Map<String, Object> providerState,
            Instant submittedAt
    ) {
        this.status = "GENERATING";
        this.providerModel = providerModel;
        this.providerRequestId = providerRequestId;
        this.providerState = providerState == null ? Map.of() : Map.copyOf(providerState);
        this.submittedAt = submittedAt;
        this.lastPolledAt = submittedAt;
        this.errorCode = null;
        this.finishedAt = null;
    }

    public void phase(String phase, Map<String, Object> providerState, Instant now) {
        this.status = phase;
        if (providerState != null && !providerState.isEmpty()) {
            this.providerState = Map.copyOf(providerState);
        }
        if ("GENERATING".equals(phase)) this.lastPolledAt = now;
        if ("DOWNLOADING".equals(phase)) this.downloadedAt = now;
        this.errorCode = null;
        this.finishedAt = null;
    }

    public void interrupted(String errorCode, Instant now) {
        this.errorCode = errorCode;
        this.lastPolledAt = now;
        this.finishedAt = null;
    }

    public void submissionUnknown(String errorCode, Instant finishedAt) {
        this.status = "SUBMISSION_UNKNOWN";
        this.errorCode = errorCode;
        this.finishedAt = finishedAt;
    }

    public void fail(String errorCode, Instant finishedAt) {
        this.status = "FAILED";
        this.errorCode = errorCode;
        this.finishedAt = finishedAt;
    }

    public String getDeploymentCode() {
        return deploymentCode;
    }

    public UUID getRunId() {
        return runId;
    }

    public ProviderInvocationScope getInvocationScope() {
        return invocationScope;
    }

    public String getCapability() {
        return capability;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public String getProviderModel() {
        return providerModel;
    }

    public String getStatus() {
        return status;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Map<String, Object> getProviderState() {
        return providerState == null ? Map.of() : Map.copyOf(providerState);
    }
}
