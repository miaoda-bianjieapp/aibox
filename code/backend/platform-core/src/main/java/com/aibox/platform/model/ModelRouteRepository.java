package com.aibox.platform.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModelRouteRepository extends JpaRepository<ModelRouteEntity, UUID> {
    List<ModelRouteEntity> findByModelAliasAndCapabilityAndEnabledTrueOrderByPriorityAsc(
            String modelAlias,
            String capability
    );
}

