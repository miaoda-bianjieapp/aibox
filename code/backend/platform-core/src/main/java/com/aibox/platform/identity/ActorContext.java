package com.aibox.platform.identity;

import java.util.UUID;

public record ActorContext(UUID tenantId, UUID userId) {
}

