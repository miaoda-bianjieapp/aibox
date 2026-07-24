package com.aibox.platform.task;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPromptSummaryServiceTest {

    private final TaskPromptSummaryService service = new TaskPromptSummaryService();

    @Test
    void extractsOnlyNaturalLanguageFields() {
        String text = service.extractText(
                "image.generate",
                Map.of(
                        "prompt", "一只在雨夜奔跑的黑猫",
                        "aspectRatio", "16:9",
                        "referenceImages", "asset-id"
                )
        );

        assertThat(text).isEqualTo("一只在雨夜奔跑的黑猫");
    }

    @Test
    void joinsNaturalLanguageFieldsInFeatureOrder() {
        String text = service.extractText(
                "writing.outline_ideas",
                Map.of(
                        "thesis", "解释复杂问题",
                        "articleTitle", "给新人的产品入门"
                )
        );

        assertThat(text).isEqualTo("给新人的产品入门 · 解释复杂问题");
    }

    @Test
    void truncatesPromptFromTheBeginning() {
        String text = "开头内容".repeat(30);

        String snippet = service.snippet(text);

        assertThat(snippet).startsWith("开头内容");
        assertThat(snippet).endsWith("...");
    }
}
