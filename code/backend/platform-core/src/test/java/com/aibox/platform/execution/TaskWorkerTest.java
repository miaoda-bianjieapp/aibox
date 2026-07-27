package com.aibox.platform.execution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskWorkerTest {

    @Test
    void marksTheJobCancelledWhenExecutionStopsAfterRunCancellation() {
        PostgresJobQueue jobQueue = mock(PostgresJobQueue.class);
        RunExecutionCoordinator coordinator = mock(RunExecutionCoordinator.class);
        RunExecutionStateService stateService = mock(RunExecutionStateService.class);
        UUID jobId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PostgresJobQueue.JobLease lease =
                new PostgresJobQueue.JobLease(jobId, runId, 1, 3);
        when(jobQueue.recoverExpiredLeases()).thenReturn(List.of());
        when(jobQueue.claimNext(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(Optional.of(lease));
        doThrow(new RunCancelledException(runId))
                .when(coordinator)
                .execute(runId);
        TaskWorker worker = new TaskWorker(jobQueue, coordinator, stateService);

        worker.poll();

        verify(jobQueue).markCancelled(jobId);
    }
}
