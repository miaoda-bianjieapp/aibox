import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/run_output_models.dart';
import 'package:yuanzuo_ai/app/widgets/streaming_output_view.dart';

void main() {
  testWidgets('advances one Unicode grapheme per fast playback tick',
      (tester) async {
    var snapshot = _snapshot(
      content: '',
      updateType: RunOutputUpdateType.started,
    );
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return StreamingOutputView(
                snapshot: snapshot,
                phase: StreamingOutputPhase.streaming,
              );
            },
          ),
        ),
      ),
    );

    update(() {
      snapshot = _snapshot(
        content: 'A👨‍👩‍👧‍👦B',
        format: 'plain_text',
        sequence: 2,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 3));
    expect(_visiblePlainText(tester), '');

    await tester.pump(const Duration(milliseconds: 1));
    expect(_visiblePlainText(tester), 'A');
    await tester.pump(const Duration(milliseconds: 3));
    expect(_visiblePlainText(tester), 'A');
    await tester.pump(const Duration(milliseconds: 1));
    expect(_visiblePlainText(tester), 'A👨‍👩‍👧‍👦');
    await tester.pump(const Duration(milliseconds: 4));
    expect(_visiblePlainText(tester), 'A👨‍👩‍👧‍👦B');
    expect(
      find.byKey(const ValueKey('streaming-output-cursor')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('streaming-output-indicator-slot')),
      findsNothing,
    );
  });

  testWidgets('throttles markdown while keeping the latest complete string',
      (tester) async {
    var snapshot = _snapshot(
      content: '',
      updateType: RunOutputUpdateType.started,
    );
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return StreamingOutputView(
                snapshot: snapshot,
                phase: StreamingOutputPhase.streaming,
              );
            },
          ),
        ),
      ),
    );

    update(() {
      snapshot = _snapshot(
        content: '**加',
        sequence: 2,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await _pumpMarkdown(tester, '**加');

    update(() {
      snapshot = _snapshot(
        content: '**加粗**',
        sequence: 3,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 35));
    expect(find.text('加粗'), findsNothing);
    await tester.pump(const Duration(milliseconds: 1));
    expect(find.text('加粗'), findsOneWidget);
    expect(find.text('**加粗**'), findsNothing);
  });

  testWidgets('applies replace progress immediately without throttling',
      (tester) async {
    var snapshot = _snapshot(
      content: '正在识别第 1-4 页',
      format: 'text',
      updateType: RunOutputUpdateType.replace,
    );
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return StreamingOutputView(
                snapshot: snapshot,
                phase: StreamingOutputPhase.streaming,
              );
            },
          ),
        ),
      ),
    );

    expect(_visiblePlainText(tester), '正在识别第 1-4 页');
    update(() {
      snapshot = _snapshot(
        content: '正在识别第 5-8 页',
        format: 'text',
        sequence: 2,
        updateType: RunOutputUpdateType.replace,
      );
    });
    await tester.pump();

    expect(_visiblePlainText(tester), '正在识别第 5-8 页');
  });

  testWidgets('promotes a completed code fence into a highlighted stable card',
      (tester) async {
    var snapshot = _snapshot(
      content: '',
      updateType: RunOutputUpdateType.started,
    );
    var phase = StreamingOutputPhase.streaming;
    var settled = false;
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return StreamingOutputView(
                snapshot: snapshot,
                phase: phase,
                onSettled: () => settled = true,
              );
            },
          ),
        ),
      ),
    );

    const draft = '''
下面是代码：

```java
public class Main {''';
    update(() {
      snapshot = _snapshot(
        content: '下面是代码：\n\n```ja',
        sequence: 2,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await _pumpMarkdown(tester, '下面是代码：\n\n```ja');
    update(() {
      snapshot = _snapshot(
        content: draft,
        sequence: 3,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await _pumpMarkdown(tester, 'va\npublic class Main {');

    expect(
      find.byKey(const ValueKey('streaming-code-draft')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('syntax-highlighted-code-0')),
      findsNothing,
    );
    expect(find.text('Java'), findsOneWidget);
    expect(find.text('生成中'), findsOneWidget);
    final draftTop = tester.getTopLeft(
      find.byKey(const ValueKey('streaming-code-draft')),
    );

    const completed = '''
下面是代码：

```java
public class Main {
  private String message = "Hello";
}
```
''';
    update(() {
      snapshot = _snapshot(
        content: completed,
        sequence: 4,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await _pumpPlayback(tester, completed.substring(draft.length));

    expect(find.text('Java'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('streaming-code-draft')),
      findsNothing,
    );
    expect(
      tester.getTopLeft(
        find.byKey(const ValueKey('markdown-code-block-0')),
      ),
      draftTop,
    );
    expect(
      _styledColors(_codeSpan(
        tester,
        const ValueKey('syntax-highlighted-code-0'),
      )),
      contains(const Color(0xFF8A3B8F)),
    );

    update(() => phase = StreamingOutputPhase.succeeded);
    await tester.pump();
    expect(
      find.byKey(const ValueKey('streaming-output-check')),
      findsNothing,
    );
    expect(settled, isFalse);

    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump();
    expect(settled, isTrue);
  });

  testWidgets('reuses stable markdown blocks while only the tail changes',
      (tester) async {
    var snapshot = _snapshot(
      content: '',
      updateType: RunOutputUpdateType.started,
    );
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return StreamingOutputView(
                snapshot: snapshot,
                phase: StreamingOutputPhase.streaming,
              );
            },
          ),
        ),
      ),
    );

    update(() {
      snapshot = _snapshot(
        content: '# 稳定标题\n\n活动段落',
        sequence: 2,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await _pumpMarkdown(tester, '# 稳定标题\n\n活动段落');

    final stableBlock = find.byKey(
      const ValueKey('streaming-markdown-stable-block-0'),
    );
    final before = tester.widget(stableBlock);
    expect(find.text('稳定标题'), findsOneWidget);
    expect(find.text('活动段落'), findsOneWidget);

    update(() {
      snapshot = _snapshot(
        content: '# 稳定标题\n\n活动段落继续增长',
        sequence: 3,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await _pumpMarkdown(tester, '继续增长');

    final after = tester.widget(stableBlock);
    expect(identical(before, after), isTrue);
    expect(find.text('活动段落继续增长'), findsOneWidget);
  });

  testWidgets('terminal playback drains at most eight graphemes per frame',
      (tester) async {
    var snapshot = _snapshot(
      content: '',
      updateType: RunOutputUpdateType.started,
    );
    var phase = StreamingOutputPhase.streaming;
    var playbackCompleted = false;
    var settled = false;
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return StreamingOutputView(
                snapshot: snapshot,
                phase: phase,
                onPlaybackCompleted: () => playbackCompleted = true,
                onSettled: () => settled = true,
              );
            },
          ),
        ),
      ),
    );

    update(() {
      snapshot = _snapshot(
        content: 'ABCDEFGHIJKL',
        format: 'plain_text',
        sequence: 2,
        updateType: RunOutputUpdateType.append,
      );
      phase = StreamingOutputPhase.succeeded;
    });
    await tester.pump();

    expect(
      find.byKey(const ValueKey('streaming-output-check')),
      findsNothing,
    );
    expect(playbackCompleted, isFalse);
    await tester.pump(const Duration(milliseconds: 16));
    expect(_visiblePlainText(tester), 'ABCDEFGH');
    expect(playbackCompleted, isFalse);

    await tester.pump(const Duration(milliseconds: 16));
    await tester.pump();
    expect(_visiblePlainText(tester), 'ABCDEFGHIJKL');
    expect(playbackCompleted, isTrue);
    expect(
      find.byKey(const ValueKey('streaming-output-check')),
      findsNothing,
    );
    expect(settled, isFalse);

    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump();
    expect(settled, isTrue);
  });

  testWidgets('flushes pending markdown in the terminal frame', (tester) async {
    var snapshot = _snapshot(
      content: '',
      updateType: RunOutputUpdateType.started,
    );
    var phase = StreamingOutputPhase.streaming;
    var playbackCompleted = false;
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return StreamingOutputView(
                snapshot: snapshot,
                phase: phase,
                onPlaybackCompleted: () => playbackCompleted = true,
              );
            },
          ),
        ),
      ),
    );

    update(() {
      snapshot = _snapshot(
        content: '**完成**',
        sequence: 2,
        updateType: RunOutputUpdateType.append,
      );
      phase = StreamingOutputPhase.succeeded;
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    await tester.pump();
    expect(
      find.byKey(const ValueKey('streaming-output-rich-text')),
      findsOneWidget,
    );
    expect(find.text('完成'), findsOneWidget);
    expect(playbackCompleted, isTrue);
    expect(
      find.byKey(const ValueKey('streaming-output-check')),
      findsNothing,
    );
  });

  testWidgets('uses 2ms single-grapheme playback above forty queued items',
      (tester) async {
    var snapshot = _snapshot(
      content: '',
      format: 'plain_text',
      updateType: RunOutputUpdateType.started,
    );
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return StreamingOutputView(
                snapshot: snapshot,
                phase: StreamingOutputPhase.streaming,
              );
            },
          ),
        ),
      ),
    );

    update(() {
      snapshot = _snapshot(
        content: List.filled(41, '字').join(),
        format: 'plain_text',
        sequence: 2,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 1));
    expect(_visiblePlainText(tester), '');
    await tester.pump(const Duration(milliseconds: 1));
    expect(_visiblePlainText(tester), '字');
  });

  testWidgets('uses four graphemes per frame above one hundred queued items',
      (tester) async {
    var snapshot = _snapshot(
      content: '',
      format: 'plain_text',
      updateType: RunOutputUpdateType.started,
    );
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return StreamingOutputView(
                snapshot: snapshot,
                phase: StreamingOutputPhase.streaming,
              );
            },
          ),
        ),
      ),
    );

    update(() {
      snapshot = _snapshot(
        content: List.generate(101, (index) => '${index % 10}').join(),
        format: 'plain_text',
        sequence: 2,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));

    expect(_visiblePlainText(tester), '0123');
  });

  testWidgets('stops immediately and discards buffered graphemes',
      (tester) async {
    var snapshot = _snapshot(
      content: '',
      format: 'plain_text',
      updateType: RunOutputUpdateType.started,
    );
    var stopRequested = false;
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return StreamingOutputView(
                snapshot: snapshot,
                phase: StreamingOutputPhase.streaming,
                stopRequested: stopRequested,
              );
            },
          ),
        ),
      ),
    );

    update(() {
      snapshot = _snapshot(
        content: 'ABCDEFGHIJKLMN',
        format: 'plain_text',
        sequence: 2,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 12));
    expect(_visiblePlainText(tester), 'ABC');

    update(() => stopRequested = true);
    await tester.pump();
    final frozenText = _visiblePlainText(tester);
    update(() {
      snapshot = _snapshot(
        content: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ',
        format: 'plain_text',
        sequence: 3,
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(frozenText, 'ABC');
    expect(_visiblePlainText(tester), frozenText);
  });
}

RunOutputSnapshot _snapshot({
  required String content,
  String format = 'markdown',
  int sequence = 1,
  RunOutputUpdateType updateType = RunOutputUpdateType.snapshot,
}) {
  return RunOutputSnapshot(
    runId: 'run-1',
    channel: 'main',
    format: format,
    content: content,
    status: 'STREAMING',
    lastSequence: sequence,
    updatedAt: DateTime(2026, 7, 30),
    updateType: updateType,
  );
}

String _visiblePlainText(WidgetTester tester) {
  final widget = tester.widget<SelectableText>(
    find.byKey(const ValueKey('streaming-output-visible-text')),
  );
  return widget.textSpan!.toPlainText(
    includeSemanticsLabels: false,
    includePlaceholders: false,
  );
}

Set<Color> _styledColors(InlineSpan span) {
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

Future<void> _pumpMarkdown(WidgetTester tester, String value) async {
  await tester.pump(
    Duration(milliseconds: value.characters.length * 4 + 32),
  );
  await tester.pump();
}

Future<void> _pumpPlayback(WidgetTester tester, String value) async {
  await tester.pump(
    Duration(milliseconds: value.characters.length * 4),
  );
  await tester.pump();
}
