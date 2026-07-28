import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/pages/document_compare_result_page.dart';
import 'package:yuanzuo_ai/app/state/app_data_controller.dart';

void main() {
  testWidgets('renders structured comparison and exposes export action',
      (tester) async {
    final excel = AssetView(
      id: 'excel-1',
      name: '多文档对比报告.xlsx',
      mediaType:
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      sizeBytes: 1024,
      createdAt: DateTime(2026, 7, 28),
    );
    final artifact = ArtifactView(
      id: 'artifact-1',
      taskId: 'task-1',
      runId: 'run-1',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'document_comparison',
      title: '合同多文档对比',
      mimeType: 'application/vnd.yuanzuo.document-comparison+json',
      content: const {
        'format': 'document_comparison',
        'detectedMode': 'contract',
        'reportMarkdown': '# 对比结论\n存在重要变化',
        'excelAssetId': 'excel-1',
        'pairwiseComparisons': [],
        'crossDocumentConclusion': {
          'summary': '三份文档的终止期限不同',
          'findings': [
            {
              'topic': '终止期限',
              'documentStatements': [
                {
                  'fileName': '甲.docx',
                  'content': '提前三十日通知',
                },
                {
                  'fileName': '乙.pdf',
                  'content': '提前七日通知',
                }
              ],
              'commonality': '均允许提前终止',
              'difference': '通知期限不同',
              'impact': '影响退出准备',
              'citationMarkers': ['S1', 'S2'],
            }
          ],
        },
        'risks': [
          {
            'severity': 'MEDIUM',
            'title': '通知期限缩短',
            'basis': '七日短于三十日',
            'recommendation': '确认退出准备时间',
            'citationMarkers': ['S1', 'S2'],
          }
        ],
        'citations': [
          {
            'marker': 'S1',
            'assetId': 'source-1',
            'fileName': '甲.docx',
            'excerpt': '提前三十日通知',
            'locator': {
              'type': 'WORD_PARAGRAPH',
              'paragraphStart': 1,
              'paragraphEnd': 1,
            },
          },
          {
            'marker': 'S2',
            'assetId': 'source-2',
            'fileName': '乙.pdf',
            'excerpt': '提前七日通知',
            'locator': {'type': 'PDF_PAGE', 'pageNumber': 2},
          }
        ],
        'warnings': [],
      },
      metadata: const {},
      assets: [excel],
      createdAt: DateTime(2026, 7, 28, 10, 30),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: DocumentCompareResultPage(
          data: AppDataController(),
          artifact: artifact,
          loadTask: (_) => Future<TaskDetail>.error('offline test'),
        ),
      ),
    );
    await tester.pump();

    expect(find.text('三份文档的终止期限不同'), findsOneWidget);
    expect(find.text('终止期限'), findsOneWidget);
    expect(find.text('通知期限缩短'), findsOneWidget);
    expect(find.byTooltip('导出'), findsOneWidget);
  });
}
