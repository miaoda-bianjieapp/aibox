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
        'comparability': {
          'status': 'COMPARABLE',
          'reason': '三份合同主题和用途一致，可以进行完整比较',
          'sharedTopics': ['终止期限'],
          'citationMarkers': ['S1', 'S2'],
        },
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
    await tester.scrollUntilVisible(
      find.text('通知期限缩短'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('通知期限缩短'), findsOneWidget);
    expect(find.byTooltip('导出'), findsOneWidget);
  });

  testWidgets('shows terminal comparability and hides empty analysis sections',
      (tester) async {
    final artifact = ArtifactView(
      id: 'artifact-terminal',
      taskId: 'task-terminal',
      runId: 'run-terminal',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'document_comparison',
      title: '完全不同文档对比',
      mimeType: 'application/vnd.yuanzuo.document-comparison+json',
      content: const {
        'format': 'document_comparison',
        'detectedMode': 'general',
        'comparability': {
          'status': 'NOT_COMPARABLE',
          'reason': '采购合同与员工手册的主题和用途不同',
          'sharedTopics': [],
          'citationMarkers': [],
        },
        'reportMarkdown': '# 对比结论\n文档不可比',
        'pairwiseComparisons': [
          {
            'comparisonAssetId': 'comparison-1',
            'comparisonFileName': '员工手册.pdf',
            'summary': '不适合进行实质差异对比',
            'comparability': {
              'status': 'NOT_COMPARABLE',
              'reason': '采购合同与员工手册的主题和用途不同',
              'sharedTopics': [],
              'citationMarkers': [],
            },
            'differences': [],
          }
        ],
        'crossDocumentConclusion': {
          'summary': '文档主题或用途不同，不适合进行实质差异对比。',
          'findings': [],
        },
        'risks': [],
        'citations': [],
        'warnings': [],
      },
      metadata: const {},
      assets: const [],
      createdAt: DateTime(2026, 7, 29, 10, 30),
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

    expect(find.text('不可比'), findsWidgets);
    expect(find.text('采购合同与员工手册的主题和用途不同'), findsOneWidget);
    expect(find.text('多文档综合结论'), findsNothing);
    expect(find.text('风险清单'), findsNothing);
  });

  testWidgets('does not infer comparability for legacy artifacts',
      (tester) async {
    final artifact = ArtifactView(
      id: 'artifact-legacy',
      taskId: 'task-legacy',
      runId: 'run-legacy',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'document_comparison',
      title: '历史文档对比',
      mimeType: 'application/vnd.yuanzuo.document-comparison+json',
      content: const {
        'format': 'document_comparison',
        'detectedMode': 'contract',
        'reportMarkdown': '# 对比结论\n存在差异',
        'pairwiseComparisons': [],
        'crossDocumentConclusion': {
          'summary': '历史对比结论',
          'findings': [],
        },
        'risks': [],
        'citations': [],
        'warnings': [],
      },
      metadata: const {},
      assets: const [],
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

    expect(find.text('历史对比结论'), findsOneWidget);
    expect(find.text('可比'), findsNothing);
  });
}
