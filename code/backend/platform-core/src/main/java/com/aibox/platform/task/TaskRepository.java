package com.aibox.platform.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {

    List<TaskEntity> findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(UUID tenantId, UUID userId);

    @Query(value = """
            select task.*
            from task
            left join task_run first_run
              on first_run.task_id = task.id
             and first_run.tenant_id = :tenantId
             and first_run.user_id = :userId
             and first_run.run_number = 1
            where task.tenant_id = :tenantId
              and task.user_id = :userId
              and task.deleted_at is null
              and (
                  position(lower(:keyword) in lower(task.title)) > 0
                  or exists (
                      select 1
                      from jsonb_each_text(
                          coalesce(first_run.parameters_json, '{}'::jsonb)
                      ) as parameter(key, value)
                      where parameter.key in (
                          'prompt',
                          'instruction',
                          'backgroundDescription',
                          'topic',
                          'articleTitle',
                          'thesis',
                          'sourceText',
                          'rewriteRequirements',
                          'polishRequirements'
                      )
                        and position(lower(:keyword) in lower(parameter.value)) > 0
                  )
              )
            order by task.updated_at desc
            """, nativeQuery = true)
    List<TaskEntity> findOwnedByTitleOrPromptKeyword(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId,
            @Param("keyword") String keyword
    );

    @Query(value = """
            select task.*
            from task
            join feature_definition feature on feature.code = task.feature_code
            join workspace on workspace.id = feature.workspace_id
            where task.tenant_id = :tenantId
              and task.user_id = :userId
              and task.deleted_at is null
              and workspace.code = :workspaceCode
              and workspace.enabled = true
            order by task.updated_at desc
            """, nativeQuery = true)
    List<TaskEntity> findOwnedByWorkspace(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId,
            @Param("workspaceCode") String workspaceCode
    );

    @Query(value = """
            select task.*
            from task
            join feature_definition feature on feature.code = task.feature_code
            join workspace on workspace.id = feature.workspace_id
            left join task_run first_run
              on first_run.task_id = task.id
             and first_run.tenant_id = :tenantId
             and first_run.user_id = :userId
             and first_run.run_number = 1
            where task.tenant_id = :tenantId
              and task.user_id = :userId
              and task.deleted_at is null
              and workspace.code = :workspaceCode
              and workspace.enabled = true
              and (
                  position(lower(:keyword) in lower(task.title)) > 0
                  or exists (
                      select 1
                      from jsonb_each_text(
                          coalesce(first_run.parameters_json, '{}'::jsonb)
                      ) as parameter(key, value)
                      where parameter.key in (
                          'prompt',
                          'instruction',
                          'backgroundDescription',
                          'topic',
                          'articleTitle',
                          'thesis',
                          'sourceText',
                          'rewriteRequirements',
                          'polishRequirements'
                      )
                        and position(lower(:keyword) in lower(parameter.value)) > 0
                  )
              )
            order by task.updated_at desc
            """, nativeQuery = true)
    List<TaskEntity> findOwnedByWorkspaceAndTitleOrPromptKeyword(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId,
            @Param("workspaceCode") String workspaceCode,
            @Param("keyword") String keyword
    );

    Optional<TaskEntity> findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(UUID id, UUID tenantId, UUID userId);

    long countByTenantIdAndUserIdAndDeletedAtIsNull(UUID tenantId, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from TaskEntity task where task.id = :id and task.tenantId = :tenantId "
            + "and task.userId = :userId and task.deletedAt is null")
    Optional<TaskEntity> findOwnedForUpdate(UUID id, UUID tenantId, UUID userId);
}
