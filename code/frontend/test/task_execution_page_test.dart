import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/run_output_models.dart';
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

  testWidgets('renders streaming markdown on the second-level execution page',
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

    expect(find.text('文章生成'), findsOneWidget);
    expect(find.text('流式标题'), findsOneWidget);
    expect(find.text('第一段内容'), findsOneWidget);
    expect(find.text('停止生成'), findsOneWidget);
    expect(find.byTooltip('复制全文'), findsOneWidget);

    await tester.tap(find.byTooltip('复制全文'));
    await tester.pump();
    final clipboard = await Clipboard.getData(Clipboard.kTextPlain);
    expect(clipboard?.text, '# 流式标题\n\n第一段内容');

    await tester.tap(find.text('停止生成'));
    await tester.pump();
    expect(cancelledRunId, 'run-1');
    await tester.pumpWidget(const SizedBox());
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

    controller.updateOutput(RunOutputSnapshot(
      runId: 'run-scroll',
      channel: 'main',
      format: 'plain_text',
      content: '$thirdContent\nLast streamed line',
      status: 'STREAMING',
      lastSequence: 4,
      updatedAt: DateTime(2026, 7, 22),
    ));
    await tester.pump();
    await tester.pump();

    expect(scrollController.position.extentAfter, lessThanOrEqualTo(40));
    await tester.pumpWidget(const SizedBox());
  });
}
