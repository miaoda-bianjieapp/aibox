package com.aibox.api;

import com.aibox.platform.artifact.ArtifactExportService;
import com.aibox.platform.artifact.ArtifactService;
import com.aibox.platform.asset.AssetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ArtifactController {

    private final ArtifactService artifactService;
    private final ArtifactExportService artifactExportService;

    public ArtifactController(
            ArtifactService artifactService,
            ArtifactExportService artifactExportService
    ) {
        this.artifactService = artifactService;
        this.artifactExportService = artifactExportService;
    }

    @GetMapping("/tasks/{taskId}/artifacts")
    public List<ArtifactService.ArtifactView> listByTask(@PathVariable UUID taskId) {
        return artifactService.listByTask(taskId);
    }

    @GetMapping("/artifacts/{artifactId}")
    public ArtifactService.ArtifactView get(@PathVariable UUID artifactId) {
        return artifactService.get(artifactId);
    }

    @PostMapping("/artifacts/{artifactId}/exports")
    public AssetService.AssetView export(
            @PathVariable UUID artifactId,
            @Valid @RequestBody ExportArtifactRequest request
    ) {
        return artifactExportService.export(artifactId, request.type());
    }

    public record ExportArtifactRequest(
            @NotBlank @Size(max = 80) String type
    ) {
    }
}

