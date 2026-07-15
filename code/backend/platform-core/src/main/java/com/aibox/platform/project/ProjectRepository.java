package com.aibox.platform.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<ProjectEntity, UUID> {

    List<ProjectEntity> findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID tenantId, UUID userId);

    Optional<ProjectEntity> findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(UUID id, UUID tenantId, UUID userId);

    long countByTenantIdAndUserIdAndDeletedAtIsNull(UUID tenantId, UUID userId);
}
