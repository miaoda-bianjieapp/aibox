package com.aibox.platform.execution;

import com.aibox.platform.common.ConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public Optional<UUID> reserveOrResolve(
            UUID tenantId,
            String scope,
            String key,
            String canonicalRequest,
            UUID proposedResourceId
    ) {
        String requestHash = sha256(canonicalRequest);
        int inserted = jdbcTemplate.update("""
                insert into idempotency_record (
                    id, tenant_id, scope, idempotency_key, request_hash,
                    resource_type, resource_id, created_at
                ) values (?, ?, ?, ?, ?, 'TASK_RUN', ?, ?)
                on conflict (tenant_id, scope, idempotency_key) do nothing
                """,
                UUID.randomUUID(),
                tenantId,
                scope,
                key,
                requestHash,
                proposedResourceId,
                Timestamp.from(clock.instant())
        );
        if (inserted == 1) {
            return Optional.empty();
        }

        ExistingRecord existing = jdbcTemplate.queryForObject("""
                select request_hash, resource_id
                from idempotency_record
                where tenant_id = ? and scope = ? and idempotency_key = ?
                """, this::mapExisting, tenantId, scope, key);
        if (existing == null) {
            throw new IllegalStateException("Idempotency record disappeared after conflict");
        }
        if (!existing.requestHash().equals(requestHash)) {
            throw new ConflictException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "The same Idempotency-Key was used with a different request"
            );
        }
        return Optional.of(existing.resourceId());
    }

    private ExistingRecord mapExisting(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExistingRecord(
                resultSet.getString("request_hash"),
                resultSet.getObject("resource_id", UUID.class)
        );
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ExistingRecord(String requestHash, UUID resourceId) {
    }
}
