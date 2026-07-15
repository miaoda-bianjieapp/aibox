package com.aibox.platform.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, UUID> {

    List<WorkspaceEntity> findByEnabledTrueOrderBySortOrderAsc();
}

