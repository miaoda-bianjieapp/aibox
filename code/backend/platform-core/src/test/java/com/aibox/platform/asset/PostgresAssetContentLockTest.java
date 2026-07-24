package com.aibox.platform.asset;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PostgresAssetContentLockTest {

    @Test
    void acquiresTransactionScopedAdvisoryLockBeforeRunningTheOperation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PostgresAssetContentLock lock = new PostgresAssetContentLock(jdbcTemplate);
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String sha256 = "abc123";
        long sizeBytes = 42;
        String identity = tenantId + ":" + userId + ":" + sha256 + ":" + sizeBytes;

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(lock.execute(
                    tenantId,
                    userId,
                    sha256,
                    sizeBytes,
                    () -> "completed"
            )).isEqualTo("completed");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verify(jdbcTemplate).query(
                contains("pg_advisory_xact_lock"),
                any(RowCallbackHandler.class),
                eq(identity)
        );
    }
}
