import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/data_analysis_models.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/pages/data_analysis_result_page.dart';

void main() {
  test('data analysis result maps chart and report assets', () {
    final artifact = _artifact();

    final result = DataAnalysisResult.fromArtifact(artifact);

    expect(result.summaryMarkdown, contains('销售表现稳定'));
    expect(result.conclusions.single.title, '区域销售');
    expect(result.anomalies.single.locationLabel, '销售 · 区域 · 第3行');
    expect(result.charts.single.asset?.id, 'chart-asset');
    expect(result.reportAsset?.id, 'report-asset');
    expect(result.copyText, contains('核心结论'));
    expect(result.copyText, contains('异常清单'));
  });

  testWidgets('data analysis result page renders composite sections',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: DataAnalysisResultPage(artifact: _artifact()),
    ));
    await tester.pumpAndSettle();

    final resultScrollable = find.descendant(
      of: find.byType(ListView),
      matching: find.byType(Scrollable),
    ).first;

    expect(find.text('数据分析结果'), findsOneWidget);
    expect(find.text('销售分析'), findsOneWidget);
    expect(find.text('区域销售'), findsOneWidget);
    expect(find.text('1 项'), findsOneWidget);

    await tester.scrollUntilVisible(
      find.text('分析图表'),
      400,
      scrollable: resultScrollable,
    );
    await tester.pumpAndSettle();

    expect(find.text('分析图表'), findsOneWidget);
    expect(find.text('区域销售额'), findsOneWidget);
    expect(find.text('图表文件不可用'), findsOneWidget);

    await tester.scrollUntilVisible(
      find.text('analysis-report.xlsx'),
      400,
      scrollable: resultScrollable,
    );
    await tester.pumpAndSettle();

    expect(find.text('analysis-report.xlsx'), findsOneWidget);
  });
}

ArtifactView _artifact() => ArtifactView(
      id: 'artifact-data-analysis',
      taskId: 'task-data-analysis',
      runId: 'run-data-analysis',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'data_analysis',
      title: '销售分析',
      mimeType: 'application/vnd.yuanzuo.data-analysis+json',
      content: const {
        'format': 'data_analysis',
        'summaryMarkdown': '## 概览\n销售表现稳定。',
        'conclusions': [
          {
            'title': '区域销售',
            'detail': '华东销售额领先。',
            'evidence': ['销售工作表中的区域汇总'],
          }
        ],
        'anomalies': [
          {
            'id': 'A1',
            'type': 'MISSING_VALUE',
            'severity': 'WARNING',
            'sheetName': '销售',
            'columnName': '区域',
            'rowNumber': 3,
            'description': '区域字段缺失',
            'evidence': '缺失数量 1',
            'interpretation': '可能影响区域汇总',
            'suggestion': '补齐区域',
          }
        ],
        'charts': [
          {
            'id': 'chart-1',
            'title': '区域销售额',
            'type': 'BAR',
            'sheetName': '销售',
            'categoryLabel': '区域',
            'valueLabel': '销售额',
            'aggregation': 'SUM',
            'assetIndex': 0,
          }
        ],
        'chartAssetIds': ['chart-asset'],
        'reportAssetId': 'report-asset',
        'reportName': 'analysis-report.xlsx',
        'warnings': ['CSV 首版仅支持 UTF-8'],
        'partial': false,
      },
      metadata: const {},
      assets: [
        AssetView(
          id: 'chart-asset',
          name: 'chart-1.png',
          mediaType: 'image/png',
          sizeBytes: 1024,
          createdAt: DateTime(2026, 7, 28, 16),
          origin: 'MODEL_OUTPUT',
          category: 'IMAGE',
          available: false,
        ),
        AssetView(
          id: 'report-asset',
          name: 'analysis-report.xlsx',
          mediaType:
              'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          sizeBytes: 2048,
          createdAt: DateTime(2026, 7, 28, 16),
          origin: 'MODEL_OUTPUT',
          category: 'DOCUMENT',
          available: false,
        ),
      ],
      createdAt: DateTime(2026, 7, 28, 16),
    );
