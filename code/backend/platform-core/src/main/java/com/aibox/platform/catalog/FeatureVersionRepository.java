package com.aibox.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FeatureVersionRepository extends JpaRepository<FeatureVersionEntity, UUID> {

    Optional<FeatureVersionEntity> findByFeatureIdAndVersion(UUID featureId, int version);
}

