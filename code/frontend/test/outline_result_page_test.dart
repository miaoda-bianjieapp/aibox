import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/network/task_execution_result.dart';
import 'package:yuanzuo_ai/app/pages/outline_result_page.dart';

void main() {
  testWidgets('edited outline can be saved as a new version', (tester) async {
    String? capturedOperation;
    String? capturedText;
    final feature = _feature();

    await tester.pumpWidget(MaterialApp(
      home: OutlineResultPage(
        artifact: _artifact(version: 1, text: '原始框架'),
        onExecuteVersion: ({
          required baseArtifact,
          required operation,
          editedText,
          required onStatus,
        }) async {
          capturedOperation = operation;
          capturedText = editedText;
          return TaskExecutionResult(
            taskId: 'task-1',
            runId: 'run-2',
            feature: feature,
            artifact: _artifact(
              version: 2,
              text: editedText ?? '',
              sourceType: 'manual',
            ),
          );
        },
        onAdjustInput: (_) async => null,
      ),
    ));

    await tester.enterText(find.byType(TextField), '人工编辑后的框架');
    await tester.pump();
    expect(find.text('未保存'), findsOneWidget);

    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存新版本'));
    await tester.pumpAndSettle();

    expect(capturedOperation, 'save_edit');
    expect(capturedText, '人工编辑后的框架');
    await tester.drag(find.byType(ListView), const Offset(0, 800));
    await tester.pumpAndSettle();
    expect(find.textContaining('v2'), findsOneWidget);
    expect(find.text('未保存'), findsNothing);
  });

  testWidgets('regeneration requires fee confirmation', (tester) async {
    String? capturedOperation;
    final feature = _feature();

    await tester.pumpWidget(MaterialApp(
      home: OutlineResultPage(
        artifact: _artifact(version: 1, text: '原始框架'),
        onExecuteVersion: ({
          required baseArtifact,
          required operation,
          editedText,
          required onStatus,
        }) async {
          capturedOperation = operation;
          return TaskExecutionResult(
            taskId: 'task-1',
            runId: 'run-2',
            feature: feature,
            artifact: _artifact(version: 2, text: '重新生成的框架'),
          );
        },
        onAdjustInput: (_) async => null,
      ),
    ));

    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pumpAndSettle();
    await tester.tap(find.text('重新生成'));
    await tester.pumpAndSettle();
    expect(find.text('确认重新生成'), findsOneWidget);
    expect(capturedOperation, isNull);

    await tester.tap(find.text('继续生成'));
    await tester.pumpAndSettle();

    expect(capturedOperation, 'regenerate');
    await tester.drag(find.byType(ListView), const Offset(0, 800));
    await tester.pumpAndSettle();
    expect(find.textContaining('v2'), findsOneWidget);
  });
}

FeatureDetail _feature() => const FeatureDetail(
      id: 'writing.outline_ideas',
      title: '大纲与思路',
      description: '',
      version: 1,
      resultType: 'outline_text',
      rendererKey: 'outline_text_editor',
      executionMode: 'ASYNC',
      inputSchema: {},
      uiSchema: {},
      outputSchema: {},
      config: {},
      modelPolicies: [],
    );

ArtifactView _artifact({
  required int version,
  required String text,
  String sourceType = 'model',
}) =>
    ArtifactView(
      id: 'artifact-$version',
      taskId: 'task-1',
      runId: 'run-$version',
      parentArtifactId: version == 1 ? null : 'artifact-${version - 1}',
      versionNumber: version,
      kind: 'outline_text',
      title: '测试文章 - 大纲与思路',
      mimeType: 'text/plain',
      content: {'format': 'plain_text', 'text': text},
      metadata: {'sourceType': sourceType},
      createdAt: DateTime(2026, 7, 17, 16, 16),
    );
