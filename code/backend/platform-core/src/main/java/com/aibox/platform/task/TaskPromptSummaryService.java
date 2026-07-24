package com.aibox.platform.task;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public final class TaskPromptSummaryService {

    private static final int MAX_SNIPPET_LENGTH = 80;

    private static final Map<String, List<String>> NATURAL_LANGUAGE_FIELDS = Map.of(
            "writing.draft", List.of("topic"),
            "writing.outline_ideas", List.of("articleTitle", "thesis"),
            "writing.rewrite_polish", List.of(
                    "sourceText", "rewriteRequirements", "polishRequirements"
            ),
            "writing.translate", List.of("sourceText"),
            "image.generate", List.of("prompt"),
            "image.local_edit", List.of("instruction"),
            "image.background_edit", List.of("backgroundDescription")
    );

    public String extractText(String featureCode, Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        List<String> fields = NATURAL_LANGUAGE_FIELDS.getOrDefault(
                featureCode,
                List.of("prompt", "instruction", "sourceText")
        );
        return fields.stream()
                .map(parameters::get)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(TaskPromptSummaryService::normalize)
                .filter(value -> !value.isEmpty())
                .reduce((left, right) -> left + " · " + right)
                .orElse("");
    }

    public String snippet(String featureCode, Map<String, Object> parameters) {
        return snippet(extractText(featureCode, parameters));
    }

    String snippet(String text) {
        String normalizedText = normalize(text);
        if (normalizedText.isEmpty()) {
            return null;
        }
        return truncate(normalizedText);
    }

    private static String truncate(String value) {
        if (value.length() <= MAX_SNIPPET_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_SNIPPET_LENGTH - 3) + "...";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
