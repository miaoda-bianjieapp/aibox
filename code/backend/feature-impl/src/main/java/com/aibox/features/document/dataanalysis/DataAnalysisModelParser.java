package com.aibox.features.document.dataanalysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DataAnalysisModelParser {

    private static final Set<String> ROOT_FIELDS = Set.of(
            "summaryMarkdown",
            "conclusions",
            "anomalyNotes",
            "selectedChartIds",
            "warnings"
    );
    private final ObjectMapper objectMapper;

    DataAnalysisModelParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    DataAnalysisModelResult parse(String value) {
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonObject(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("模型没有返回有效 JSON", exception);
        }
        requireObject(root, ROOT_FIELDS, "分析结果");
        String summary = requiredText(root, "summaryMarkdown", "分析结果");
        List<DataAnalysisModelResult.Conclusion> conclusions =
                conclusions(root.path("conclusions"));
        Map<String, DataAnalysisModelResult.AnomalyNote> notes =
                anomalyNotes(root.path("anomalyNotes"));
        List<String> selectedCharts = strings(
                root.path("selectedChartIds"),
                4,
                80,
                "selectedChartIds"
        );
        List<String> warnings = strings(root.path("warnings"), 20, 500, "warnings");
        if (conclusions.isEmpty()) {
            throw new IllegalArgumentException("模型没有返回核心结论");
        }
        return new DataAnalysisModelResult(
                summary,
                conclusions,
                notes,
                selectedCharts,
                warnings
        );
    }

    private List<DataAnalysisModelResult.Conclusion> conclusions(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > 10) {
            throw new IllegalArgumentException("conclusions 必须包含 1 到 10 项");
        }
        List<DataAnalysisModelResult.Conclusion> result = new ArrayList<>();
        for (JsonNode conclusion : node) {
            requireObject(
                    conclusion,
                    Set.of("title", "detail", "evidence"),
                    "结论"
            );
            result.add(new DataAnalysisModelResult.Conclusion(
                    requiredText(conclusion, "title", "结论"),
                    requiredText(conclusion, "detail", "结论"),
                    strings(conclusion.path("evidence"), 10, 500, "evidence")
            ));
        }
        return List.copyOf(result);
    }

    private Map<String, DataAnalysisModelResult.AnomalyNote> anomalyNotes(JsonNode node) {
        if (!node.isArray() || node.size() > 50) {
            throw new IllegalArgumentException("anomalyNotes 必须是最多 50 项的数组");
        }
        Map<String, DataAnalysisModelResult.AnomalyNote> result = new LinkedHashMap<>();
        for (JsonNode note : node) {
            requireObject(
                    note,
                    Set.of("anomalyId", "interpretation", "suggestion"),
                    "异常说明"
            );
            String anomalyId = requiredText(note, "anomalyId", "异常说明");
            if (result.put(
                    anomalyId,
                    new DataAnalysisModelResult.AnomalyNote(
                            optionalText(note, "interpretation"),
                            optionalText(note, "suggestion")
                    )
            ) != null) {
                throw new IllegalArgumentException("anomalyNotes 包含重复异常标识");
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> strings(
            JsonNode node,
            int maximumItems,
            int maximumLength,
            String field
    ) {
        if (!node.isArray() || node.size() > maximumItems) {
            throw new IllegalArgumentException(field + " 数量超过限制");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException(field + " 必须是字符串数组");
            }
            String normalized = value.textValue().strip();
            if (normalized.isEmpty() || normalized.length() > maximumLength) {
                throw new IllegalArgumentException(field + " 包含无效文本");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static void requireObject(
            JsonNode node,
            Set<String> expectedFields,
            String label
    ) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(label + "必须是 JSON 对象");
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expectedFields)) {
            throw new IllegalArgumentException(label + "字段不完整或包含额外字段");
        }
    }

    private static String requiredText(JsonNode node, String field, String label) {
        String value = optionalText(node, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + "缺少字段：" + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) return "";
        return value.textValue().strip();
    }

    private static String jsonObject(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("模型没有返回分析结果");
        }
        String normalized = value.trim();
        if (normalized.startsWith("```")) {
            int firstLine = normalized.indexOf('\n');
            int closing = normalized.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                normalized = normalized.substring(firstLine + 1, closing).trim();
            }
        }
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型响应中没有 JSON 对象");
        }
        return normalized.substring(start, end + 1);
    }
}
