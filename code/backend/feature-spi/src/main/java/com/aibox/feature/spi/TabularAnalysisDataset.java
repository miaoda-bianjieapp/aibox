package com.aibox.feature.spi;

import java.util.List;

public record TabularAnalysisDataset(
        String format,
        List<SheetProfile> sheets,
        List<Anomaly> anomalies,
        List<ChartCandidate> chartCandidates,
        int totalRows,
        int totalNonEmptyCells,
        List<String> warnings
) {
    public TabularAnalysisDataset {
        format = format == null ? "" : format;
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
        chartCandidates = chartCandidates == null ? List.of() : List.copyOf(chartCandidates);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("Tabular analysis requires at least one sheet");
        }
        if (chartCandidates.isEmpty()) {
            throw new IllegalArgumentException("Tabular analysis requires a chart candidate");
        }
    }

    public record SheetProfile(
            String name,
            int rowCount,
            int columnCount,
            int duplicateRowCount,
            List<ColumnProfile> columns
    ) {
        public SheetProfile {
            name = name == null ? "" : name;
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    public record ColumnProfile(
            String name,
            String type,
            int nonEmptyCount,
            int missingCount,
            int distinctCount,
            Double minimum,
            Double maximum,
            Double mean,
            Double median,
            Double firstQuartile,
            Double thirdQuartile,
            List<ValueFrequency> topValues
    ) {
        public ColumnProfile {
            name = name == null ? "" : name;
            type = type == null ? "EMPTY" : type;
            topValues = topValues == null ? List.of() : List.copyOf(topValues);
        }
    }

    public record ValueFrequency(String value, int count) {
        public ValueFrequency {
            value = value == null ? "" : value;
        }
    }

    public record Anomaly(
            String id,
            String type,
            String severity,
            String sheetName,
            String columnName,
            Integer rowNumber,
            String description,
            String evidence
    ) {
        public Anomaly {
            id = id == null ? "" : id;
            type = type == null ? "" : type;
            severity = severity == null ? "INFO" : severity;
            sheetName = sheetName == null ? "" : sheetName;
            columnName = columnName == null ? "" : columnName;
            description = description == null ? "" : description;
            evidence = evidence == null ? "" : evidence;
        }
    }

    public record ChartCandidate(
            String id,
            String title,
            String type,
            String sheetName,
            String categoryLabel,
            String valueLabel,
            List<String> categories,
            List<Double> values,
            String aggregation
    ) {
        public ChartCandidate {
            id = id == null ? "" : id;
            title = title == null ? "" : title;
            type = type == null ? "BAR" : type;
            sheetName = sheetName == null ? "" : sheetName;
            categoryLabel = categoryLabel == null ? "" : categoryLabel;
            valueLabel = valueLabel == null ? "" : valueLabel;
            categories = categories == null ? List.of() : List.copyOf(categories);
            values = values == null ? List.of() : List.copyOf(values);
            aggregation = aggregation == null ? "" : aggregation;
            if (categories.isEmpty() || categories.size() != values.size()) {
                throw new IllegalArgumentException("Chart categories and values must align");
            }
        }
    }
}
