package com.aibox.api;

import com.aibox.platform.artifact.ArtifactService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ArtifactController {

    private final ArtifactService artifactService;

    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @GetMapping("/tasks/{taskId}/artifacts")
    public List<ArtifactService.ArtifactView> listByTask(@PathVariable UUID taskId) {
        return artifactService.listByTask(taskId);
    }

    @GetMapping("/artifacts/{artifactId}")
    public ArtifactService.ArtifactView get(@PathVariable UUID artifactId) {
        return artifactService.get(artifactId);
    }
}

