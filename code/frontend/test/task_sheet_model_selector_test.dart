import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/widgets/task_sheet.dart';

void main() {
  testWidgets('meeting minutes model uses an expandable dropdown',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(360, 640));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var selectedCode = 'zhipu-glm-4-5-air-text';
    const policy = ModelPolicy(
      capability: 'TEXT_GENERATION',
      defaultModelCode: 'zhipu-glm-4-5-air-text',
      allowUserSelection: true,
      options: [
        ModelOption(
          code: 'zhipu-glm-4-5-air-text',
          displayName: 'GLM-4.5-Air',
          description: '推荐，会议纪要输出稳定。',
          isDefault: true,
          sourceType: 'OFFICIAL',
          sourceName: '智谱官方',
          maxReferenceImages: null,
        ),
        ModelOption(
          code: 'codex2api-gpt-5-4-mini-text',
          displayName: 'GPT-5.4 Mini',
          description: '实验选项，复杂纪要可能超时。',
          isDefault: false,
          sourceType: 'RELAY',
          sourceName: 'Codex2API Relay',
          maxReferenceImages: null,
        ),
        ModelOption(
          code: 'future-text-model',
          displayName: '未来文本模型',
          description: '用于验证后续模型可以继续加入下拉列表。',
          isDefault: false,
          sourceType: 'OFFICIAL',
          sourceName: '未来厂商',
          maxReferenceImages: null,
        ),
      ],
    );

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: StatefulBuilder(
          builder: (context, setState) => TaskModelSelector(
            policy: policy,
            selectedCode: selectedCode,
            options: const {
              'widget': 'dropdown',
            },
            enabled: true,
            onSelected: (value) => setState(() => selectedCode = value),
          ),
        ),
      ),
    ));

    expect(find.byType(DropdownButtonFormField<String>), findsOneWidget);
    expect(find.text('GLM-4.5-Air'), findsOneWidget);
    expect(find.text('GPT-5.4 Mini'), findsNothing);
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('GLM-4.5-Air'));
    await tester.pumpAndSettle();
    expect(find.text('GPT-5.4 Mini'), findsOneWidget);
    expect(find.text('未来文本模型'), findsOneWidget);

    await tester.tap(find.text('GPT-5.4 Mini'));
    await tester.pumpAndSettle();

    expect(selectedCode, 'codex2api-gpt-5-4-mini-text');
    final selector = tester.widget<DropdownButtonFormField<String>>(
      find.byType(DropdownButtonFormField<String>),
    );
    expect(selector.initialValue, 'codex2api-gpt-5-4-mini-text');
    expect(tester.takeException(), isNull);
  });

  testWidgets('switching TTS model normalizes only incompatible voice',
      (tester) async {
    final feature = _ttsFeature();
    var selectedCode = 'openai2api-index-tts2-tts';
    final textController = TextEditingController(text: '保留这段文字');
    var values = <String, Object?>{
      'text': textController.text,
      'voice': 'science_female',
      'speed': 1.25,
      'emotion': 'natural',
    };
    addTearDown(textController.dispose);

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: StatefulBuilder(
          builder: (context, setState) {
            final selectedModels = {'TEXT_TO_SPEECH': selectedCode};
            final voiceValues = feature.enumValuesFor('voice', selectedModels);
            return Column(
              children: [
                TextField(controller: textController),
                TaskModelSelector(
                  policy: feature.modelPolicies.single,
                  selectedCode: selectedCode,
                  options: const {'widget': 'dropdown'},
                  enabled: true,
                  onSelected: (value) {
                    setState(() {
                      selectedCode = value;
                      values = feature.normalizedEnumValues(
                        {'TEXT_TO_SPEECH': selectedCode},
                        values,
                      );
                    });
                  },
                ),
                DropdownButtonFormField<String>(
                  key: const Key('voice-field'),
                  value: values['voice'] as String?,
                  items: voiceValues
                      .map((value) => DropdownMenuItem<String>(
                            value: value,
                            child: Text(value),
                          ))
                      .toList(),
                  onChanged: (_) {},
                ),
                Text('speed:${values['speed']}'),
                Text('emotion:${values['emotion']}'),
              ],
            );
          },
        ),
      ),
    ));

    expect(find.text('IndexTTS2'), findsOneWidget);
    expect(find.text('science_female'), findsOneWidget);
    expect(textController.text, '保留这段文字');

    await tester.tap(find.text('IndexTTS2'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('GPT-SoVITS').last);
    await tester.pumpAndSettle();

    expect(find.text('GPT-SoVITS'), findsOneWidget);
    expect(find.text('science_female'), findsNothing);
    expect(find.text('gentle_female'), findsOneWidget);
    expect(textController.text, '保留这段文字');
    expect(find.text('speed:1.25'), findsOneWidget);
    expect(find.text('emotion:natural'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

FeatureDetail _ttsFeature() => FeatureDetail.fromJson({
      'code': 'audio.text_to_speech',
      'displayName': '文字转语音',
      'description': '',
      'version': 1,
      'resultType': 'audio',
      'rendererKey': 'audio',
      'executionMode': 'ASYNC',
      'inputSchema': {
        'type': 'object',
        'properties': {
          'text': {'type': 'string'},
          'voice': {
            'type': 'string',
            'enum': ['science_female', 'gentle_female'],
          },
          'speed': {
            'type': 'number',
            'minimum': 0.5,
            'maximum': 2.0,
            'multipleOf': 0.05,
            'default': 1.0,
          },
          'emotion': {
            'type': 'string',
            'enum': ['natural'],
          },
        },
      },
      'uiSchema': {
        'widgets': {'speed': 'slider'},
        'fieldOptions': {
          'speed': {
            'step': 0.05,
            'suffix': '×',
            'decimalPlaces': 2,
            'minimumFractionDigits': 1,
          },
        },
      },
      'outputSchema': const <String, Object?>{},
      'config': const <String, Object?>{},
      'modelPolicies': [
        {
          'capability': 'TEXT_TO_SPEECH',
          'defaultModelCode': 'openai2api-gpt-sovits-v2-tts',
          'allowUserSelection': true,
          'options': [
            {
              'code': 'openai2api-gpt-sovits-v2-tts',
              'displayName': 'GPT-SoVITS',
              'description': '',
              'isDefault': true,
              'sourceType': 'RELAY',
              'sourceName': 'Relay',
              'parameterOptions': {
                'voice': ['gentle_female'],
              },
            },
            {
              'code': 'openai2api-index-tts2-tts',
              'displayName': 'IndexTTS2',
              'description': '',
              'isDefault': false,
              'sourceType': 'RELAY',
              'sourceName': 'Relay',
              'parameterOptions': {
                'voice': ['science_female', 'gentle_female'],
              },
            },
          ],
        },
      ],
    });
