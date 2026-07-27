package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;

public record DocumentQuestionResponse(
        String answerMarkdown,
        List<DocumentCitation> citations,
        List<String> warnings,
        String provider,
        String model,
        String providerRequestId,
        Integer inputTokens,
        Integer outputTokens,
        Map<String, Object> metadata
) {
    public DocumentQuestionResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
