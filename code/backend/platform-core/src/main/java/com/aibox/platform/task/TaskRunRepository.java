package com.aibox.platform.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRunRepository extends JpaRepository<TaskRunEntity, UUID> {

    Optional<TaskRunEntity> findByIdAndTenantIdAndUserId(UUID id, UUID tenantId, UUID userId);

    List<TaskRunEntity> findByTaskIdAndTenantIdAndUserIdOrderByRunNumberDesc(UUID taskId, UUID tenantId, UUID userId);

    long countByTenantIdAndUserId(UUID tenantId, UUID userId);

    @Query("select coalesce(max(run.runNumber), 0) from TaskRunEntity run where run.taskId = :taskId")
    int findMaxRunNumber(UUID taskId);
}
