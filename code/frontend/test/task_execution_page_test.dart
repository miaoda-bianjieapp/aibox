import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/models/run_output_models.dart';
import 'package:yuanzuo_ai/app/network/task_execution_result.dart';
import 'package:yuanzuo_ai/app/pages/task_execution_page.dart';

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

  testWidgets('renders accumulated streaming markdown character by character',
      (tester) async {
    var cancelledRunId = '';
    final controller = TaskExecutionController(
      initialStatus: '正在创建任务',
      onCancelRun: (runId) async {
        cancelledRunId = runId;
      },
      loadRunOutput: (runId) async => [
        RunOutputSnapshot(
          runId: runId,
          channel: 'main',
          format: 'markdown',
          content: '# 流式标题\n\n第一段内容',
          status: 'STREAMING',
          lastSequence: 2,
          updatedAt: DateTime(2026, 7, 21),
          updateType: RunOutputUpdateType.append,
        ),
      ],
    );

    await tester.pumpWidget(MaterialApp(
      home: TaskExecutionPage(
        title: '文章生成',
        controller: controller,
        openResult: true,
        resultRouteBuilder: (_) => MaterialPageRoute<void>(
          builder: (context) => const Scaffold(),
        ),
      ),
    ));

    controller.attachRun('run-1');
    controller.updateStatus('正在执行');
    await tester.pump();
    await tester.pump();
    await _pumpMarkdown(tester, '# 流式标题\n\n第一段内容');

    expect(find.text('文章生成'), findsOneWidget);
    expect(find.text('流式标题'), findsOneWidget);
    expect(find.text('第一段内容'), findsOneWidget);
    expect(find.text('# 流式标题'), findsNothing);
    expect(find.text('正在执行'), findsNothing);
    expect(find.text('正在思考…'), findsNothing);
    expect(find.text('停止生成'), findsOneWidget);
    expect(find.byTooltip('复制全文'), findsOneWidget);
    final scrollView = tester.widget<SingleChildScrollView>(
      find.byKey(const ValueKey('task-execution-scroll-view')),
    );
    expect(
      scrollView.padding,
      const EdgeInsets.fromLTRB(20, 18, 20, 0),
    );
    expect(
      find.byKey(const ValueKey('streaming-output-indicator-slot')),
      findsNothing,
    );

    await tester.tap(find.byTooltip('复制全文'));
    await tester.pump();
    final clipboard = await Clipboard.getData(Clipboard.kTextPlain);
    expect(clipboard?.text, '# 流式标题\n\n第一段内容');

    await tester.tap(find.text('停止生成'));
    await tester.pump();
    expect(cancelledRunId, 'run-1');
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('stop action freezes buffered output immediately',
      (tester) async {
    final cancelCompleter = Completer<void>();
    final controller = TaskExecutionController(
      initialStatus: '正在执行',
      onCancelRun: (_) => cancelCompleter.future,
      loadRunOutput: (_) async => const [],
    );
    await tester.pumpWidget(MaterialApp(
      home: TaskExecutionPage(
        title: '文章生成',
        controller: controller,
        openResult: true,
        resultRouteBuilder: (_) => MaterialPageRoute<void>(
          builder: (context) => const Scaffold(),
        ),
      ),
    ));

    controller.attachRun('run-stop');
    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-stop',
      channel: 'main',
      format: 'plain_text',
      content: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ',
      status: 'STREAMING',
      lastSequence: 1,
      updatedAt: DateTime(2026, 7, 31),
      updateType: RunOutputUpdateType.append,
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 12));
    expect(_visiblePlainOutput(tester), 'ABC');

    await tester.tap(find.text('停止生成'));
    await tester.pump();
    final frozenText = _visiblePlainOutput(tester);
    expect(find.text('正在取消'), findsOneWidget);

    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-stop',
      channel: 'main',
      format: 'plain_text',
      content: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789',
      status: 'STREAMING',
      lastSequence: 2,
      updatedAt: DateTime(2026, 7, 31),
      updateType: RunOutputUpdateType.append,
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(frozenText, 'ABC');
    expect(_visiblePlainOutput(tester), frozenText);

    cancelCompleter.complete();
    await tester.pump();
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('keeps the stop action fixed while streaming content grows',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(400, 600));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    final controller = TaskExecutionController(
      initialStatus: '正在执行',
      onCancelRun: (_) async {},
      loadRunOutput: (_) async => const [],
    );
    await tester.pumpWidget(MaterialApp(
      home: TaskExecutionPage(
        title: '文章生成',
        controller: controller,
        openResult: true,
        resultRouteBuilder: (_) => MaterialPageRoute<void>(
          builder: (context) => const Scaffold(),
        ),
      ),
    ));

    final actionBar = find.byKey(
      const ValueKey('task-execution-action-bar'),
    );
    final initialRect = tester.getRect(actionBar);

    final baseContent = List.generate(80, (index) => 'Line $index').join('\n');
    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-fixed-action',
      channel: 'main',
      format: 'plain_text',
      content: baseContent,
      status: 'STREAMING',
      lastSequence: 1,
      updatedAt: DateTime(2026, 7, 30),
      updateType: RunOutputUpdateType.replace,
    ));
    await tester.pump();
    await tester.pump();

    expect(tester.getRect(actionBar), initialRect);

    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-fixed-action',
      channel: 'main',
      format: 'plain_text',
      content: '$baseContent\n${List.filled(600, '字').join()}',
      status: 'STREAMING',
      lastSequence: 2,
      updatedAt: DateTime(2026, 7, 30),
      updateType: RunOutputUpdateType.append,
    ));
    await tester.pump();
    for (var index = 0; index < 12; index++) {
      await tester.pump(const Duration(milliseconds: 28));
      expect(tester.getRect(actionBar), initialRect);
    }
  });

  testWidgets('shows failures and lets the user return to edit inputs',
      (tester) async {
    final controller = TaskExecutionController(
      initialStatus: '正在创建任务',
      onCancelRun: (_) async {},
      loadRunOutput: (_) async => const [],
    );

    await tester.pumpWidget(MaterialApp(
      home: TaskExecutionPage(
        title: '文章生成',
        controller: controller,
        openResult: true,
        resultRouteBuilder: (_) => MaterialPageRoute<void>(
          builder: (context) => const Scaffold(),
        ),
      ),
    ));
    controller.fail('模型服务暂时不可用');
    await tester.pump();

    expect(find.text('模型服务暂时不可用'), findsOneWidget);
    expect(find.text('返回修改'), findsOneWidget);
  });

  testWidgets('does not show copy action for progress-only text',
      (tester) async {
    final controller = TaskExecutionController(
      initialStatus: '正在执行',
      onCancelRun: (_) async {},
      loadRunOutput: (_) async => const [],
    );

    await tester.pumpWidget(MaterialApp(
      home: TaskExecutionPage(
        title: '表格与信息提取',
        controller: controller,
        openResult: true,
        resultRouteBuilder: (_) => MaterialPageRoute<void>(
          builder: (context) => const Scaffold(),
        ),
      ),
    ));

    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-progress',
      channel: 'main',
      format: 'text',
      content: '正在识别第 9-12 页（2/2）',
      status: 'STREAMING',
      lastSequence: 1,
      updatedAt: DateTime(2026, 7, 27),
      updateType: RunOutputUpdateType.replace,
    ));
    await tester.pump();

    expect(_visiblePlainOutput(tester), '正在识别第 9-12 页（2/2）');
    expect(find.text('正在执行'), findsOneWidget);
    expect(find.text('正在思考…'), findsNothing);
    expect(find.byTooltip('复制全文'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
      'follows streaming output until the user scrolls away and resumes at the bottom',
      (tester) async {
    await tester.binding.setSurfaceSize(const Size(400, 600));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    final controller = TaskExecutionController(
      initialStatus: '正在执行',
      onCancelRun: (_) async {},
      loadRunOutput: (_) async => const [],
    );
    await tester.pumpWidget(MaterialApp(
      home: TaskExecutionPage(
        title: '文章生成',
        controller: controller,
        openResult: true,
        resultRouteBuilder: (_) => MaterialPageRoute<void>(
          builder: (context) => const Scaffold(),
        ),
      ),
    ));

    final firstContent =
        List.generate(80, (index) => 'Line ${index + 1}').join('\n');
    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-scroll',
      channel: 'main',
      format: 'plain_text',
      content: firstContent,
      status: 'STREAMING',
      lastSequence: 1,
      updatedAt: DateTime(2026, 7, 22),
      updateType: RunOutputUpdateType.replace,
    ));
    await tester.pump();
    await tester.pump();

    final scrollView = find.byKey(
      const ValueKey('task-execution-scroll-view'),
    );
    final scrollController =
        tester.widget<SingleChildScrollView>(scrollView).controller!;
    expect(
      scrollController.position.extentAfter,
      lessThanOrEqualTo(40),
    );

    final initialOffset = scrollController.offset;
    final secondContent = '$firstContent\n'
        '${List.generate(20, (index) => 'New line ${index + 1}').join('\n')}';
    final drag = scrollController.position.drag(
      DragStartDetails(globalPosition: Offset.zero),
      () {},
    );
    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-scroll',
      channel: 'main',
      format: 'plain_text',
      content: secondContent,
      status: 'STREAMING',
      lastSequence: 2,
      updatedAt: DateTime(2026, 7, 22),
      updateType: RunOutputUpdateType.replace,
    ));
    await tester.pump();
    await tester.pump();

    expect(scrollController.offset, closeTo(initialOffset, 1));
    expect(scrollController.position.extentAfter, greaterThan(24));

    drag.update(DragUpdateDetails(
      globalPosition: const Offset(0, 260),
      delta: const Offset(0, 260),
      primaryDelta: 260,
    ));
    drag.end(DragEndDetails(
      velocity: Velocity.zero,
      primaryVelocity: 0,
    ));
    await tester.pump();
    final pausedOffset = scrollController.offset;
    expect(pausedOffset, lessThan(scrollController.position.maxScrollExtent));

    final thirdContent = '$secondContent\n'
        '${List.generate(20, (index) => 'Later line ${index + 1}').join('\n')}';
    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-scroll',
      channel: 'main',
      format: 'plain_text',
      content: thirdContent,
      status: 'STREAMING',
      lastSequence: 3,
      updatedAt: DateTime(2026, 7, 22),
      updateType: RunOutputUpdateType.replace,
    ));
    await tester.pump();
    await tester.pump();

    expect(scrollController.offset, closeTo(pausedOffset, 1));
    expect(scrollController.position.extentAfter, greaterThan(24));

    final resumeDrag = scrollController.position.drag(
      DragStartDetails(globalPosition: const Offset(0, 260)),
      () {},
    );
    resumeDrag.update(DragUpdateDetails(
      globalPosition: const Offset(0, -5000),
      delta: const Offset(0, -5000),
      primaryDelta: -5000,
    ));
    resumeDrag.end(DragEndDetails(
      velocity: Velocity.zero,
      primaryVelocity: 0,
    ));
    await tester.pump(const Duration(milliseconds: 200));
    await tester.pump();
    expect(scrollController.position.extentAfter, lessThanOrEqualTo(40));

    final streamedTail =
        List.generate(12, (index) => 'Streamed line ${index + 1}').join('\n');
    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-scroll',
      channel: 'main',
      format: 'plain_text',
      content: '$thirdContent\n$streamedTail',
      status: 'STREAMING',
      lastSequence: 4,
      updatedAt: DateTime(2026, 7, 22),
      updateType: RunOutputUpdateType.append,
    ));
    await tester.pump();
    await _pumpTextFrameByFrame(tester, '\n$streamedTail');
    await tester.pump();

    expect(scrollController.position.extentAfter, lessThanOrEqualTo(40));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('waits for terminal playback and completion delay before routing',
      (tester) async {
    final controller = TaskExecutionController(
      initialStatus: '正在执行',
      onCancelRun: (_) async {},
      loadRunOutput: (_) async => const [],
    );
    await tester.pumpWidget(MaterialApp(
      home: TaskExecutionPage(
        title: '文章生成',
        controller: controller,
        openResult: true,
        resultRouteBuilder: (_) => MaterialPageRoute<void>(
          builder: (context) => const Scaffold(
            body: Text('成果页面'),
          ),
        ),
      ),
    ));

    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-complete',
      channel: 'main',
      format: 'plain_text',
      content: 'ABCDEFGHIJKL',
      status: 'STREAMING',
      lastSequence: 1,
      updatedAt: DateTime(2026, 7, 30),
      updateType: RunOutputUpdateType.append,
    ));
    await tester.pump();

    controller.complete(_result());
    await tester.pump();

    expect(find.text('正在完成'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('streaming-output-check')),
      findsNothing,
    );
    expect(find.text('成果页面'), findsNothing);

    await tester.pump(const Duration(milliseconds: 16));
    expect(_visiblePlainOutput(tester), 'ABCDEFGH');
    expect(find.text('正在完成'), findsOneWidget);
    expect(find.text('生成完成'), findsNothing);

    await tester.pump(const Duration(milliseconds: 16));
    await tester.pump();
    expect(_visiblePlainOutput(tester), 'ABCDEFGHIJKL');
    expect(find.text('生成完成'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('streaming-output-check')),
      findsNothing,
    );

    await tester.pump(const Duration(milliseconds: 299));
    expect(find.text('成果页面'), findsNothing);

    await tester.pump(const Duration(milliseconds: 1));
    await tester.pump();
    await tester.pumpAndSettle();
    expect(find.text('成果页面'), findsOneWidget);
  });

  testWidgets('keeps partial output visible when execution fails',
      (tester) async {
    final controller = TaskExecutionController(
      initialStatus: '正在执行',
      onCancelRun: (_) async {},
      loadRunOutput: (_) async => const [],
    );
    await tester.pumpWidget(MaterialApp(
      home: TaskExecutionPage(
        title: '文章生成',
        controller: controller,
        openResult: true,
        resultRouteBuilder: (_) => MaterialPageRoute<void>(
          builder: (context) => const Scaffold(),
        ),
      ),
    ));

    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-failed',
      channel: 'main',
      format: 'markdown',
      content: '已经生成的部分',
      status: 'STREAMING',
      lastSequence: 1,
      updatedAt: DateTime(2026, 7, 30),
      updateType: RunOutputUpdateType.append,
    ));
    await tester.pump();
    for (var index = 0; index < 8; index++) {
      await tester.pump(const Duration(milliseconds: 28));
    }
    controller.fail('模型连接中断');
    await tester.pump();
    for (var index = 0; index < 8; index++) {
      await tester.pump(const Duration(milliseconds: 28));
    }

    expect(find.text('模型连接中断'), findsOneWidget);
    expect(find.text('已经生成的部分'), findsOneWidget);
    expect(find.text('返回修改'), findsOneWidget);
  });
}

