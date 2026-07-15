package com.aibox.platform.project;

import com.aibox.platform.common.NotFoundException;
import com.aibox.platform.identity.ActorContext;
import com.aibox.platform.identity.ActorContextProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ActorContextProvider actorContextProvider;
    private final Clock clock;

    public ProjectService(
            ProjectRepository projectRepository,
            ActorContextProvider actorContextProvider,
            Clock clock
    ) {
        this.projectRepository = projectRepository;
        this.actorContextProvider = actorContextProvider;
        this.clock = clock;
    }

    @Transactional
    public ProjectView create(String name, String description) {
        ActorContext actor = actorContextProvider.current();
        Instant now = clock.instant();
        ProjectEntity project = new ProjectEntity(
                UUID.randomUUID(),
                actor.tenantId(),
                actor.userId(),
                name.trim(),
                description == null ? "" : description.trim(),
                now
        );
        return toView(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ProjectView> list() {
        ActorContext actor = actorContextProvider.current();
        return projectRepository
                .findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(actor.tenantId(), actor.userId())
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectEntity requireOwned(UUID id) {
        ActorContext actor = actorContextProvider.current();
        return projectRepository.findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(
                        id,
                        actor.tenantId(),
                        actor.userId()
                )
                .orElseThrow(() -> new NotFoundException("project", id));
    }

    private ProjectView toView(ProjectEntity project) {
        return new ProjectView(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    public record ProjectView(
            UUID id,
            String name,
            String description,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}

