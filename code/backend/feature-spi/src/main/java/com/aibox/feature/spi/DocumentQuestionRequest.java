package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DocumentQuestionRequest(
        UUID tenantId,
        UUID userId,
        UUID runId,
        UUID taskId,
        String question,
        List<UUID> inputAssetIds,
        List<DocumentConversationTurn> conversation,
        String textModelAlias,
        String textDeploymentCode,
        String visionModelAlias,
        String visionDeploymentCode,
        Integer maxOutputTokens,
        Map<String, Object> metadata
) {
    public DocumentQuestionRequest {
        inputAssetIds = inputAssetIds == null ? List.of() : List.copyOf(inputAssetIds);
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
