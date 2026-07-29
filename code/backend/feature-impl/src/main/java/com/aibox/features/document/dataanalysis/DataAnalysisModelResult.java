package com.aibox.features.document.dataanalysis;

import java.util.List;
import java.util.Map;

record DataAnalysisModelResult(
        String summaryMarkdown,
        List<Conclusion> conclusions,
        Map<String, AnomalyNote> anomalyNotes,
        List<String> selectedChartIds,
        List<String> warnings
) {
    DataAnalysisModelResult {
        summaryMarkdown = summaryMarkdown == null ? "" : summaryMarkdown.strip();
        conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
        anomalyNotes = anomalyNotes == null ? Map.of() : Map.copyOf(anomalyNotes);
        selectedChartIds = selectedChartIds == null ? List.of() : List.copyOf(selectedChartIds);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    record Conclusion(String title, String detail, List<String> evidence) {
        Conclusion {
            title = title == null ? "" : title.strip();
            detail = detail == null ? "" : detail.strip();
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    record AnomalyNote(String interpretation, String suggestion) {
        AnomalyNote {
            interpretation = interpretation == null ? "" : interpretation.strip();
            suggestion = suggestion == null ? "" : suggestion.strip();
        }
    }
}
