package com.aibox.platform.execution;

import com.aibox.platform.artifact.ArtifactService;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.task.RunStatus;
import com.aibox.platform.task.TaskRunEntity;
import com.aibox.platform.task.TaskRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunExecutionStateServiceTest {

    @Test
    void cancelledRunStopsRecoveredWorkerInsteadOfLookingSuccessfullyHandled() {
        TaskRunRepository runRepository = mock(TaskRunRepository.class);
        TaskRunEntity run = mock(TaskRunEntity.class);
        UUID runId = UUID.randomUUID();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(run.getStatus()).thenReturn(RunStatus.CANCELLED);

        RunExecutionStateService service = new RunExecutionStateService(
                runRepository,
                mock(ArtifactService.class),
                mock(AssetService.class),
                mock(OutboxService.class),
                mock(RunEventPublisher.class),
                mock(RunOutputService.class),
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> service.start(runId))
                .isInstanceOf(RunCancelledException.class)
                .hasMessageContaining(runId.toString());
    }
}
