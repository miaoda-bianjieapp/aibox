package com.aibox.api;

import com.aibox.platform.task.TaskApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskApplicationService taskService;

    public TaskController(TaskApplicationService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskApplicationService.TaskView create(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.createTask(request.projectId(), request.featureCode(), request.title());
    }

    @GetMapping
    public List<TaskApplicationService.TaskView> list() {
        return taskService.listTasks();
    }

    @GetMapping("/{taskId}")
    public TaskApplicationService.TaskDetailView get(@PathVariable UUID taskId) {
        return taskService.getTask(taskId);
    }

    public record CreateTaskRequest(
            UUID projectId,
            @NotBlank @Size(max = 120) String featureCode,
            @NotBlank @Size(max = 240) String title
    ) {
    }
}

