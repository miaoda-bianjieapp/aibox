package com.aibox.features.document.tableextraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class StructuredExtractionParser {

    private static final int MAX_TABLES = 50;
    private static final int MAX_COLUMNS = 100;
    private static final int MAX_ROWS_PER_TABLE = 5_000;
    private static final int MAX_FIELDS = 100;
    private static final int MAX_WARNINGS = 100;
    private static final int MAX_WARNING_LENGTH = 500;

    private final ObjectMapper objectMapper;

    StructuredExtractionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    StructuredExtractionResult parse(
            String modelText,
            int maximumPage,
            List<Integer> fallbackPages
    ) {
        try {
            JsonNode root = objectMapper.readTree(jsonObject(modelText));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("模型响应必须是 JSON 对象");
            }
            StructuredExtractionResult result = new StructuredExtractionResult();
            result.addWarnings(warnings(root.path("warnings")));
            parseTables(root.path("tables"), maximumPage, fallbackPages, result);
            parseFields(root.path("fields"), maximumPage, fallbackPages, result);
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("模型返回了无效的结构化数据", exception);
        }
    }

    private void parseTables(
            JsonNode tables,
            int maximumPage,
            List<Integer> fallbackPages,
            StructuredExtractionResult result
    ) {
        if (tables.isMissingNode() || tables.isNull()) return;
        if (!tables.isArray()) throw new IllegalArgumentException("tables 必须是数组");
        if (tables.size() > MAX_TABLES) {
            throw new IllegalArgumentException("单次响应中的表格数量超过限制");
        }
        int tableNumber = 0;
        for (JsonNode table : tables) {
            tableNumber++;
            if (!table.isObject()) throw new IllegalArgumentException("表格项必须是对象");
            List<List<String>> rows = rows(table.path("rows"));
            if (rows.isEmpty()) continue;
            List<String> columns = strings(table.path("columns"), MAX_COLUMNS);
            int width = Math.max(
                    columns.size(),
                    rows.stream().mapToInt(List::size).max().orElse(0)
            );
            if (width <= 0 || width > MAX_COLUMNS) {
                throw new IllegalArgumentException("表格列数超过限制");
            }
            List<String> normalizedColumns = new ArrayList<>(columns);
            while (normalizedColumns.size() < width) {
                normalizedColumns.add("列" + (normalizedColumns.size() + 1));
            }
            List<List<String>> normalizedRows = new ArrayList<>();
            for (List<String> row : rows) {
                List<String> normalized = new ArrayList<>(row);
                while (normalized.size() < width) {
                    normalized.add(StructuredExtractionResult.UNRECOGNIZED);
                }
                if (normalized.size() > width) {
                    normalized = new ArrayList<>(normalized.subList(0, width));
                }
                normalizedRows.add(List.copyOf(normalized));
            }
            SourcePages sourcePages = sourcePages(
                    table.path("sourcePages"),
                    maximumPage,
                    fallbackPages
            );
            List<String> tableWarnings = new ArrayList<>(warnings(table.path("warnings")));
            if (sourcePages.fallbackUsed()) {
                tableWarnings.add("模型未返回精确来源页码，已记录当前批次页范围");
            }
            result.addTable(new StructuredExtractionResult.ExtractedTable(
                    text(table.path("name"), "表" + tableNumber),
                    List.copyOf(normalizedColumns),
                    List.copyOf(normalizedRows),
                    sourcePages.pages(),
                    confidence(table.path("confidence")),
                    List.copyOf(tableWarnings)
            ));
        }
    }

    private void parseFields(
            JsonNode fields,
            int maximumPage,
            List<Integer> fallbackPages,
            StructuredExtractionResult result
    ) {
        if (fields.isMissingNode() || fields.isNull()) return;
        if (!fields.isArray()) throw new IllegalArgumentException("fields 必须是数组");
        if (fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("单次响应中的字段数量超过限制");
        }
        for (JsonNode field : fields) {
            if (!field.isObject()) throw new IllegalArgumentException("字段项必须是对象");
            String name = text(field.path("name"), "");
            if (name.isBlank()) continue;
            SourcePages sourcePages = sourcePages(
                    field.path("sourcePages"),
                    maximumPage,
                    fallbackPages
            );
            List<String> fieldWarnings = new ArrayList<>(warnings(field.path("warnings")));
            if (sourcePages.fallbackUsed()) {
                fieldWarnings.add("模型未返回精确来源页码，已记录当前批次页范围");
            }
            result.addField(new StructuredExtractionResult.ExtractedField(
                    name,
                    cellText(field.path("value")),
                    sourcePages.pages(),
                    confidence(field.path("confidence")),
                    List.copyOf(fieldWarnings)
            ));
        }
    }

    private List<List<String>> rows(JsonNode node) {
        if (!node.isArray()) return List.of();
        if (node.size() > MAX_ROWS_PER_TABLE) {
            throw new IllegalArgumentException("单个表格的行数超过限制");
        }
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : node) {
            if (!row.isArray()) throw new IllegalArgumentException("表格行必须是数组");
            if (row.size() > MAX_COLUMNS) {
                throw new IllegalArgumentException("表格列数超过限制");
            }
            List<String> cells = new ArrayList<>();
            row.forEach(cell -> cells.add(cellText(cell)));
            rows.add(List.copyOf(cells));
        }
        return List.copyOf(rows);
    }

    private SourcePages sourcePages(
            JsonNode node,
            int maximumPage,
            List<Integer> fallbackPages
    ) {
        Set<Integer> pages = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode page : node) {
                if (!page.canConvertToInt()) continue;
                int value = page.asInt();
                if (value >= 1 && value <= maximumPage) pages.add(value);
            }
        }
        boolean fallbackUsed = pages.isEmpty();
        if (fallbackUsed) {
            fallbackPages.stream()
                    .filter(page -> page >= 1 && page <= maximumPage)
                    .forEach(pages::add);
        }
        return new SourcePages(pages.stream().sorted().toList(), fallbackUsed);
    }

    private List<String> strings(JsonNode node, int maximum) {
        if (!node.isArray()) return List.of();
        if (node.size() > maximum) throw new IllegalArgumentException("数组项数量超过限制");
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(cellText(value)));
        return List.copyOf(values);
    }

    private List<String> warnings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode warning : node) {
            if (values.size() >= MAX_WARNINGS) break;
            String value = text(warning, "").trim();
            if (value.isEmpty()) continue;
            values.add(value.length() <= MAX_WARNING_LENGTH
                    ? value
                    : value.substring(0, MAX_WARNING_LENGTH));
        }
        return List.copyOf(values);
    }

    private String cellText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return StructuredExtractionResult.UNRECOGNIZED;
        }
        String value = node.isValueNode() ? node.asText() : node.toString();
        value = value == null ? "" : value.trim();
        return value.isEmpty() ? StructuredExtractionResult.UNRECOGNIZED : value;
    }

    private static String text(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        String value = node.asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static double confidence(JsonNode node) {
        if (node == null || !node.isNumber()) return 0.0;
        return Math.max(0.0, Math.min(1.0, node.asDouble()));
    }

    private static String jsonObject(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("模型没有返回结构化数据");
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

    private record SourcePages(List<Integer> pages, boolean fallbackUsed) {
    }
}
