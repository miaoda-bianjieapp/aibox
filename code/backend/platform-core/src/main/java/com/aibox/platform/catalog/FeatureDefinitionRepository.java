package com.aibox.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeatureDefinitionRepository extends JpaRepository<FeatureDefinitionEntity, UUID> {

    List<FeatureDefinitionEntity> findByWorkspaceIdInAndStatusInOrderBySortOrderAsc(
            Collection<UUID> workspaceIds,
            Collection<FeatureStatus> statuses
    );

    Optional<FeatureDefinitionEntity> findByCode(String code);
}

