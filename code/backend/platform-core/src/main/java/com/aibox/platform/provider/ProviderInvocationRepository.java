package com.aibox.platform.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;

public interface ProviderInvocationRepository extends JpaRepository<ProviderInvocationEntity, UUID> {

    Optional<ProviderInvocationEntity>
    findFirstByRunIdAndInvocationScopeAndCapabilityAndDeploymentCodeAndRequestFingerprintAndProviderRequestIdIsNotNullOrderByStartedAtDesc(
            UUID runId,
            ProviderInvocationScope invocationScope,
            String capability,
            String deploymentCode,
            String requestFingerprint
    );
}

