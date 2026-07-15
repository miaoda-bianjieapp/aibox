package com.aibox.platform.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_invocation")
public class ProviderInvocationEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

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
        this.id = id;
        this.tenantId = tenantId;
        this.runId = runId;
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

    public void fail(String errorCode, Instant finishedAt) {
        this.status = "FAILED";
        this.errorCode = errorCode;
        this.finishedAt = finishedAt;
    }

    public String getDeploymentCode() {
        return deploymentCode;
    }
}
