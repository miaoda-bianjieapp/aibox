package com.aibox.api;

import com.aibox.platform.task.TaskApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1")
public class RunController {

    private final TaskApplicationService taskService;
    private final SseRunEventPublisher eventPublisher;

    public RunController(TaskApplicationService taskService, SseRunEventPublisher eventPublisher) {
        this.taskService = taskService;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping("/tasks/{taskId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskApplicationService.RunView create(
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @Valid @RequestBody CreateRunRequest request
    ) {
        return taskService.createRun(
                taskId, request.parameters(), request.inputAssetIds(), request.baseArtifactId(),
                request.selectedModelCode(), idempotencyKey
        );
    }

    @GetMapping("/runs/{runId}")
    public TaskApplicationService.RunDetailView get(@PathVariable UUID runId) {
        return taskService.getRun(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public TaskApplicationService.RunView cancel(@PathVariable UUID runId) {
        return taskService.cancelRun(runId);
    }

    @PostMapping("/runs/{runId}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskApplicationService.RunView retry(
            @PathVariable UUID runId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey
    ) {
        return taskService.retryRun(runId, idempotencyKey);
    }

    @GetMapping("/runs/{runId}/events")
    public SseEmitter events(@PathVariable UUID runId) {
        TaskApplicationService.RunDetailView current = taskService.getRun(runId);
        return eventPublisher.subscribe(runId, current.run().status().name());
    }

    public record CreateRunRequest(
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            UUID baseArtifactId,
            @Size(max = 120) String selectedModelCode
    ) {
        public CreateRunRequest {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            inputAssetIds = inputAssetIds == null ? List.of() : List.copyOf(inputAssetIds);
        }
    }
}
