import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/run_output_models.dart';
import '../network/task_execution_result.dart';
import '../theme/app_theme.dart';
import '../widgets/markdown_output_view.dart';

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
  Timer? _snapshotTimer;

  String get status => _status;
  String? get runId => _runId;
  RunOutputSnapshot? get output => _output;
  TaskExecutionResult? get result => _result;
  String? get error => _error;
  bool get cancelling => _cancelling;
  bool get cancelled => _cancelled;
  bool get running => _result == null && _error == null && !_cancelled;

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
        current.content == snapshot.content) {
      return;
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
  static const double _bottomThreshold = 40;

  bool _completionHandled = false;
  bool _followOutput = true;
  bool _userScrolling = false;
  String? _lastOutputContent;
  int? _lastOutputSequence;
  final ScrollController _scrollController = ScrollController();
  final GlobalKey _bottomAnchorKey = GlobalKey();

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onControllerChanged);
    _scrollController.addListener(_restoreFollowingAtBottom);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChanged);
    _scrollController.removeListener(_restoreFollowingAtBottom);
    _scrollController.dispose();
    widget.controller.dispose();
    super.dispose();
  }

  void _onControllerChanged() {
    final output = widget.controller.output;
    final outputChanged = output != null &&
        (output.content != _lastOutputContent ||
            output.lastSequence != _lastOutputSequence);
    if (output != null) {
      _lastOutputContent = output.content;
      _lastOutputSequence = output.lastSequence;
    }
    if (!_userScrolling && !_followOutput && _isAtBottom()) {
      _followOutput = true;
    }
    if (mounted) setState(() {});
    if (outputChanged && _followOutput) {
      _scheduleScrollToBottom();
    }
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
  }

  bool _handleScrollNotification(ScrollNotification notification) {
    if (notification.metrics.axis != Axis.vertical) return false;

    if (notification is ScrollStartNotification &&
        notification.dragDetails != null) {
      setState(() {
        _userScrolling = true;
        _followOutput = false;
      });
    }

    if (notification is ScrollUpdateNotification &&
        notification.dragDetails != null &&
        !_userScrolling) {
      setState(() {
        _userScrolling = true;
        _followOutput = false;
      });
    }
    if (notification is ScrollUpdateNotification && !_userScrolling) {
      _restoreFollowingAtBottom();
    }
    if (notification is ScrollEndNotification) {
      _userScrolling = false;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _restoreFollowingAtBottom();
      });
    }
    return false;
  }

  bool _isAtBottom() {
    if (!_scrollController.hasClients) return true;
    return _scrollController.position.extentAfter <= _bottomThreshold;
  }

  void _restoreFollowingAtBottom() {
    if (_userScrolling || !_scrollController.hasClients) return;
    final atBottom = _isAtBottom();
    if (atBottom && !_followOutput) {
      setState(() {
        _followOutput = true;
      });
    }
  }

  void _scheduleScrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_followOutput || _userScrolling) return;
      final anchorContext = _bottomAnchorKey.currentContext;
      if (anchorContext == null) return;
      unawaited(Scrollable.ensureVisible(
        anchorContext,
        alignment: 1,
        duration: Duration.zero,
      ).then((_) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _restoreFollowingAtBottom();
        });
      }));
    });
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
            if (output?.content.isNotEmpty == true)
              IconButton(
                onPressed: () => _copyAll(output!.content),
                tooltip: '复制全文',
                icon: const Icon(Icons.copy_all_outlined),
              ),
          ],
        ),
        body: SafeArea(
          child: NotificationListener<ScrollNotification>(
            onNotification: _handleScrollNotification,
            child: SingleChildScrollView(
              key: const ValueKey('task-execution-scroll-view'),
              controller: _scrollController,
              padding: const EdgeInsets.fromLTRB(20, 18, 20, 32),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
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
                  if (controller.error != null)
                    _ExecutionError(message: controller.error!)
                  else if (output?.content.isNotEmpty == true)
                    _buildOutput(output!, controller.running)
                  else
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
                  const SizedBox(height: 24),
                  if (controller.running)
                    OutlinedButton.icon(
                      onPressed:
                          controller.runId == null || controller.cancelling
                              ? null
                              : controller.cancel,
                      icon: const Icon(Icons.stop_circle_outlined),
                      label: Text(
                        controller.cancelling ? '正在取消' : '停止生成',
                      ),
                    )
                  else if (controller.result == null)
                    FilledButton.icon(
                      onPressed: () => Navigator.of(context).pop(),
                      icon: const Icon(Icons.tune_rounded),
                      label: const Text('返回修改'),
                    ),
                  SizedBox(key: _bottomAnchorKey, height: 1),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildOutput(RunOutputSnapshot output, bool streaming) {
    if (output.format == 'plain_text') {
      return SelectableText(
        output.content,
        style: Theme.of(context).textTheme.bodyLarge?.copyWith(
              color: AppColors.ink,
              height: 1.55,
            ),
      );
    }
    return MarkdownOutputView(
      markdown: output.content,
      streaming: streaming,
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
