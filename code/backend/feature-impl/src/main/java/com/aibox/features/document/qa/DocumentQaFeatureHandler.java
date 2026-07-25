package com.aibox.features.document.qa;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.DocumentCitation;
import com.aibox.feature.spi.DocumentConversationTurn;
import com.aibox.feature.spi.DocumentQuestionRequest;
import com.aibox.feature.spi.DocumentQuestionResponse;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureOutputEmitter;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.StreamingFeatureHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public final class DocumentQaFeatureHandler implements StreamingFeatureHandler {

    public static final String FEATURE_CODE = "document.qa";
    private static final int MAX_FILES = 10;
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
    private static final int MAX_QUESTION_LENGTH = 4_000;
    private static final int MAX_CONTEXT_TURNS = 20;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".txt", ".md", ".csv", ".json"
    );
    private static final Map<String, Set<String>> ALLOWED_MEDIA_TYPES = Map.ofEntries(
            Map.entry(".pdf", Set.of("application/pdf", "application/octet-stream")),
            Map.entry(".doc", Set.of("application/msword", "application/octet-stream")),
            Map.entry(".docx", Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip",
                    "application/octet-stream"
            )),
            Map.entry(".xls", Set.of(
                    "application/vnd.ms-excel",
                    "application/octet-stream"
            )),
            Map.entry(".xlsx", Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/zip",
                    "application/octet-stream"
            )),
            Map.entry(".ppt", Set.of(
                    "application/vnd.ms-powerpoint",
                    "application/octet-stream"
            )),
            Map.entry(".pptx", Set.of(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/zip",
                    "application/octet-stream"
            )),
            Map.entry(".txt", Set.of("text/plain", "application/octet-stream")),
            Map.entry(".md", Set.of(
                    "text/markdown",
                    "text/x-markdown",
                    "text/plain",
                    "application/octet-stream"
            )),
            Map.entry(".csv", Set.of(
                    "text/csv",
                    "application/csv",
                    "text/plain",
                    "application/vnd.ms-excel",
                    "application/octet-stream"
            )),
            Map.entry(".json", Set.of(
                    "application/json",
                    "text/json",
                    "text/plain",
                    "application/octet-stream"
            ))
    );

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        String question = stringParameter(context, "question");
        if (question.isBlank()) {
            throw new FeatureValidationException("question", "question is required");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new FeatureValidationException(
                    "question", "question must not exceed " + MAX_QUESTION_LENGTH + " characters"
            );
        }
        if (!Boolean.TRUE.equals(context.parameters().getOrDefault("strictGrounding", true))) {
            throw new FeatureValidationException(
                    "strictGrounding", "document answers must use strict grounding"
            );
        }
        List<UUID> parameterIds = documentIds(context.parameters().get("documents"));
        if (parameterIds.isEmpty() || parameterIds.size() > MAX_FILES) {
            throw new FeatureValidationException("documents", "between 1 and 10 documents are required");
        }
        if (!List.copyOf(context.inputAssetIds()).equals(parameterIds)) {
            throw new FeatureValidationException(
                    "documents", "document parameters must match the run input assets"
            );
        }
        if (context.inputAssets().size() != parameterIds.size()) {
            throw new FeatureValidationException("documents", "all documents must be available");
        }
        long total = 0;
        for (InputAssetReference asset : context.inputAssets()) {
            String extension = extension(asset.fileName());
            if (!ALLOWED_EXTENSIONS.contains(extension)
                    || !supportsMediaType(extension, asset.mediaType())) {
                throw new FeatureValidationException(
                        "documents", "unsupported document type: " + asset.fileName()
                );
            }
            if (asset.sizeBytes() <= 0 || asset.sizeBytes() > MAX_FILE_BYTES) {
                throw new FeatureValidationException(
                        "documents", "document exceeds the 50 MB limit: " + asset.fileName()
                );
            }
            total += asset.sizeBytes();
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new FeatureValidationException("documents", "documents exceed the 200 MB total limit");
        }
        if (isBlank(context.selectedModelCode(ModelCapability.TEXT_GENERATION))) {
            throw new FeatureValidationException("selectedModels", "a text model is required");
        }
        if (isBlank(context.selectedModelCode(ModelCapability.VISION))) {
            throw new FeatureValidationException("selectedModels", "a vision model is required");
        }
    }

    @Override
    public FeatureExecutionResult execute(
            FeatureExecutionContext context,
            ModelGateway modelGateway,
            FeatureOutputEmitter outputEmitter
    ) {
        String question = stringParameter(context, "question");
        List<DocumentConversationTurn> previousTurns = previousTurns(context);
        outputEmitter.start("main", "markdown");
        DocumentQuestionResponse response = modelGateway.answerDocumentQuestion(
                new DocumentQuestionRequest(
                        context.tenantId(),
                        context.userId(),
                        context.runId(),
                        context.taskId(),
                        question,
                        context.inputAssetIds(),
                        previousTurns,
                        "document.qa.text",
                        context.selectedModelCode(ModelCapability.TEXT_GENERATION),
                        "document.qa.vision",
                        context.selectedModelCode(ModelCapability.VISION),
                        2_500,
                        Map.of("featureCode", FEATURE_CODE)
                ),
                delta -> {
                    outputEmitter.appendText("main", delta);
                    return !outputEmitter.isCancelled();
                }
        );
        outputEmitter.replaceText("main", response.answerMarkdown());

        List<Map<String, Object>> citations = response.citations().stream()
                .map(DocumentQaFeatureHandler::citationMap)
                .toList();
        List<Map<String, Object>> contextTurns = new ArrayList<>();
        int start = Math.max(0, previousTurns.size() - (MAX_CONTEXT_TURNS - 1));
        for (int index = start; index < previousTurns.size(); index++) {
            DocumentConversationTurn turn = previousTurns.get(index);
            contextTurns.add(Map.of("question", turn.question(), "answer", turn.answer()));
        }
        contextTurns.add(Map.of("question", question, "answer", response.answerMarkdown()));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", "document_chat");
        content.put("question", question);
        content.put("answerMarkdown", response.answerMarkdown());
        content.put("citations", citations);
        content.put("contextTurns", List.copyOf(contextTurns));
        content.put("warnings", response.warnings());

        Map<String, Object> metadata = new LinkedHashMap<>(response.metadata());
        putIfPresent(metadata, "provider", response.provider());
        putIfPresent(metadata, "model", response.model());
        putIfPresent(metadata, "providerRequestId", response.providerRequestId());
        metadata.put("textDeploymentCode", context.selectedModelCode(ModelCapability.TEXT_GENERATION));
        metadata.put("visionDeploymentCode", context.selectedModelCode(ModelCapability.VISION));
        metadata.put("sourceCount", citations.size());

        return FeatureExecutionResult.of(new ArtifactDraft(
                "document_answer",
                title(question),
                "application/vnd.yuanzuo.document-answer+json",
                content,
                metadata
        ));
    }

    private static List<DocumentConversationTurn> previousTurns(FeatureExecutionContext context) {
        if (context.baseArtifact() == null) return List.of();
        Object raw = context.baseArtifact().content().get("contextTurns");
        if (!(raw instanceof List<?> values)) return List.of();
        List<DocumentConversationTurn> turns = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Object questionValue = map.get("question");
            Object answerValue = map.get("answer");
            String question = questionValue == null ? "" : questionValue.toString().trim();
            String answer = answerValue == null ? "" : answerValue.toString().trim();
            if (!question.isBlank() && !answer.isBlank()) {
                turns.add(new DocumentConversationTurn(question, answer));
            }
        }
        return turns.size() <= MAX_CONTEXT_TURNS
                ? List.copyOf(turns)
                : List.copyOf(turns.subList(turns.size() - MAX_CONTEXT_TURNS, turns.size()));
    }

    private static List<UUID> documentIds(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<UUID> ids = new ArrayList<>();
        for (Object item : values) {
            try {
                UUID id = UUID.fromString(String.valueOf(item));
                if (!ids.contains(id)) ids.add(id);
            } catch (IllegalArgumentException ignored) {
                return List.of();
            }
        }
        return List.copyOf(ids);
    }

    private static Map<String, Object> citationMap(DocumentCitation citation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("marker", citation.marker());
        result.put("assetId", citation.assetId().toString());
        result.put("fileName", citation.fileName());
        result.put("excerpt", citation.excerpt());
        result.put("locator", citation.locator());
        return Map.copyOf(result);
    }

    private static String stringParameter(FeatureExecutionContext context, String name) {
        Object value = context.parameters().get(name);
        return value == null ? "" : value.toString().trim();
    }

    private static String extension(String name) {
        if (name == null) return "";
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }

    private static boolean supportsMediaType(String extension, String mediaType) {
        String normalized = mediaType == null
                ? ""
                : mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return ALLOWED_MEDIA_TYPES.getOrDefault(extension, Set.of()).contains(normalized);
    }

    private static String title(String question) {
        String normalized = question.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !value.toString().isBlank()) target.put(key, value);
    }
}
