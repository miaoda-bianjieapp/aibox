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

  testWidgets('renders streaming markdown and code cards without highlighting',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: MarkdownOutputView(
            renderMode: MarkdownRenderMode.streaming,
            markdown: '''
```mermaid
graph TD
  A --> B
```

![外部图](https://example.com/streaming.png)
''',
          ),
        ),
      ),
    );
    await tester.pump();

    expect(find.text('Mermaid'), findsOneWidget);
    expect(find.byKey(const ValueKey('copy-code-block-0')), findsOneWidget);
    expect(
      _syntaxColors(_codeSpan(
        tester,
        const ValueKey('syntax-highlighted-code-0'),
      )),
      {const Color(0xFF14201D)},
    );
    expect(find.textContaining('外部图片已隐藏'), findsOneWidget);
    expect(find.byType(Image), findsNothing);
  });

  testWidgets('highlights Java and Python syntax in completed code blocks',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: MarkdownOutputView(
            markdown: '''
```java
public class Main {
  private String greeting = "Hello";
}
```

```python
def greet(name):
    return f"Hello {name}"
```
''',
          ),
        ),
      ),
    );
    await tester.pump();

    final java = tester.widget<SelectableText>(
      find.byKey(const ValueKey('syntax-highlighted-code-0')),
    );
    final python = tester.widget<SelectableText>(
      find.byKey(const ValueKey('syntax-highlighted-code-1')),
    );

    expect(
      _syntaxColors(java.textSpan!),
      containsAll(
        {
          const Color(0xFF8A3B8F),
          const Color(0xFF1769AA),
          const Color(0xFF4F772D),
        },
      ),
    );
    expect(
      _syntaxColors(python.textSpan!),
      containsAll(
        {
          const Color(0xFF8A3B8F),
          const Color(0xFF4F772D),
        },
      ),
    );
  });
}

Set<Color> _syntaxColors(InlineSpan span) {
  final colors = <Color>{};

  void collect(InlineSpan current) {
    if (current is! TextSpan) return;
    final color = current.style?.color;
    if (color != null) colors.add(color);
    for (final child in current.children ?? const <InlineSpan>[]) {
      collect(child);
    }
  }

  collect(span);
  return colors;
}

InlineSpan _codeSpan(WidgetTester tester, Key key) {
  final widget = tester.widget(find.byKey(key));
  return switch (widget) {
    SelectableText selectable => selectable.textSpan!,
    Text text => text.textSpan!,
    _ => throw StateError('Unexpected code widget ${widget.runtimeType}'),
  };
}
