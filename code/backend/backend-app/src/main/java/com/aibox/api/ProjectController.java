package com.aibox.api;

import com.aibox.platform.project.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectService.ProjectView create(@Valid @RequestBody CreateProjectRequest request) {
        return projectService.create(request.name(), request.description());
    }

    @GetMapping
    public List<ProjectService.ProjectView> list() {
        return projectService.list();
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1_000) String description
    ) {
    }
}

