package com.aibox.features.document.tableextraction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class StructuredExtractionResult {

    static final String UNRECOGNIZED = "无法识别";

    private final List<ExtractedTable> tables = new ArrayList<>();
    private final Map<String, ExtractedField> fields = new LinkedHashMap<>();
    private final Set<String> warnings = new LinkedHashSet<>();
    private boolean partial;

    void merge(StructuredExtractionResult other) {
        tables.addAll(other.tables);
        other.fields.values().forEach(this::mergeField);
        warnings.addAll(other.warnings);
        partial |= other.partial;
    }

    void addTable(ExtractedTable table) {
        tables.add(table);
    }

    void addField(ExtractedField field) {
        mergeField(field);
    }

    void addWarnings(Collection<String> values) {
        values.stream().filter(value -> value != null && !value.isBlank()).forEach(warnings::add);
    }

    void markPartial(String warning) {
        partial = true;
        if (warning != null && !warning.isBlank()) warnings.add(warning);
    }

    void ensureRequestedFields(List<String> requestedFields) {
        for (String requested : requestedFields) {
            String key = fieldKey(requested);
            if (!fields.containsKey(key)) {
                fields.put(key, new ExtractedField(
                        requested,
                        UNRECOGNIZED,
                        List.of(),
                        0.0,
                        List.of("未在文件中识别到该字段")
                ));
            }
        }
    }

    void keepOnlyRequestedFields(List<String> requestedFields) {
        Set<String> allowed = requestedFields.stream()
                .map(StructuredExtractionResult::fieldKey)
                .collect(java.util.stream.Collectors.toSet());
        fields.keySet().removeIf(key -> !allowed.contains(key));
    }

    void clearTables() {
        tables.clear();
    }

    void clearFields() {
        fields.clear();
    }

    boolean hasData() {
        return !tables.isEmpty() || !fields.isEmpty();
    }

    List<ExtractedTable> tables() {
        return List.copyOf(tables);
    }

    List<ExtractedField> fields() {
        return List.copyOf(fields.values());
    }

    List<String> warnings() {
        return List.copyOf(warnings);
    }

    boolean partial() {
        return partial;
    }

    List<Integer> sourcePages() {
        Set<Integer> pages = new LinkedHashSet<>();
        tables.forEach(table -> pages.addAll(table.sourcePages()));
        fields.values().forEach(field -> pages.addAll(field.sourcePages()));
        return pages.stream().sorted().toList();
    }

    double confidence() {
        List<Double> values = new ArrayList<>();
        tables.forEach(table -> values.add(table.confidence()));
        fields.values().forEach(field -> values.add(field.confidence()));
        if (values.isEmpty()) return 0.0;
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private void mergeField(ExtractedField candidate) {
        String key = fieldKey(candidate.name());
        ExtractedField current = fields.get(key);
        if (current == null) {
            fields.put(key, candidate);
            return;
        }
        boolean candidateReadable = !UNRECOGNIZED.equals(candidate.value());
        boolean currentReadable = !UNRECOGNIZED.equals(current.value());
        ExtractedField preferred = candidateReadable && (!currentReadable
                || candidate.confidence() > current.confidence())
                ? candidate
                : current;
        Set<Integer> pages = new LinkedHashSet<>(current.sourcePages());
        pages.addAll(candidate.sourcePages());
        Set<String> combinedWarnings = new LinkedHashSet<>(current.warnings());
        combinedWarnings.addAll(candidate.warnings());
        fields.put(key, new ExtractedField(
                preferred.name(),
                preferred.value(),
                pages.stream().sorted().toList(),
                Math.max(current.confidence(), candidate.confidence()),
                List.copyOf(combinedWarnings)
        ));
    }

    private static String fieldKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    record ExtractedTable(
            String name,
            List<String> columns,
            List<List<String>> rows,
            List<Integer> sourcePages,
            double confidence,
            List<String> warnings
    ) {
        ExtractedTable {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
            sourcePages = List.copyOf(sourcePages);
            warnings = List.copyOf(warnings);
        }
    }

    record ExtractedField(
            String name,
            String value,
            List<Integer> sourcePages,
            double confidence,
            List<String> warnings
    ) {
        ExtractedField {
            sourcePages = List.copyOf(sourcePages);
            warnings = List.copyOf(warnings);
        }
    }
}
