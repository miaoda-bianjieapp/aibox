package com.aibox.platform.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {

    List<TaskEntity> findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID tenantId, UUID userId);

    Optional<TaskEntity> findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(UUID id, UUID tenantId, UUID userId);

    long countByTenantIdAndUserIdAndDeletedAtIsNull(UUID tenantId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from TaskEntity task where task.id = :id and task.tenantId = :tenantId "
            + "and task.userId = :userId and task.deletedAt is null")
    Optional<TaskEntity> findOwnedForUpdate(UUID id, UUID tenantId, UUID userId);
}
