package com.aibox.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface JobRepository extends JpaRepository<JobEntity, UUID> {

    @Modifying
    @Query(value = "update job set status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP "
            + "where run_id = :runId and status = 'QUEUED'", nativeQuery = true)
    int cancelQueuedByRunId(UUID runId);
}
