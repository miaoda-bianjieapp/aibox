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
      'config': {
        'revisionSourceField': 'sourceText',
        'revisionSourceAssetField': 'sourceImage',
        'revisionResetFields': ['maskImage'],
      },
      'modelPolicies': const <Object?>[],
    });

    expect(feature.exampleFor('sourceText'), '示例文本');
    expect(feature.showResetAction, isTrue);
    expect(feature.revisionSourceField, 'sourceText');
    expect(feature.revisionSourceAssetField, 'sourceImage');
    expect(feature.revisionResetFields, {'maskImage'});
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
      baseArtifactAssetIds: ['asset-1'],
    );

    expect(request.isRevision, isTrue);
    expect(request.baseArtifactText, '上一版成果');
    expect(request.baseArtifactAssetIds, ['asset-1']);
  });

  test('feature visibility supports combined all conditions', () {
    final feature = FeatureDetail.fromJson({
      'code': 'image.expand',
      'displayName': '扩图与改比例',
      'description': '',
      'version': 3,
      'resultType': 'image',
      'rendererKey': 'image',
      'executionMode': 'ASYNC',
      'inputSchema': {
        'type': 'object',
        'properties': {
          'operationMode': {
            'type': 'string',
            'enum': ['change_ratio', 'expand'],
          },
          'ratioMode': {
            'type': 'string',
            'enum': ['preset', 'custom'],
          },
          'presetAspectRatio': {
            'type': 'string',
            'enum': ['1:1', '16:9'],
          },
        },
      },
      'uiSchema': {
        'visibility': {
          'presetAspectRatio': {
            'all': [
              {'field': 'operationMode', 'equals': 'change_ratio'},
              {'field': 'ratioMode', 'equals': 'preset'},
            ],
          },
        },
        'fieldHelp': {
          'operationMode': {
            'when': {'field': 'operationMode', 'equals': 'change_ratio'},
            'text': '该选项会给改比例后的图片进行填充处理',
            'tone': 'danger',
          },
        },
      },
      'outputSchema': const <String, Object?>{},
      'config': const <String, Object?>{},
      'modelPolicies': const <Object?>[],
    });

    expect(
      feature.isFieldVisible('presetAspectRatio', {
        'operationMode': 'change_ratio',
        'ratioMode': 'preset',
      }),
      isTrue,
    );
    expect(
      feature.isFieldVisible('presetAspectRatio', {
        'operationMode': 'expand',
        'ratioMode': 'preset',
      }),
      isFalse,
    );
    expect(
      feature.isFieldVisible('presetAspectRatio', {
        'operationMode': 'change_ratio',
        'ratioMode': 'custom',
      }),
      isFalse,
    );
    expect(
      feature.fieldHelp('operationMode', {
        'operationMode': 'change_ratio',
      })['text'],
      '该选项会给改比例后的图片进行填充处理',
    );
    expect(
      feature.fieldHelp('operationMode', {
        'operationMode': 'expand',
      }),
      isEmpty,
    );
  });
}
