package com.aibox.api;

import com.aibox.platform.task.TaskApplicationService;
import com.aibox.platform.task.TaskAssetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskApplicationService taskService;
    private final TaskAssetService taskAssetService;

    public TaskController(
            TaskApplicationService taskService,
            TaskAssetService taskAssetService
    ) {
        this.taskService = taskService;
        this.taskAssetService = taskAssetService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskApplicationService.TaskView create(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.createTask(request.projectId(), request.featureCode(), request.title());
    }

    @GetMapping
    public List<TaskApplicationService.TaskView> list(
            @RequestParam(required = false) String workspaceCode,
            @RequestParam(required = false) String keyword
    ) {
        return taskService.listTasks(workspaceCode, keyword);
    }

    @GetMapping("/{taskId}")
    public TaskApplicationService.TaskDetailView get(@PathVariable UUID taskId) {
        return taskService.getTask(taskId);
    }

    @GetMapping("/{taskId}/assets")
    public List<TaskAssetService.TaskAssetView> listAssets(@PathVariable UUID taskId) {
        return taskAssetService.list(taskId);
    }

    @PostMapping("/{taskId}/assets")
    public List<TaskAssetService.TaskAssetView> addAssets(
            @PathVariable UUID taskId,
            @RequestBody TaskAssetRequest request
    ) {
        return taskAssetService.add(taskId, request.assetIds(), request.role());
    }

    @DeleteMapping("/{taskId}/assets/{assetId}")
    public List<TaskAssetService.TaskAssetView> removeAsset(
            @PathVariable UUID taskId,
            @PathVariable UUID assetId,
            @RequestParam(required = false) String role
    ) {
        return taskAssetService.remove(taskId, assetId, role);
    }

    public record CreateTaskRequest(
            UUID projectId,
            @NotBlank @Size(max = 120) String featureCode,
            @NotBlank @Size(max = 240) String title
    ) {
    }

    public record TaskAssetRequest(
            List<UUID> assetIds,
            String role
    ) {
        public TaskAssetRequest {
            assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
        }
    }
}

