package com.aibox.api;

import com.aibox.platform.task.TaskApplicationService;
import com.aibox.platform.execution.RunOutputService;
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
    private final RunOutputService outputService;

    public RunController(
            TaskApplicationService taskService,
            SseRunEventPublisher eventPublisher,
            RunOutputService outputService
    ) {
        this.taskService = taskService;
        this.eventPublisher = eventPublisher;
        this.outputService = outputService;
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
                request.selectedModels(), request.selectedModelCode(), idempotencyKey
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
    public SseEmitter events(
            @PathVariable UUID runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId
    ) {
        taskService.getRun(runId);
        long replayAfter = parseEventId(lastEventId);
        return eventPublisher.subscribe(
                runId,
                replayAfter,
                () -> taskService.getRun(runId).run().status().name(),
                after -> outputService.getOwnedEventsAfter(runId, after)
        );
    }

    @GetMapping("/runs/{runId}/output")
    public List<RunOutputService.RunOutputStreamView> output(@PathVariable UUID runId) {
        return outputService.getOwnedStreams(runId);
    }

    private static long parseEventId(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public record CreateRunRequest(
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            UUID baseArtifactId,
            Map<String, String> selectedModels,
            @Size(max = 120) String selectedModelCode
    ) {
        public CreateRunRequest {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            inputAssetIds = inputAssetIds == null ? List.of() : List.copyOf(inputAssetIds);
            selectedModels = selectedModels == null ? Map.of() : Map.copyOf(selectedModels);
        }
    }
}
