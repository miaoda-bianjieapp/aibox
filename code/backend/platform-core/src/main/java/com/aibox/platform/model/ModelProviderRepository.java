package com.aibox.platform.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ModelProviderRepository extends JpaRepository<ModelProviderEntity, UUID> {
    Optional<ModelProviderEntity> findByCodeAndEnabledTrue(String code);
}

