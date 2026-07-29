import 'feature_models.dart';

class DataAnalysisResult {
  const DataAnalysisResult({
    required this.summaryMarkdown,
    required this.conclusions,
    required this.anomalies,
    required this.charts,
    required this.warnings,
    required this.reportName,
    required this.reportAsset,
  });

  factory DataAnalysisResult.fromArtifact(ArtifactView artifact) {
    final assetsById = {for (final asset in artifact.assets) asset.id: asset};
    final chartAssetIds = _stringList(artifact.content['chartAssetIds']);
    final chartAssets = chartAssetIds
        .map((id) => assetsById[id])
        .whereType<AssetView>()
        .toList();
    final reportId = artifact.content['reportAssetId']?.toString();
    return DataAnalysisResult(
      summaryMarkdown: artifact.content['summaryMarkdown']?.toString() ?? '',
      conclusions: _mapList(artifact.content['conclusions'])
          .map(DataAnalysisConclusion.fromJson)
          .toList(),
      anomalies: _mapList(artifact.content['anomalies'])
          .map(DataAnalysisAnomaly.fromJson)
          .toList(),
      charts: _mapList(artifact.content['charts'])
          .map(DataAnalysisChart.fromJson)
          .map((chart) => chart.withAsset(
                chart.assetIndex >= 0 && chart.assetIndex < chartAssets.length
                    ? chartAssets[chart.assetIndex]
                    : null,
              ))
          .toList(),
      warnings: _stringList(artifact.content['warnings']),
      reportName: artifact.content['reportName']?.toString() ?? '数据分析报告.xlsx',
      reportAsset: reportId == null ? null : assetsById[reportId],
    );
  }

  final String summaryMarkdown;
  final List<DataAnalysisConclusion> conclusions;
  final List<DataAnalysisAnomaly> anomalies;
  final List<DataAnalysisChart> charts;
  final List<String> warnings;
  final String reportName;
  final AssetView? reportAsset;

  String get copyText {
    final buffer = StringBuffer(summaryMarkdown.trim());
    if (conclusions.isNotEmpty) {
      buffer
        ..writeln()
        ..writeln()
        ..writeln('核心结论');
      for (final conclusion in conclusions) {
        buffer
          ..writeln()
          ..writeln('${conclusion.title}：${conclusion.detail}');
        for (final evidence in conclusion.evidence) {
          buffer.writeln('- $evidence');
        }
      }
    }
    if (anomalies.isNotEmpty) {
      buffer
        ..writeln()
        ..writeln('异常清单');
      for (final anomaly in anomalies) {
        buffer.writeln(
          '- ${anomaly.locationLabel} ${anomaly.description}：${anomaly.evidence}',
        );
      }
    }
    return buffer.toString().trim();
  }
}

class DataAnalysisConclusion {
  const DataAnalysisConclusion({
    required this.title,
    required this.detail,
    required this.evidence,
  });

  factory DataAnalysisConclusion.fromJson(Map<String, dynamic> json) =>
      DataAnalysisConclusion(
        title: json['title']?.toString() ?? '',
        detail: json['detail']?.toString() ?? '',
        evidence: _stringList(json['evidence']),
      );

  final String title;
  final String detail;
  final List<String> evidence;
}

class DataAnalysisAnomaly {
  const DataAnalysisAnomaly({
    required this.id,
    required this.type,
    required this.severity,
    required this.sheetName,
    required this.columnName,
    required this.rowNumber,
    required this.description,
    required this.evidence,
    required this.interpretation,
    required this.suggestion,
  });

  factory DataAnalysisAnomaly.fromJson(Map<String, dynamic> json) =>
      DataAnalysisAnomaly(
        id: json['id']?.toString() ?? '',
        type: json['type']?.toString() ?? '',
        severity: json['severity']?.toString() ?? 'INFO',
        sheetName: json['sheetName']?.toString() ?? '',
        columnName: json['columnName']?.toString() ?? '',
        rowNumber: json['rowNumber'] is num
            ? (json['rowNumber'] as num).toInt()
            : null,
        description: json['description']?.toString() ?? '',
        evidence: json['evidence']?.toString() ?? '',
        interpretation: json['interpretation']?.toString() ?? '',
        suggestion: json['suggestion']?.toString() ?? '',
      );

  final String id;
  final String type;
  final String severity;
  final String sheetName;
  final String columnName;
  final int? rowNumber;
  final String description;
  final String evidence;
  final String interpretation;
  final String suggestion;

  String get locationLabel => [
        if (sheetName.isNotEmpty) sheetName,
        if (columnName.isNotEmpty) columnName,
        if (rowNumber != null) '第$rowNumber行',
      ].join(' · ');
}

class DataAnalysisChart {
  const DataAnalysisChart({
    required this.id,
    required this.title,
    required this.type,
    required this.sheetName,
    required this.categoryLabel,
    required this.valueLabel,
    required this.aggregation,
    required this.assetIndex,
    this.asset,
  });

  factory DataAnalysisChart.fromJson(Map<String, dynamic> json) =>
      DataAnalysisChart(
        id: json['id']?.toString() ?? '',
        title: json['title']?.toString() ?? '',
        type: json['type']?.toString() ?? 'BAR',
        sheetName: json['sheetName']?.toString() ?? '',
        categoryLabel: json['categoryLabel']?.toString() ?? '',
        valueLabel: json['valueLabel']?.toString() ?? '',
        aggregation: json['aggregation']?.toString() ?? '',
        assetIndex: json['assetIndex'] is num
            ? (json['assetIndex'] as num).toInt()
            : -1,
      );

  final String id;
  final String title;
  final String type;
  final String sheetName;
  final String categoryLabel;
  final String valueLabel;
  final String aggregation;
  final int assetIndex;
  final AssetView? asset;

  DataAnalysisChart withAsset(AssetView? value) => DataAnalysisChart(
        id: id,
        title: title,
        type: type,
        sheetName: sheetName,
        categoryLabel: categoryLabel,
        valueLabel: valueLabel,
        aggregation: aggregation,
        assetIndex: assetIndex,
        asset: value,
      );
}

List<Map<String, dynamic>> _mapList(Object? value) => value is List
    ? value
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList()
    : const [];

List<String> _stringList(Object? value) =>
    value is List ? value.map((item) => item.toString()).toList() : const [];
