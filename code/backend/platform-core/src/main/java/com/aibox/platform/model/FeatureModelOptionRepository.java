package com.aibox.platform.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeatureModelOptionRepository extends JpaRepository<FeatureModelOptionEntity, FeatureModelOptionId> {
    List<FeatureModelOptionEntity> findByPolicyIdAndEnabledTrueOrderBySortOrderAsc(java.util.UUID policyId);
}

