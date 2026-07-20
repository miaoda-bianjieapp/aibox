import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/pages/task_history_page.dart';

void main() {
  test('image revisions use the current artifact asset', () {
    const feature = FeatureEntry(
      id: 'image.expand',
      title: '扩图与改比例',
      description: '',
      version: 1,
      resultType: 'image',
      rendererKey: 'image',
      executionMode: 'ASYNC',
    );
    final run = RunView(
      id: 'run-1',
      runNumber: 1,
      status: 'SUCCEEDED',
      parameters: const {},
      inputAssetIds: const ['original-asset'],
      baseArtifactId: null,
      selectedModelCode: null,
      selectedModels: const {},
      errorCode: null,
      errorMessage: null,
      createdAt: DateTime(2026, 7, 18),
    );
    final artifact = ArtifactView(
      id: 'artifact-1',
      taskId: 'task-1',
      runId: 'run-1',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'image',
      title: '扩图结果',
      mimeType: 'image/png',
      content: const {'assetId': 'current-artifact-asset'},
      metadata: const {},
      createdAt: DateTime(2026, 7, 18),
    );

    expect(
      revisionInputAssetIds(feature: feature, run: run, artifact: artifact),
      ['current-artifact-asset'],
    );
  });

  test('non-image revisions keep the original run attachments', () {
    const feature = FeatureEntry(
      id: 'writing.draft',
      title: '从零起草',
      description: '',
      version: 1,
      resultType: 'rich_text',
      rendererKey: 'rich_text_editor',
      executionMode: 'ASYNC',
    );
    final run = RunView(
      id: 'run-1',
      runNumber: 1,
      status: 'SUCCEEDED',
      parameters: const {},
      inputAssetIds: const ['attachment-1'],
      baseArtifactId: null,
      selectedModelCode: null,
      selectedModels: const {},
      errorCode: null,
      errorMessage: null,
      createdAt: DateTime(2026, 7, 18),
    );
    final artifact = ArtifactView(
      id: 'artifact-1',
      taskId: 'task-1',
      runId: 'run-1',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'rich_text',
      title: '结果',
      mimeType: 'text/markdown',
      content: const {'text': 'content'},
      metadata: const {},
      createdAt: DateTime(2026, 7, 18),
    );

    expect(
      revisionInputAssetIds(feature: feature, run: run, artifact: artifact),
      ['attachment-1'],
    );
  });
}
