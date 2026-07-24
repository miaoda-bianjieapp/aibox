package com.aibox.platform.asset;

import java.util.UUID;
import java.util.function.Supplier;

public interface AssetContentLock {

    <T> T execute(
            UUID tenantId,
            UUID userId,
            String sha256,
            long sizeBytes,
            Supplier<T> operation
    );
}