String _visiblePlainOutput(WidgetTester tester) {
  final widget = tester.widget<SelectableText>(
    find.byKey(const ValueKey('streaming-output-visible-text')),
  );
  return widget.textSpan!.toPlainText(
    includeSemanticsLabels: false,
    includePlaceholders: false,
  );
}

TaskExecutionResult _result() => TaskExecutionResult(
      taskId: 'task-1',
      runId: 'run-complete',
      feature: const FeatureDetail(
        id: 'writing.draft',
        title: '文章生成',
        description: '',
        version: 1,
        resultType: 'markdown',
        rendererKey: 'writing',
        executionMode: 'ASYNC',
        inputSchema: {},
        uiSchema: {},
        outputSchema: {},
        config: {},
        modelPolicies: [],
      ),
      artifact: ArtifactView(
        id: 'artifact-1',
        taskId: 'task-1',
        runId: 'run-complete',
        parentArtifactId: null,
        versionNumber: 1,
        kind: 'markdown',
        title: '结果',
        mimeType: 'text/markdown',
        content: const {'text': '完成'},
        metadata: const {},
        createdAt: DateTime(2026, 7, 30),
      ),
    );

Future<void> _pumpMarkdown(WidgetTester tester, String value) async {
  await tester.pump(
    Duration(milliseconds: value.characters.length * 4 + 32),
  );
  await tester.pump();
}

Future<void> _pumpTextFrameByFrame(
  WidgetTester tester,
  String value,
) async {
  for (var index = 0; index < value.characters.length; index++) {
    await tester.pump(const Duration(milliseconds: 4));
  }
}
