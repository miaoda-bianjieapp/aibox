package com.aibox.platform.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AssetBlobRepository extends JpaRepository<AssetBlobEntity, UUID> {

    Optional<AssetBlobEntity> findByTenantIdAndUserIdAndSha256AndSizeBytesAndStatus(
            UUID tenantId,
            UUID userId,
            String sha256,
            long sizeBytes,
            String status
    );
}
