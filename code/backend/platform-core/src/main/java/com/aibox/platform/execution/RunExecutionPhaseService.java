package com.aibox.platform.execution;

import com.aibox.platform.task.TaskRunEntity;
import com.aibox.platform.task.TaskRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RunExecutionPhaseService {

    private final TaskRunRepository runRepository;
    private final RunEventPublisher eventPublisher;

    public RunExecutionPhaseService(
            TaskRunRepository runRepository,
            RunEventPublisher eventPublisher
    ) {
        this.runRepository = runRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(UUID runId, String phase) {
        if (runId == null || phase == null || phase.isBlank()) return;
        TaskRunEntity run = runRepository.findById(runId).orElse(null);
        if (run == null || run.getStatus().isTerminal()) return;
        run.updateExecutionPhase(phase);
        runRepository.saveAndFlush(run);
        eventPublisher.publish(runId, "phase", java.util.Map.of("phase", run.getExecutionPhase()));
    }
}
