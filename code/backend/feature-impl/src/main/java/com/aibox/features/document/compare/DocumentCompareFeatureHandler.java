package com.aibox.features.document.compare;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.DocumentCitation;
import com.aibox.feature.spi.DocumentComparisonExportOption;
import com.aibox.feature.spi.DocumentComparisonExports;
import com.aibox.feature.spi.DocumentComparisonRequest;
import com.aibox.feature.spi.DocumentComparisonResponse;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureOutputEmitter;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.StreamingFeatureHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public final class DocumentCompareFeatureHandler implements StreamingFeatureHandler {

    public static final String FEATURE_CODE = "document.compare";

    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;
    private static final int MAX_INSTRUCTIONS_CHARACTERS = 2_000;
    private static final int MAX_OUTPUT_TOKENS = 8_000;
    private static final int PROMPT_VERSION = 2;
    private static final String TEXT_MODEL_ALIAS = "text.document-compare";
    private static final String VISION_MODEL_ALIAS = "vision.document-compare";
    private static final Set<String> PARAMETER_NAMES = Set.of(
            "baselineDocument",
            "comparisonDocuments",
            "comparisonMode",
            "instructions"
    );
    private static final Set<String> MODES = Set.of(
            "auto", "contract", "policy", "version"
    );
    private static final Set<String> EXTENSIONS = Set.of(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".txt", ".md", ".csv", ".json"
    );

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        if (!PARAMETER_NAMES.containsAll(context.parameters().keySet())) {
            throw new FeatureValidationException(
                    "parameters",
                    "包含不支持的多文档对比参数"
            );
        }
        UUID baselineId = optionalUuidParameter(context, "baselineDocument");
        List<UUID> comparisonIds = uuidListParameter(
                context,
                "comparisonDocuments"
        );
        int minimum = baselineId == null ? 2 : 1;
        int maximum = baselineId == null ? 5 : 4;
        if (comparisonIds.size() < minimum || comparisonIds.size() > maximum) {
            throw new FeatureValidationException(
                    "comparisonDocuments",
                    baselineId == null
                            ? "无基准文档时必须选择 2 至 5 份对比文档"
                            : "有基准文档时必须选择 1 至 4 份对比文档"
            );
        }
        if (baselineId != null && comparisonIds.contains(baselineId)) {
            throw new FeatureValidationException(
                    "comparisonDocuments",
                    "基准文档不能同时作为对比文档"
            );
        }

        List<UUID> expectedIds = new ArrayList<>();
        if (baselineId != null) expectedIds.add(baselineId);
        expectedIds.addAll(comparisonIds);
        if (!context.inputAssetIds().equals(expectedIds)
                || context.inputAssets().size() != expectedIds.size()) {
            throw new FeatureValidationException(
                    "comparisonDocuments",
                    "文档参数必须与本次 Run 的输入附件顺序一致"
            );
        }
        long totalBytes = 0;
        for (int index = 0; index < context.inputAssets().size(); index++) {
            InputAssetReference asset = context.inputAssets().get(index);
            if (!asset.id().equals(expectedIds.get(index))) {
                throw new FeatureValidationException(
                        "comparisonDocuments",
                        "存在无法读取或顺序不一致的文档"
                );
            }
            if (asset.sizeBytes() <= 0 || asset.sizeBytes() > MAX_FILE_BYTES) {
                throw new FeatureValidationException(
                        "comparisonDocuments",
                        "单个文档必须大于 0 且不能超过 50 MB"
                );
            }
            String extension = extension(asset.fileName());
            if (!EXTENSIONS.contains(extension)
                    || !mediaTypeMatches(extension, asset.mediaType())) {
                throw new FeatureValidationException(
                        "comparisonDocuments",
                        "不支持该文档格式或扩展名与 MIME 不匹配：" + asset.fileName()
                );
            }
            totalBytes += asset.sizeBytes();
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new FeatureValidationException(
                    "comparisonDocuments",
                    "全部文档总大小不能超过 200 MB"
            );
        }

        String mode = stringParameter(context, "comparisonMode", "auto");
        if (!MODES.contains(mode)) {
            throw new FeatureValidationException(
                    "comparisonMode",
                    "不支持该文档对比模式"
            );
        }
        String instructions = stringParameter(context, "instructions", "");
        if (codePointCount(instructions) > MAX_INSTRUCTIONS_CHARACTERS) {
            throw new FeatureValidationException(
                    "instructions",
                    "补充对比要求不能超过 2000 字"
            );
        }
        if (isBlank(context.selectedModelCode(ModelCapability.TEXT_GENERATION))
                || isBlank(context.selectedModelCode(ModelCapability.VISION))) {
            throw new FeatureValidationException(
                    "selectedModels",
                    "文档对比的文本和视觉模型配置不完整"
            );
        }
        if (context.baseArtifact() != null
                && !"document_comparison".equals(context.baseArtifact().kind())) {
            throw new FeatureValidationException(
                    "baseArtifactId",
                    "只能基于多文档对比成果继续修改"
            );
        }
    }

    @Override
    public FeatureExecutionResult execute(
            FeatureExecutionContext context,
            ModelGateway modelGateway,
            FeatureOutputEmitter outputEmitter
    ) {
        UUID baselineId = optionalUuidParameter(context, "baselineDocument");
        List<UUID> comparisonIds = uuidListParameter(
                context,
                "comparisonDocuments"
        );
        String mode = stringParameter(context, "comparisonMode", "auto");
        String instructions = stringParameter(context, "instructions", "");
        Map<UUID, InputAssetReference> assetsById = new LinkedHashMap<>();
        context.inputAssets().forEach(asset -> assetsById.put(asset.id(), asset));
        String baselineFileName = baselineId == null
                ? ""
                : assetsById.get(baselineId).fileName();

        outputEmitter.start("main", "text");
        outputEmitter.replaceText("main", "正在解析文档并建立可追溯来源");
        DocumentComparisonResponse response = modelGateway.compareDocuments(
                new DocumentComparisonRequest(
                        context.tenantId(),
                        context.userId(),
                        context.runId(),
                        context.taskId(),
                        baselineId,
                        comparisonIds,
                        mode,
                        instructions,
                        TEXT_MODEL_ALIAS,
                        context.selectedModelCode(ModelCapability.TEXT_GENERATION),
                        VISION_MODEL_ALIAS,
                        context.selectedModelCode(ModelCapability.VISION),
                        MAX_OUTPUT_TOKENS,
                        Map.of(
                                "featureCode", FEATURE_CODE,
                                "promptVersion", PROMPT_VERSION
                        )
                )
        );
        if (response.reportMarkdown().isBlank()) {
            throw new ModelProviderException(
                    "DOCUMENT_COMPARISON_EMPTY",
                    "模型没有返回可展示的文档对比结果",
                    true
            );
        }

        List<DocumentComparisonExportOption> exportOptions =
                DocumentComparisonExports.availableOptions(
                        baselineId,
                        baselineFileName,
                        response
                );
        List<String> warnings = response.warnings();

        Map<String, Object> content = content(
                context,
                baselineId,
                comparisonIds,
                assetsById,
                mode,
                response,
                warnings,
                exportOptions
        );
        Map<String, Object> metadata = new LinkedHashMap<>(response.metadata());
        metadata.put("promptVersion", PROMPT_VERSION);
        metadata.put("mode", mode);
        metadata.put("detectedMode", response.detectedMode());
        metadata.put("comparabilityStatus", response.comparability().status());
        metadata.put("hasBaseline", baselineId != null);
        metadata.put("inputDocumentCount", context.inputAssetIds().size());
        metadata.put("availableExportCount", exportOptions.size());
        if (context.baseArtifact() != null) {
            metadata.put(
                    "basedOnArtifactId",
                    context.baseArtifact().id().toString()
            );
            metadata.put(
                    "basedOnVersion",
                    context.baseArtifact().versionNumber()
            );
        }

        outputEmitter.replaceText("main", "对比完成，可查看正文和来源；导出文件将在需要时生成");
        return FeatureExecutionResult.of(new ArtifactDraft(
                "document_comparison",
                artifactTitle(baselineId, comparisonIds, assetsById),
                "application/vnd.yuanzuo.document-comparison+json",
                content,
                Map.copyOf(metadata),
                List.of()
        ));
    }

    private static Map<String, Object> content(
            FeatureExecutionContext context,
            UUID baselineId,
            List<UUID> comparisonIds,
            Map<UUID, InputAssetReference> assetsById,
            String mode,
            DocumentComparisonResponse response,
            List<String> warnings,
            List<DocumentComparisonExportOption> exportOptions
    ) {
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("format", "document_comparison");
        content.put("mode", mode);
        content.put("detectedMode", response.detectedMode());
        content.put("hasBaseline", baselineId != null);
        content.put("summary", response.summary());
        content.put("comparability", comparabilityMap(response.comparability()));
        content.put("reportMarkdown", response.reportMarkdown());
        content.put(
                "documents",
                documentMaps(baselineId, comparisonIds, assetsById)
        );
        content.put(
                "pairwiseComparisons",
                response.pairwiseComparisons().stream()
                        .map(DocumentCompareFeatureHandler::pairMap)
                        .toList()
        );
        content.put(
                "crossDocumentConclusion",
                conclusionMap(response.crossDocumentConclusion())
        );
        content.put(
                "risks",
                response.risks().stream()
                        .map(DocumentCompareFeatureHandler::riskMap)
                        .toList()
        );
        content.put(
                "citations",
                response.citations().stream()
                        .map(DocumentCompareFeatureHandler::citationMap)
                        .toList()
        );
        content.put("warnings", List.copyOf(new LinkedHashSet<>(warnings)));
        content.put(
                "exportOptions",
                exportOptions.stream()
                        .map(DocumentCompareFeatureHandler::exportOptionMap)
                        .toList()
        );
        return Map.copyOf(content);
    }

    private static List<Map<String, Object>> documentMaps(
            UUID baselineId,
            List<UUID> comparisonIds,
            Map<UUID, InputAssetReference> assetsById
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (baselineId != null) {
            result.add(documentMap(assetsById.get(baselineId), "baseline"));
        }
        comparisonIds.forEach(id ->
                result.add(documentMap(assetsById.get(id), "comparison")));
        return List.copyOf(result);
    }

    private static Map<String, Object> documentMap(
            InputAssetReference asset,
            String role
    ) {
        return Map.of(
                "assetId", asset.id().toString(),
                "fileName", asset.fileName(),
                "role", role
        );
    }

    private static Map<String, Object> pairMap(
            DocumentComparisonResponse.PairwiseComparison pair
    ) {
        return Map.of(
                "comparisonAssetId", pair.comparisonAssetId().toString(),
                "comparisonFileName", pair.comparisonFileName(),
                "summary", pair.summary(),
                "comparability", comparabilityMap(pair.comparability()),
                "differences", pair.differences().stream()
                        .map(DocumentCompareFeatureHandler::differenceMap)
                        .toList()
        );
    }

    private static Map<String, Object> comparabilityMap(
            DocumentComparisonResponse.Comparability comparability
    ) {
        return Map.of(
                "status", comparability.status(),
                "reason", comparability.reason(),
                "sharedTopics", comparability.sharedTopics(),
                "citationMarkers", comparability.citationMarkers()
        );
    }

    private static Map<String, Object> differenceMap(
            DocumentComparisonResponse.Difference difference
    ) {
        return Map.of(
                "topic", difference.topic(),
                "baselineContent", difference.baselineContent(),
                "comparisonContent", difference.comparisonContent(),
                "impact", difference.impact(),
                "changeType", difference.changeType(),
                "citationMarkers", difference.citationMarkers()
        );
    }

    private static Map<String, Object> conclusionMap(
            DocumentComparisonResponse.CrossDocumentConclusion conclusion
    ) {
        return Map.of(
                "summary", conclusion.summary(),
                "findings", conclusion.findings().stream()
                        .map(DocumentCompareFeatureHandler::findingMap)
                        .toList()
        );
    }

    private static Map<String, Object> findingMap(
            DocumentComparisonResponse.ConsensusFinding finding
    ) {
        return Map.of(
                "topic", finding.topic(),
                "documentStatements", finding.documentStatements().stream()
                        .map(DocumentCompareFeatureHandler::statementMap)
                        .toList(),
                "commonality", finding.commonality(),
                "difference", finding.difference(),
                "impact", finding.impact(),
                "citationMarkers", finding.citationMarkers()
        );
    }

    private static Map<String, Object> statementMap(
            DocumentComparisonResponse.DocumentStatement statement
    ) {
        return Map.of(
                "assetId", statement.assetId().toString(),
                "fileName", statement.fileName(),
                "content", statement.content(),
                "citationMarkers", statement.citationMarkers()
        );
    }

    private static Map<String, Object> riskMap(
            DocumentComparisonResponse.Risk risk
    ) {
        return Map.of(
                "severity", risk.severity(),
                "title", risk.title(),
                "basis", risk.basis(),
                "recommendation", risk.recommendation(),
                "affectedAssetIds", risk.affectedAssetIds().stream()
                        .map(UUID::toString)
                        .toList(),
                "citationMarkers", risk.citationMarkers()
        );
    }

    private static Map<String, Object> citationMap(DocumentCitation citation) {
        return Map.of(
                "marker", citation.marker(),
                "assetId", citation.assetId().toString(),
                "fileName", citation.fileName(),
                "excerpt", citation.excerpt(),
                "locator", citation.locator()
        );
    }

    private static Map<String, Object> exportOptionMap(
            DocumentComparisonExportOption option
    ) {
        return Map.of(
                "type", option.type(),
                "label", option.label(),
                "fileName", option.fileName(),
                "mediaType", option.mediaType()
        );
    }

    private static UUID optionalUuidParameter(
            FeatureExecutionContext context,
            String name
    ) {
        Object value = context.parameters().get(name);
        if (value == null || value.toString().isBlank()) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            throw new FeatureValidationException(name, "文档标识无效");
        }
    }

    private static List<UUID> uuidListParameter(
            FeatureExecutionContext context,
            String name
    ) {
        Object value = context.parameters().get(name);
        if (!(value instanceof List<?> items)) {
            throw new FeatureValidationException(name, "请选择对比文档");
        }
        List<UUID> result = new ArrayList<>();
        for (Object item : items) {
            try {
                UUID id = UUID.fromString(String.valueOf(item));
                if (!result.add(id)) {
                    throw new FeatureValidationException(name, "对比文档不能重复");
                }
            } catch (IllegalArgumentException exception) {
                throw new FeatureValidationException(name, "对比文档标识无效");
            }
        }
        return List.copyOf(result);
    }

    private static String stringParameter(
            FeatureExecutionContext context,
            String name,
            String fallback
    ) {
        Object value = context.parameters().get(name);
        if (value == null) return fallback;
        String normalized = value.toString().trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static boolean mediaTypeMatches(String extension, String mediaType) {
        String normalized = mediaType == null
                ? ""
                : mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || "application/octet-stream".equals(normalized)) {
            return true;
        }
        return switch (extension) {
            case ".pdf" -> Set.of("application/pdf", "application/x-pdf")
                    .contains(normalized);
            case ".doc" -> Set.of(
                    "application/msword",
                    "application/vnd.ms-word",
                    "application/x-tika-msoffice"
            ).contains(normalized);
            case ".docx" -> Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip",
                    "application/x-zip-compressed"
            ).contains(normalized);
            case ".xls" -> Set.of(
                    "application/vnd.ms-excel",
                    "application/msexcel",
                    "application/x-excel",
                    "application/x-tika-msoffice"
            ).contains(normalized);
            case ".xlsx" -> Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/zip",
                    "application/x-zip-compressed"
            ).contains(normalized);
            case ".ppt" -> Set.of(
                    "application/vnd.ms-powerpoint",
                    "application/mspowerpoint",
                    "application/x-tika-msoffice"
            ).contains(normalized);
            case ".pptx" -> Set.of(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/zip",
                    "application/x-zip-compressed"
            ).contains(normalized);
            case ".txt" -> "text/plain".equals(normalized);
            case ".md" -> Set.of(
                    "text/markdown",
                    "text/x-markdown",
                    "text/plain"
            ).contains(normalized);
            case ".csv" -> Set.of(
                    "text/csv",
                    "text/plain",
                    "application/csv",
                    "text/comma-separated-values",
                    "application/vnd.ms-excel"
            ).contains(normalized);
            case ".json" -> Set.of(
                    "application/json",
                    "text/json",
                    "text/plain"
            ).contains(normalized);
            default -> false;
        };
    }

    private static String artifactTitle(
            UUID baselineId,
            List<UUID> comparisonIds,
            Map<UUID, InputAssetReference> assetsById
    ) {
        if (baselineId != null) {
            return baseName(assetsById.get(baselineId).fileName()) + " 多文档对比";
        }
        return baseName(assetsById.get(comparisonIds.get(0)).fileName())
                + " 等 " + comparisonIds.size() + " 份文档对比";
    }

    private static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "文档";
        int index = fileName.lastIndexOf('.');
        return index <= 0 ? fileName : fileName.substring(0, index);
    }

    private static String extension(String name) {
        if (name == null) return "";
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }

    private static int codePointCount(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
