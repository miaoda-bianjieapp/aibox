package com.aibox.platform.asset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.function.Supplier;

@Component
public final class PostgresAssetContentLock implements AssetContentLock {

    private final JdbcTemplate jdbcTemplate;

    public PostgresAssetContentLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public <T> T execute(
            UUID tenantId,
            UUID userId,
            String sha256,
            long sizeBytes,
            Supplier<T> operation
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Asset content locking requires an active transaction");
        }
        String identity = tenantId + ":" + userId + ":" + sha256 + ":" + sizeBytes;
        jdbcTemplate.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> {
                },
                identity
        );
        return operation.get();
    }
}
