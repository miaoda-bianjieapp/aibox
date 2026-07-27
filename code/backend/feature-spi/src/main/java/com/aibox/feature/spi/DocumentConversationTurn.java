package com.aibox.feature.spi;

public record DocumentConversationTurn(
        String question,
        String answer
) {
    public DocumentConversationTurn {
        question = question == null ? "" : question.trim();
        answer = answer == null ? "" : answer.trim();
    }
}
