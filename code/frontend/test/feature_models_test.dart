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
        'promptAssist': {
          'fields': {
            'rewriteRequirements': {
              'contextFields': ['mode', 'sourceText'],
            },
          },
        },
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
    expect(feature.supportsPromptAssist('rewriteRequirements'), isTrue);
    expect(
      feature.promptAssistContextFields('rewriteRequirements'),
      ['mode', 'sourceText'],
    );
    expect(feature.supportsPromptAssist('sourceText'), isFalse);
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
      baseArtifactAssets: [],
    );

    expect(request.isRevision, isTrue);
    expect(request.baseArtifactText, '上一版成果');
    expect(request.baseArtifactAssetIds, ['asset-1']);
  });

  test('single selectable model still exposes its selector', () {
    final policy = ModelPolicy.fromJson({
      'capability': 'IMAGE_GENERATION',
      'defaultModelCode': 'gpt-image-2',
      'allowUserSelection': true,
      'options': [
        {
          'code': 'gpt-image-2',
          'displayName': 'GPT Image 2',
          'description': '局部编辑模型',
          'isDefault': true,
          'sourceType': 'RELAY',
          'sourceName': 'Codex2API Relay',
        },
      ],
    });

    expect(policy.shouldShowSelector, isTrue);
  });

  test('audio enhancement policy exposes its model selector', () {
    final policy = ModelPolicy.fromJson({
      'capability': 'AUDIO_ENHANCEMENT',
      'defaultModelCode': 'cleanvoice-studio-sound-audio',
      'allowUserSelection': true,
      'options': [
        {
          'code': 'cleanvoice-studio-sound-audio',
          'displayName': 'Cleanvoice Studio Sound',
          'description': '官方稳定版音频增强模型',
          'isDefault': true,
          'sourceType': 'OFFICIAL',
          'sourceName': 'Cleanvoice 官方',
        },
      ],
    });

    expect(policy.shouldShowSelector, isTrue);
    expect(policy.options.single.code, 'cleanvoice-studio-sound-audio');
  });

  test('feature detail exposes model selector presentation options', () {
    final feature = FeatureDetail.fromJson({
      'code': 'audio.transcription',
      'displayName': '音频转写',
      'description': '',
      'version': 4,
      'resultType': 'transcript',
      'rendererKey': 'transcript',
      'executionMode': 'ASYNC',
      'inputSchema': const <String, Object?>{},
      'uiSchema': {
        'modelSelectors': {
          'TEXT_GENERATION': {
            'label': '摘要/会议纪要模型',
            'widget': 'dropdown',
          },
        },
      },
      'outputSchema': const <String, Object?>{},
      'config': const <String, Object?>{},
      'modelPolicies': const <Object?>[],
    });

    expect(
      feature.modelSelectorOptions('TEXT_GENERATION'),
      containsPair('widget', 'dropdown'),
    );
    expect(
      feature.modelSelectorOptions('TEXT_GENERATION')['label'],
      '摘要/会议纪要模型',
    );
    expect(feature.modelSelectorOptions('VISION'), isEmpty);
  });

  test('model option exposes its reference image limit', () {
    final option = ModelOption.fromJson({
      'code': 'gpt-image-2',
      'displayName': 'GPT Image 2',
      'description': '支持参考图',
      'isDefault': true,
      'sourceType': 'RELAY',
      'sourceName': 'Codex2API Relay',
      'maxReferenceImages': 4,
    });

    expect(option.maxReferenceImages, 4);
    expect(option.supportsReferenceImages, isTrue);

    final unsupported = ModelOption.fromJson({
      'code': 'text-to-image-only',
      'displayName': '仅文生图',
      'description': '不支持参考图',
      'isDefault': false,
      'sourceType': 'OFFICIAL',
      'sourceName': 'Official',
      'maxReferenceImages': 0,
    });
    expect(unsupported.supportsReferenceImages, isFalse);
  });

  test('feature detail exposes schema-driven linked model selections', () {
    final feature = FeatureDetail.fromJson({
      'code': 'document.summary',
      'displayName': '文档总结',
      'description': '',
      'version': 1,
      'resultType': 'rich_text',
      'rendererKey': 'rich_text_editor',
      'executionMode': 'ASYNC',
      'inputSchema': const <String, Object?>{},
      'uiSchema': {
        'modelSelectionGroups': [
          {
            'key': 'documentModel',
            'label': '文档处理模型',
            'capabilities': ['TEXT_GENERATION', 'VISION'],
            'options': [
              {
                'value': 'gpt-5.6-sol',
                'displayName': 'GPT-5.6 Sol',
                'description': '长文档模型',
                'deployments': {
                  'TEXT_GENERATION': 'gpt-5.6-sol-text',
                  'VISION': 'gpt-5.6-sol-vision',
                },
              },
            ],
          },
        ],
      },
      'outputSchema': const <String, Object?>{},
      'config': const <String, Object?>{},
      'modelPolicies': [
        {
          'capability': 'TEXT_GENERATION',
          'defaultModelCode': 'gpt-5.6-sol-text',
          'allowUserSelection': true,
          'options': [
            {
              'code': 'gpt-5.6-sol-text',
              'displayName': 'GPT-5.6 Sol',
              'description': '',
              'sourceType': 'RELAY',
              'sourceName': 'Relay',
            },
          ],
        },
      ],
    });

    final group = feature.modelSelectionGroups.single;
    expect(group.key, 'documentModel');
    expect(group.capabilities, ['TEXT_GENERATION', 'VISION']);
    expect(
      group.options.single.deployments['VISION'],
      'gpt-5.6-sol-vision',
    );
    expect(
      feature.modelOption('TEXT_GENERATION', 'gpt-5.6-sol-text')?.sourceName,
      'Relay',
    );
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

  test('deleted input and output assets remain readable in history models', () {
    final deletedAsset = {
      'id': 'asset-1',
      'name': 'reference.png',
      'mediaType': 'image/png',
      'sizeBytes': 2048,
      'createdAt': '2026-07-24T00:00:00Z',
      'origin': 'USER_UPLOAD',
      'category': 'IMAGE',
      'status': 'DELETED',
      'available': false,
      'associatedTaskCount': 1,
      'latestTaskTitle': '商品主图',
    };
    final run = RunView.fromJson({
      'id': 'run-1',
      'taskId': 'task-1',
      'runNumber': 2,
      'status': 'SUCCEEDED',
      'parameters': {'prompt': '保留原提示词'},
      'inputAssetIds': ['asset-1'],
      'inputAssets': [deletedAsset],
      'selectedModels': const <String, String>{},
      'createdAt': '2026-07-24T00:00:00Z',
    });
    final artifact = ArtifactView.fromJson({
      'id': 'artifact-1',
      'taskId': 'task-1',
      'runId': 'run-1',
      'versionNumber': 1,
      'kind': 'image',
      'title': '生成结果',
      'mimeType': 'image/png',
      'content': {'assetId': 'asset-1'},
      'metadata': const <String, Object?>{},
      'assets': [deletedAsset],
      'createdAt': '2026-07-24T00:00:00Z',
    });

    expect(run.inputAssets.single.available, isFalse);
    expect(run.inputAssets.single.name, 'reference.png');
    expect(artifact.assets.single.available, isFalse);
    expect(artifact.assets.single.status, 'DELETED');
  });

  test('asset preview descriptor exposes Office text fallback state', () {
    final preview = AssetPreviewDescriptor.fromJson({
      'kind': 'TEXT',
      'mediaType': 'text/plain',
      'contentUrl': null,
      'text': 'Extracted Office text',
      'truncated': false,
      'fallback': true,
    });
    final legacyPreview = AssetPreviewDescriptor.fromJson({
      'kind': 'PDF',
      'mediaType': 'application/pdf',
      'contentUrl': '/api/v1/assets/asset-1/preview/content',
      'text': null,
      'truncated': false,
    });

    expect(preview.fallback, isTrue);
    expect(legacyPreview.fallback, isFalse);
  });

  test('asset preview descriptor parses structured spreadsheet rows', () {
    final preview = AssetPreviewDescriptor.fromJson({
      'kind': 'SPREADSHEET',
      'mediaType':
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'contentUrl': '/api/v1/assets/asset-1/preview/content',
      'text': null,
      'truncated': false,
      'fallback': false,
      'spreadsheet': {
        'truncated': false,
        'sheets': [
          {
            'name': '销售',
            'headerRowNumber': 2,
            'columns': ['地区', '销售额'],
            'truncated': false,
            'rows': [
              {
                'rowNumber': 4,
                'cells': ['华东', '42'],
              },
            ],
          },
        ],
      },
    });

    expect(preview.spreadsheet?.sheets.single.name, '销售');
    expect(preview.spreadsheet?.sheets.single.headerRowNumber, 2);
    expect(preview.spreadsheet?.sheets.single.rows.single.rowNumber, 4);
    expect(
      preview.spreadsheet?.sheets.single.rows.single.cells,
      ['华东', '42'],
    );
  });
}
