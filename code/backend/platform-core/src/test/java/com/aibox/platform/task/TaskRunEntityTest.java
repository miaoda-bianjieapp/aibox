package com.aibox.platform.task;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskRunEntityTest {

    @Test
    void followsTheSuccessfulStatePath() {
        TaskRunEntity run = newRun();
        Instant startedAt = Instant.parse("2026-07-14T01:00:00Z");
        Instant finishedAt = Instant.parse("2026-07-14T01:01:00Z");

        run.markRunning(startedAt);
        run.markSucceeded(finishedAt);

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getStartedAt()).isEqualTo(startedAt);
        assertThat(run.getFinishedAt()).isEqualTo(finishedAt);
    }

    @Test
    void rejectsAnInvalidSuccessTransition() {
        TaskRunEntity run = newRun();

        assertThatThrownBy(() -> run.markSucceeded(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected run status RUNNING");
    }

    @Test
    void cancellationIsTerminal() {
        TaskRunEntity run = newRun();
        run.cancel(Instant.parse("2026-07-14T01:00:00Z"));
        run.markFailed("LATE_ERROR", "late provider response", Instant.parse("2026-07-14T01:01:00Z"));

        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    private static TaskRunEntity newRun() {
        return new TaskRunEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "writing.draft",
                1,
                Map.of(),
                List.of(),
                Instant.parse("2026-07-14T00:00:00Z")
        );
    }
}
