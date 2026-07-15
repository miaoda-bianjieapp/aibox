package com.aibox.platform.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeatureModelPolicyRepository extends JpaRepository<FeatureModelPolicyEntity, UUID> {
    Optional<FeatureModelPolicyEntity> findFirstByFeatureCodeOrderByCapabilityAsc(String featureCode);
    List<FeatureModelPolicyEntity> findByFeatureCodeOrderByCapabilityAsc(String featureCode);
}

