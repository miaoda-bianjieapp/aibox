package com.aibox.platform.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<AssetEntity, UUID> {

    List<AssetEntity> findByTenantIdAndUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID tenantId, UUID userId);

    Optional<AssetEntity> findByIdAndTenantIdAndUserIdAndDeletedAtIsNull(UUID id, UUID tenantId, UUID userId);

    long countByTenantIdAndUserIdAndDeletedAtIsNull(UUID tenantId, UUID userId);

    @Query("select coalesce(sum(asset.sizeBytes), 0) from AssetEntity asset "
            + "where asset.tenantId = :tenantId and asset.userId = :userId and asset.deletedAt is null")
    long sumSizeByOwner(UUID tenantId, UUID userId);
}

