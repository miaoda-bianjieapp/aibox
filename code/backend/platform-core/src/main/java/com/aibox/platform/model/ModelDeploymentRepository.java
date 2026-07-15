package com.aibox.platform.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelDeploymentRepository extends JpaRepository<ModelDeploymentEntity, UUID> {
    Optional<ModelDeploymentEntity> findByCodeAndEnabledTrue(String code);
    List<ModelDeploymentEntity> findByCapabilityAndEnabledTrueOrderByDisplayNameAsc(String capability);
}

