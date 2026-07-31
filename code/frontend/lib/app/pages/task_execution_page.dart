import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/run_output_models.dart';
import '../network/task_execution_result.dart';
import '../theme/app_theme.dart';
import '../widgets/streaming_output_view.dart';

class TaskExecutionController extends ChangeNotifier {
  TaskExecutionController({
    required this.initialStatus,
    required this.onCancelRun,
    required this.loadRunOutput,
  }) : _status = initialStatus;

  final String initialStatus;
  final Future<void> Function(String runId) onCancelRun;
  final Future<List<RunOutputSnapshot>> Function(String runId) loadRunOutput;

  String _status;
  String? _runId;
  RunOutputSnapshot? _output;
  TaskExecutionResult? _result;
  String? _error;
  bool _cancelling = false;
  bool _cancelled = false;
  bool _loadingSnapshot = false;
  bool _contentStreamingStarted = false;
  Timer? _snapshotTimer;

  String get status => _status;
  String? get runId => _runId;
  RunOutputSnapshot? get output => _output;
  TaskExecutionResult? get result => _result;
  String? get error => _error;
  bool get cancelling => _cancelling;
  bool get cancelled => _cancelled;
  bool get running => _result == null && _error == null && !_cancelled;
  bool get contentStreamingStarted => _contentStreamingStarted;

  void updateStatus(String value) {
    if (!running || value == _status) return;
    _status = value;
    notifyListeners();
  }

  void attachRun(String runId) {
    _runId = runId;
    notifyListeners();
    _snapshotTimer?.cancel();
    unawaited(_refreshOutput());
    _snapshotTimer = Timer.periodic(
      const Duration(milliseconds: 600),
      (_) => unawaited(_refreshOutput()),
    );
  }

  void updateOutput(RunOutputSnapshot snapshot) {
    if (snapshot.channel != 'main' || !running) return;
    final current = _output;
    if (current != null &&
        current.lastSequence == snapshot.lastSequence &&
        current.status == snapshot.status &&
        current.content == snapshot.content &&
        current.updateType == snapshot.updateType) {
      return;
    }
    switch (snapshot.updateType) {
      case RunOutputUpdateType.started:
        _contentStreamingStarted = false;
      case RunOutputUpdateType.append:
        _contentStreamingStarted = true;
      case RunOutputUpdateType.snapshot:
        if (_isRenderableOutput(snapshot) && snapshot.content.isNotEmpty) {
          _contentStreamingStarted = true;
        }
      case RunOutputUpdateType.replace:
      case RunOutputUpdateType.completed:
      case RunOutputUpdateType.failed:
      case RunOutputUpdateType.partial:
        break;
    }
    _output = snapshot;
    notifyListeners();
  }

  void complete(TaskExecutionResult result) {
    _stopSnapshotPolling();
    _result = result;
    _status = '生成完成';
    _cancelling = false;
    notifyListeners();
  }

  void fail(String message) {
    _stopSnapshotPolling();
    _status = '生成失败';
    _error = message;
    _cancelling = false;
    notifyListeners();
  }

  void markCancelled() {
    _stopSnapshotPolling();
    _status = '任务已取消';
    _cancelled = true;
    _cancelling = false;
    notifyListeners();
  }

  Future<void> cancel() async {
    final currentRunId = _runId;
    if (currentRunId == null || _cancelling || !running) return;
    _cancelling = true;
    _status = '正在取消任务';
    notifyListeners();
    try {
      await onCancelRun(currentRunId);
    } catch (exception) {
      _cancelling = false;
      _error = '$exception';
      notifyListeners();
    }
  }

  Future<void> _refreshOutput() async {
    final currentRunId = _runId;
    if (currentRunId == null || _loadingSnapshot || !running) return;
    _loadingSnapshot = true;
    try {
      final snapshots = await loadRunOutput(currentRunId);
      for (final snapshot in snapshots) {
        updateOutput(snapshot);
      }
    } catch (_) {
      // SSE remains primary. A later snapshot refresh can recover the gap.
    } finally {
      _loadingSnapshot = false;
    }
  }

