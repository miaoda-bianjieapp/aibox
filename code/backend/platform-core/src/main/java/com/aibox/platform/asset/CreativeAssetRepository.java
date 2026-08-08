package com.aibox.platform.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreativeAssetRepository extends JpaRepository<CreativeAssetEntity, UUID> {

    List<CreativeAssetEntity> findByTenantIdAndUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(
            UUID tenantId,
            UUID userId
    );

    Optional<CreativeAssetEntity> findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(
            UUID id,
            UUID tenantId,
            UUID userId
    );
}
