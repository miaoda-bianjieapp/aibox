package com.aibox.platform.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProviderInvocationRepository extends JpaRepository<ProviderInvocationEntity, UUID> {
}

