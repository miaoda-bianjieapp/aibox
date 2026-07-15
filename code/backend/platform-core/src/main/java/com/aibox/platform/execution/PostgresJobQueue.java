package com.aibox.platform.execution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PostgresJobQueue {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PostgresJobQueue(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    public Optional<JobLease> claimNext(String workerId, Duration leaseDuration) {
        Instant now = clock.instant();
        Timestamp nowTimestamp = Timestamp.from(now);
        List<JobLease> leases = jdbcTemplate.query("""
                with candidate as (
                    select id
                    from job
                    where type = 'FEATURE_EXECUTION'
                      and status = 'QUEUED'
                      and available_at <= ?
                    order by available_at, created_at
                    for update skip locked
                    limit 1
                )
                update job j
                set status = 'RUNNING',
                    locked_by = ?,
                    locked_until = ?,
                    attempts = attempts + 1,
                    updated_at = ?
                from candidate c
                where j.id = c.id
                returning j.id, j.run_id, j.attempts, j.max_attempts
                """,
                this::mapLease,
                nowTimestamp,
                workerId,
                Timestamp.from(now.plus(leaseDuration)),
                nowTimestamp
        );
        return leases.stream().findFirst();
    }

    @Transactional
    public List<UUID> recoverExpiredLeases() {
        Instant now = clock.instant();
        Timestamp nowTimestamp = Timestamp.from(now);
        List<UUID> exhaustedRunIds = jdbcTemplate.query("""
                update job
                set status = 'FAILED', locked_by = null, locked_until = null,
                    last_error = 'Worker lease expired after maximum attempts', updated_at = ?
                where status = 'RUNNING' and locked_until < ? and attempts >= max_attempts
                returning run_id
                """,
                (resultSet, rowNumber) -> resultSet.getObject("run_id", UUID.class),
                nowTimestamp,
                nowTimestamp
        );
        jdbcTemplate.update("""
                update job
                set status = 'QUEUED', available_at = ?, locked_by = null, locked_until = null,
                    last_error = 'Worker lease expired', updated_at = ?
                where status = 'RUNNING' and locked_until < ? and attempts < max_attempts
                """, nowTimestamp, nowTimestamp, nowTimestamp);
        return exhaustedRunIds;
    }

    public void markSucceeded(UUID jobId) {
        jdbcTemplate.update("""
                update job
                set status = 'SUCCEEDED', locked_by = null, locked_until = null, updated_at = ?
                where id = ?
                """, Timestamp.from(clock.instant()), jobId);
    }

    public void markFailed(JobLease lease, String error, boolean retryable) {
        Instant now = clock.instant();
        Timestamp nowTimestamp = Timestamp.from(now);
        if (retryable && lease.attempts() < lease.maxAttempts()) {
            long delaySeconds = Math.min(30L, 1L << lease.attempts());
            jdbcTemplate.update("""
                    update job
                    set status = 'QUEUED', available_at = ?, locked_by = null, locked_until = null,
                        last_error = ?, updated_at = ?
                    where id = ?
                    """,
                    Timestamp.from(now.plusSeconds(delaySeconds)),
                    abbreviate(error),
                    nowTimestamp,
                    lease.jobId()
            );
            return;
        }
        jdbcTemplate.update("""
                update job
                set status = 'FAILED', locked_by = null, locked_until = null,
                    last_error = ?, updated_at = ?
                where id = ?
                """, abbreviate(error), nowTimestamp, lease.jobId());
    }

    private JobLease mapLease(ResultSet resultSet, int rowNumber) throws SQLException {
        return new JobLease(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getInt("attempts"),
                resultSet.getInt("max_attempts")
        );
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "Unknown job error";
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    public record JobLease(UUID jobId, UUID runId, int attempts, int maxAttempts) {
    }
}
