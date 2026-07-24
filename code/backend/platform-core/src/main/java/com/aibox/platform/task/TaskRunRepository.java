package com.aibox.platform.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRunRepository extends JpaRepository<TaskRunEntity, UUID> {

    Optional<TaskRunEntity> findByIdAndTenantIdAndUserId(UUID id, UUID tenantId, UUID userId);

    List<TaskRunEntity> findByTaskIdAndTenantIdAndUserIdOrderByRunNumberDesc(UUID taskId, UUID tenantId, UUID userId);

    @Query("""
            select run
            from TaskRunEntity run
            where run.taskId in :taskIds
              and run.tenantId = :tenantId
              and run.userId = :userId
              and run.runNumber = (
                  select min(firstRun.runNumber)
                  from TaskRunEntity firstRun
                  where firstRun.taskId = run.taskId
                    and firstRun.tenantId = run.tenantId
                    and firstRun.userId = run.userId
              )
            order by run.taskId
            """)
    List<TaskRunEntity> findFirstRunsByTaskIds(
            @Param("taskIds") List<UUID> taskIds,
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId
    );

    long countByTenantIdAndUserId(UUID tenantId, UUID userId);

    @Query("select coalesce(max(run.runNumber), 0) from TaskRunEntity run where run.taskId = :taskId")
    int findMaxRunNumber(UUID taskId);
}
