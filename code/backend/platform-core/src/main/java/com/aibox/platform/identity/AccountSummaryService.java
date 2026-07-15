package com.aibox.platform.identity;

import com.aibox.platform.artifact.ArtifactRepository;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.project.ProjectRepository;
import com.aibox.platform.task.TaskRepository;
import com.aibox.platform.task.TaskRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountSummaryService {

    private final ActorContextProvider actorContextProvider;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final TaskRunRepository runRepository;
    private final ArtifactRepository artifactRepository;
    private final AssetService assetService;

    public AccountSummaryService(
            ActorContextProvider actorContextProvider,
            ProjectRepository projectRepository,
            TaskRepository taskRepository,
            TaskRunRepository runRepository,
            ArtifactRepository artifactRepository,
            AssetService assetService
    ) {
        this.actorContextProvider = actorContextProvider;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.assetService = assetService;
    }

    @Transactional(readOnly = true)
    public AccountSummary get() {
        ActorContext actor = actorContextProvider.current();
        return new AccountSummary(
                "DEVELOPMENT",
                "本地开发账户",
                projectRepository.countByTenantIdAndUserIdAndDeletedAtIsNull(actor.tenantId(), actor.userId()),
                taskRepository.countByTenantIdAndUserIdAndDeletedAtIsNull(actor.tenantId(), actor.userId()),
                runRepository.countByTenantIdAndUserId(actor.tenantId(), actor.userId()),
                artifactRepository.countByTenantIdAndUserId(actor.tenantId(), actor.userId()),
                assetService.countOwned(),
                assetService.totalOwnedBytes()
        );
    }

    public record AccountSummary(
            String accountMode,
            String displayName,
            long projectCount,
            long taskCount,
            long runCount,
            long artifactCount,
            long assetCount,
            long assetBytes
    ) {
    }
}

