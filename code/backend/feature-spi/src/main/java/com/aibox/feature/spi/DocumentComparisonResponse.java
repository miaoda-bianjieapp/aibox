package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DocumentComparisonResponse(
        String detectedMode,
        String summary,
        String reportMarkdown,
        List<PairwiseComparison> pairwiseComparisons,
        CrossDocumentConclusion crossDocumentConclusion,
        List<Risk> risks,
        List<DocumentCitation> citations,
        List<String> warnings,
        Map<String, Object> metadata
) {
    public DocumentComparisonResponse {
        detectedMode = detectedMode == null ? "general" : detectedMode;
        summary = summary == null ? "" : summary;
        reportMarkdown = reportMarkdown == null ? "" : reportMarkdown;
        pairwiseComparisons = pairwiseComparisons == null
                ? List.of()
                : List.copyOf(pairwiseComparisons);
        crossDocumentConclusion = crossDocumentConclusion == null
                ? new CrossDocumentConclusion("", List.of())
                : crossDocumentConclusion;
        risks = risks == null ? List.of() : List.copyOf(risks);
        citations = citations == null ? List.of() : List.copyOf(citations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public record PairwiseComparison(
            UUID comparisonAssetId,
            String comparisonFileName,
            String summary,
            List<Difference> differences
    ) {
        public PairwiseComparison {
            comparisonFileName = comparisonFileName == null ? "" : comparisonFileName;
            summary = summary == null ? "" : summary;
            differences = differences == null ? List.of() : List.copyOf(differences);
        }
    }

    public record Difference(
            String topic,
            String baselineContent,
            String comparisonContent,
            String impact,
            String changeType,
            List<String> citationMarkers
    ) {
        public Difference {
            topic = topic == null ? "" : topic;
            baselineContent = baselineContent == null ? "" : baselineContent;
            comparisonContent = comparisonContent == null ? "" : comparisonContent;
            impact = impact == null ? "" : impact;
            changeType = changeType == null ? "uncertain" : changeType;
            citationMarkers = citationMarkers == null
                    ? List.of()
                    : List.copyOf(citationMarkers);
        }
    }

    public record CrossDocumentConclusion(
            String summary,
            List<ConsensusFinding> findings
    ) {
        public CrossDocumentConclusion {
            summary = summary == null ? "" : summary;
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    public record ConsensusFinding(
            String topic,
            List<DocumentStatement> documentStatements,
            String commonality,
            String difference,
            String impact,
            List<String> citationMarkers
    ) {
        public ConsensusFinding {
            topic = topic == null ? "" : topic;
            documentStatements = documentStatements == null
                    ? List.of()
                    : List.copyOf(documentStatements);
            commonality = commonality == null ? "" : commonality;
            difference = difference == null ? "" : difference;
            impact = impact == null ? "" : impact;
            citationMarkers = citationMarkers == null
                    ? List.of()
                    : List.copyOf(citationMarkers);
        }
    }

    public record DocumentStatement(
            UUID assetId,
            String fileName,
            String content,
            List<String> citationMarkers
    ) {
        public DocumentStatement {
            fileName = fileName == null ? "" : fileName;
            content = content == null ? "" : content;
            citationMarkers = citationMarkers == null
                    ? List.of()
                    : List.copyOf(citationMarkers);
        }
    }

    public record Risk(
            String severity,
            String title,
            String basis,
            String recommendation,
            List<UUID> affectedAssetIds,
            List<String> citationMarkers
    ) {
        public Risk {
            severity = severity == null ? "LOW" : severity;
            title = title == null ? "" : title;
            basis = basis == null ? "" : basis;
            recommendation = recommendation == null ? "" : recommendation;
            affectedAssetIds = affectedAssetIds == null
                    ? List.of()
                    : List.copyOf(affectedAssetIds);
            citationMarkers = citationMarkers == null
                    ? List.of()
                    : List.copyOf(citationMarkers);
        }
    }
}
