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
}
