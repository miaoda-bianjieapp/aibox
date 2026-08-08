import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/pages/video_generate_page.dart';

void main() {
  testWidgets(
    'submitting a character asset waits for the dialog to dispose controllers',
    (tester) async {
      AssetDraft? submitted;
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: FilledButton(
              onPressed: () async {
                submitted = await showDialog<AssetDraft>(
                  context: context,
                  builder: (context) =>
                      const AssetDraftDialog(hasProject: false),
                );
              },
              child: const Text('打开资产弹窗'),
            ),
          ),
        ),
      ));

      await tester.tap(find.text('打开资产弹窗'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const ValueKey<String>('asset-draft-name')),
        '角色甲',
      );
      await tester.enterText(
        find.byKey(const ValueKey<String>('asset-draft-description')),
        '黑色短发，深色外套',
      );
      await tester.enterText(
        find.byKey(const ValueKey<String>('asset-draft-personality')),
        '沉着冷静',
      );
      await tester.tap(
        find.byKey(const ValueKey<String>('asset-draft-submit')),
      );
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(submitted?.scope, 'GLOBAL');
      expect(submitted?.assetType, 'CHARACTER');
      expect(submitted?.name, '角色甲');
      expect(find.byType(AssetDraftDialog), findsNothing);
    },
  );
}