  void _stopSnapshotPolling() {
    _snapshotTimer?.cancel();
    _snapshotTimer = null;
  }

  @override
  void dispose() {
    _stopSnapshotPolling();
    super.dispose();
  }
}

bool _isRenderableOutput(RunOutputSnapshot snapshot) {
  return snapshot.format == 'markdown' || snapshot.format == 'plain_text';
}

class TaskExecutionPage extends StatefulWidget {
  const TaskExecutionPage({
    super.key,
    required this.title,
    required this.controller,
    required this.openResult,
    required this.resultRouteBuilder,
  });

  final String title;
  final TaskExecutionController controller;
  final bool openResult;
  final Route<void> Function(TaskExecutionResult result) resultRouteBuilder;

  @override
  State<TaskExecutionPage> createState() => _TaskExecutionPageState();
}

class _TaskExecutionPageState extends State<TaskExecutionPage> {
  bool _completionHandled = false;
  bool _playbackCompleted = false;
  final ScrollController _scrollController = ScrollController();
  late final StreamingScrollFollowController _scrollFollowController;

  @override
  void initState() {
    super.initState();
    _scrollFollowController = StreamingScrollFollowController(
      scrollController: _scrollController,
    );
    widget.controller.addListener(_onControllerChanged);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChanged);
    _scrollFollowController.dispose();
    _scrollController.dispose();
    widget.controller.dispose();
    super.dispose();
  }

  void _onControllerChanged() {
    final output = widget.controller.output;
    if (mounted) setState(() {});
    final result = widget.controller.result;
    if (result == null || _completionHandled) return;
    if (output?.content.isNotEmpty == true) return;
    _playbackCompleted = true;
    _handleCompletion();
  }

  void _handlePlaybackCompleted() {
    if (_playbackCompleted) return;
    setState(() => _playbackCompleted = true);
  }

  void _handleCompletion() {
    final result = widget.controller.result;
    if (result == null || _completionHandled) return;
    _completionHandled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (widget.openResult) {
        Navigator.of(context).pushReplacement<void, void>(
          widget.resultRouteBuilder(result),
        );
      } else {
        Navigator.of(context).pop();
      }
    });
    WidgetsBinding.instance.scheduleFrame();
  }

  Future<void> _copyAll(String text) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('全文已复制')),
    );
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    final output = controller.output;
    final copyableOutput = output != null &&
            output.content.isNotEmpty &&
            (output.format == 'markdown' || output.format == 'plain_text')
        ? output
        : null;
    final showTopStatus =
        !controller.contentStreamingStarted || controller.error != null;
    return PopScope(
      canPop: !controller.running,
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.title),
          automaticallyImplyLeading: !controller.running,
          leading: controller.running
              ? null
              : IconButton(
                  onPressed: () => Navigator.of(context).pop(),
                  tooltip: '返回',
                  icon: const Icon(Icons.arrow_back_rounded),
                ),
          actions: [
            if (copyableOutput != null)
              IconButton(
                onPressed: () => _copyAll(copyableOutput.content),
                tooltip: '复制全文',
                icon: const Icon(Icons.copy_all_outlined),
              ),
          ],
        ),
        bottomNavigationBar: _ExecutionActionBar(
          controller: controller,
          playbackCompleted:
              _playbackCompleted || output?.content.isNotEmpty != true,
          onReturn: () => Navigator.of(context).pop(),
        ),
        body: SafeArea(
          child: NotificationListener<ScrollNotification>(
            onNotification: _scrollFollowController.handleNotification,
            child: SingleChildScrollView(
              key: const ValueKey('task-execution-scroll-view'),
              controller: _scrollController,
              padding: EdgeInsets.fromLTRB(
                20,
                18,
                20,
                output?.content.isNotEmpty == true ? 0 : 24,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (showTopStatus) ...[
                    Row(
                      children: [
                        if (controller.running)
                          const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        else
                          Icon(
                            controller.error == null
                                ? Icons.check_circle_outline_rounded
                                : Icons.error_outline_rounded,
                            size: 20,
                            color: controller.error == null
                                ? AppColors.accent
                                : AppColors.danger,
                          ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            controller.status,
                            style: TextStyle(
                              color: controller.error == null
                                  ? AppColors.accent
                                  : AppColors.danger,
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 18),
                    const Divider(height: 1),
                    const SizedBox(height: 18),
                  ],
                  if (controller.error != null) ...[
                    _ExecutionError(message: controller.error!),
                    if (output?.content.isNotEmpty == true)
                      const SizedBox(height: 18),
                  ],
                  if (output?.content.isNotEmpty == true)
                    _buildOutput(output!)
                  else if (controller.error == null)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 72),
                      child: Column(
                        children: [
                          Icon(
                            Icons.auto_awesome_rounded,
                            size: 34,
                            color: AppColors.muted.withOpacity(0.7),
                          ),
                          const SizedBox(height: 12),
                          Text(
                            controller.cancelled
                                ? '本次任务没有生成可保留的内容'
                                : '正在等待模型返回内容',
                            style: Theme.of(context).textTheme.bodyMedium,
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildOutput(RunOutputSnapshot output) {
    final result = widget.controller.result;
    final phase = result == null
        ? widget.controller.error == null
            ? StreamingOutputPhase.streaming
            : StreamingOutputPhase.failed
        : result.runStatus == 'PARTIAL'
            ? StreamingOutputPhase.partial
            : StreamingOutputPhase.succeeded;
    return StreamingOutputView(
      snapshot: output,
      phase: phase,
      stopRequested:
          widget.controller.cancelling || widget.controller.cancelled,
      onPlaybackCompleted: _handlePlaybackCompleted,
      onSettled: _handleCompletion,
      onContentChanged: _scrollFollowController.contentChanged,
    );
  }
}

class _ExecutionActionBar extends StatelessWidget {
  const _ExecutionActionBar({
    required this.controller,
    required this.playbackCompleted,
    required this.onReturn,
  });

  final TaskExecutionController controller;
  final bool playbackCompleted;
  final VoidCallback onReturn;

  @override
  Widget build(BuildContext context) {
    final Widget action;
    if (controller.running) {
      action = OutlinedButton.icon(
        key: const ValueKey('task-execution-stop-action'),
        onPressed: controller.runId == null || controller.cancelling
            ? null
            : controller.cancel,
        icon: const Icon(Icons.stop_circle_outlined),
        label: Text(controller.cancelling ? '正在取消' : '停止生成'),
      );
    } else if (controller.result == null) {
      action = FilledButton.icon(
        key: const ValueKey('task-execution-return-action'),
        onPressed: onReturn,
        icon: const Icon(Icons.tune_rounded),
        label: const Text('返回修改'),
      );
    } else if (!playbackCompleted) {
      action = OutlinedButton.icon(
        key: const ValueKey('task-execution-settling-action'),
        onPressed: null,
        icon: const SizedBox(
          width: 16,
          height: 16,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
        label: const Text('正在完成'),
      );
    } else {
      action = OutlinedButton.icon(
        key: const ValueKey('task-execution-complete-action'),
        onPressed: null,
        icon: const Icon(Icons.check_rounded),
        label: const Text('生成完成'),
      );
    }

    return SafeArea(
      top: false,
      child: Container(
        key: const ValueKey('task-execution-action-bar'),
        height: 68,
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        decoration: const BoxDecoration(
          color: AppColors.paper,
          border: Border(top: BorderSide(color: AppColors.line)),
        ),
        child: action,
      ),
    );
  }
}

class _ExecutionError extends StatelessWidget {
  const _ExecutionError({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: const Color(0xFFFFF1F0),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Text(
          message,
          style: const TextStyle(
            color: AppColors.danger,
            fontSize: 13,
            height: 1.5,
          ),
        ),
      );
}
