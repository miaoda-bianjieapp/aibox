import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/widgets/markdown_output_view.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  String? clipboardText;

  setUp(() {
    clipboardText = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        clipboardText =
            (call.arguments as Map<Object?, Object?>)['text'] as String?;
        return null;
      }
      if (call.method == 'Clipboard.getData') {
        return clipboardText == null
            ? null
            : <String, Object?>{'text': clipboardText};
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null);
  });

  testWidgets('renders markdown content and hides arbitrary remote images',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: MarkdownOutputView(
            markdown: '''
# 标题

`inline code`

![示例](https://example.com/private.png)
''',
          ),
        ),
      ),
    );
    await tester.pump();

    expect(find.text('标题'), findsOneWidget);
    expect(find.textContaining('外部图片已隐藏'), findsOneWidget);
    expect(find.byType(Image), findsNothing);
  });

  testWidgets('copies fenced code and labels Mermaid as source code',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: MarkdownOutputView(
            markdown: '''
```dart
void main() {
  print('hello');
}
```

```mermaid
graph TD
  A --> B
```
''',
          ),
        ),
      ),
    );
    await tester.pump();

    expect(find.text('Dart'), findsOneWidget);
    expect(find.text('Mermaid'), findsOneWidget);
    expect(find.byKey(const ValueKey('copy-code-block-0')), findsOneWidget);
    expect(find.byKey(const ValueKey('copy-code-block-1')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('copy-code-block-0')));
    await tester.pump(const Duration(milliseconds: 10));

    expect(find.text('已复制'), findsOneWidget);
    final clipboard = await Clipboard.getData(Clipboard.kTextPlain);
    expect(
      clipboard?.text,
      "void main() {\n  print('hello');\n}",
    );
  });

  testWidgets('disables copy while a streaming code fence is incomplete',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: MarkdownOutputView(
            streaming: true,
            markdown: '''
```mermaid
graph TD
  A --> B
''',
          ),
        ),
      ),
    );
    await tester.pump();

    final button = tester.widget<TextButton>(
      find.byKey(const ValueKey('copy-code-block-0')),
    );
    expect(button.onPressed, isNull);
    expect(find.text('生成中'), findsOneWidget);
  });
}
