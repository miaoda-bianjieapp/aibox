import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';

void main() {
  test('feature detail exposes schema-driven examples and actions', () {
    final feature = FeatureDetail.fromJson({
      'code': 'writing.rewrite_polish',
      'displayName': '改写与润色',
      'description': '处理已有文本',
      'version': 1,
      'resultType': 'rich_text',
      'rendererKey': 'rich_text_editor',
      'executionMode': 'ASYNC',
      'inputSchema': {
        'type': 'object',
        'properties': {
          'mode': {
            'type': 'string',
            'enum': ['rewrite', 'polish'],
            'default': 'rewrite',
          },
          'sourceText': {'type': 'string', 'maxLength': 2000},
          'rewriteRequirements': {'type': 'string', 'maxLength': 500},
          'polishRequirements': {'type': 'string', 'maxLength': 500},
        },
      },
      'uiSchema': {
        'order': [
          'mode',
          'sourceText',
          'rewriteRequirements',
          'polishRequirements',
        ],
        'widgets': {
          'mode': 'segmented',
          'sourceText': 'textarea',
          'rewriteRequirements': 'textarea',
          'polishRequirements': 'textarea',
        },
        'visibility': {
          'rewriteRequirements': {'field': 'mode', 'equals': 'rewrite'},
          'polishRequirements': {'field': 'mode', 'equals': 'polish'},
        },
        'examples': {'sourceText': '示例文本'},
        'actions': {'showReset': true},
      },
      'outputSchema': const <String, Object?>{},
      'config': {'revisionSourceField': 'sourceText'},
      'modelPolicies': const <Object?>[],
    });

    expect(feature.exampleFor('sourceText'), '示例文本');
    expect(feature.showResetAction, isTrue);
    expect(feature.revisionSourceField, 'sourceText');
    expect(
      feature.isFieldVisible('rewriteRequirements', {'mode': 'rewrite'}),
      isTrue,
    );
    expect(
      feature.isFieldVisible('polishRequirements', {'mode': 'rewrite'}),
      isFalse,
    );
    expect(
      feature.isFieldVisible('polishRequirements', {'mode': 'polish'}),
      isTrue,
    );
  });

  test('task launch request carries the selected base artifact text', () {
    const workspace = WorkspaceDefinition(
      id: 'writing',
      title: '文本与写作',
      description: '',
      iconKey: 'edit',
      groups: {},
      searchTerms: [],
      entries: [],
    );
    const entry = FeatureEntry(
      id: 'writing.rewrite_polish',
      title: '改写与润色',
      description: '',
      version: 1,
      resultType: 'rich_text',
      rendererKey: 'rich_text_editor',
      executionMode: 'ASYNC',
    );
    const request = TaskLaunchRequest(
      workspace: workspace,
      entry: entry,
      existingTaskId: 'task-1',
      baseArtifactId: 'artifact-1',
      baseArtifactText: '上一版成果',
    );

    expect(request.isRevision, isTrue);
    expect(request.baseArtifactText, '上一版成果');
  });
}
