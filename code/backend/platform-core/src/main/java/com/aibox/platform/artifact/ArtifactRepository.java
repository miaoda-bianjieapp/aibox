package com.aibox.platform.artifact;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArtifactRepository extends JpaRepository<ArtifactEntity, UUID> {

    List<ArtifactEntity> findByTaskIdAndTenantIdAndUserIdOrderByCreatedAtDesc(UUID taskId, UUID tenantId, UUID userId);

    List<ArtifactEntity> findByRunIdAndTenantIdAndUserIdOrderByCreatedAtAsc(UUID runId, UUID tenantId, UUID userId);

    Optional<ArtifactEntity> findByIdAndTenantIdAndUserId(UUID id, UUID tenantId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select artifact
            from ArtifactEntity artifact
            where artifact.id = :id
              and artifact.tenantId = :tenantId
              and artifact.userId = :userId
            """)
    Optional<ArtifactEntity> findOwnedForUpdate(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId
    );

    long countByTenantIdAndUserId(UUID tenantId, UUID userId);

    @Query("select coalesce(max(artifact.versionNumber), 0) from ArtifactEntity artifact "
            + "where artifact.taskId = :taskId")
    int findMaxVersionNumber(UUID taskId);
}
