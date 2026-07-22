package com.aibox.features.writing.draft;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureOutputEmitter;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.StreamingFeatureHandler;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public final class WritingDraftFeatureHandler implements StreamingFeatureHandler {

    public static final String FEATURE_CODE = "writing.draft";

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        String topic = stringParameter(context, "topic");
        if (topic.isBlank()) {
            throw new FeatureValidationException("topic", "topic is required");
        }
        if (topic.length() > 500) {
            throw new FeatureValidationException("topic", "topic must not exceed 500 characters");
        }
    }

    @Override
    public FeatureExecutionResult execute(
            FeatureExecutionContext context,
            ModelGateway modelGateway,
            FeatureOutputEmitter outputEmitter
    ) {
        String topic = stringParameter(context, "topic");
        String audience = defaultValue(stringParameter(context, "audience"), "general readers");
        String tone = defaultValue(stringParameter(context, "tone"), "clear and professional");
        String length = defaultValue(stringParameter(context, "length"), "medium");

        String requirements = "Topic: " + topic + "\n"
                + "Audience: " + audience + "\n"
                + "Tone: " + tone + "\n"
                + "Length: " + length;
        String previousText = context.baseArtifact() == null
                ? ""
                : String.valueOf(context.baseArtifact().content().getOrDefault("text", ""));
        String userPrompt = previousText.isBlank()
                ? "Write a complete draft with the following requirements:\n" + requirements
                : "Revise the existing draft according to the updated requirements. "
                        + "Return the complete revised draft, not a list of changes.\n\n"
                        + requirements + "\n\nExisting draft:\n" + previousText;

        outputEmitter.start("main", "markdown");
        TextGenerationResponse response = modelGateway.generateTextStream(new TextGenerationRequest(
                context.tenantId(),
                context.runId(),
                "text.default",
                context.selectedModelCode(ModelCapability.TEXT_GENERATION),
                "You are a professional Chinese writing assistant. Return a structured Markdown draft.",
                userPrompt,
                2_000,
                0.7,
                Map.of("featureCode", FEATURE_CODE, "runId", context.runId().toString())
        ), delta -> {
            outputEmitter.appendText("main", delta);
            return !outputEmitter.isCancelled();
        });

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", "markdown");
        content.put("text", response.text());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", response.provider());
        metadata.put("model", response.model());
        metadata.put("providerRequestId", response.providerRequestId());
        if (context.baseArtifact() != null) {
            metadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
            metadata.put("basedOnVersion", context.baseArtifact().versionNumber());
        }

        return FeatureExecutionResult.of(new ArtifactDraft(
                "rich_text",
                topic,
                "text/markdown",
                content,
                metadata
        ));
    }

    private static String stringParameter(FeatureExecutionContext context, String name) {
        Object value = context.parameters().get(name);
        return value == null ? "" : value.toString().trim();
    }

    private static String defaultValue(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }
}
