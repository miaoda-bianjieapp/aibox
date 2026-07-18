import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/feature_models.dart';
import '../network/api_exception.dart';
import '../network/task_execution_result.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';

typedef OutlineVersionExecutor = Future<TaskExecutionResult?> Function({
  required ArtifactView baseArtifact,
  required String operation,
  String? editedText,
  required ValueChanged<String> onStatus,
});

typedef OutlineInputAdjuster = Future<TaskExecutionResult?> Function(
  ArtifactView baseArtifact,
);

Future<TaskExecutionResult?> executeOutlineVersion({
  required AppDataController data,
  required ArtifactView baseArtifact,
  required String operation,
  String? editedText,
  required ValueChanged<String> onStatus,
}) async {
  final detail = await data.api.getTask(baseArtifact.taskId);
  final feature = await data.api.getFeature(detail.task.featureCode);
  final run =
      detail.runs.where((item) => item.id == baseArtifact.runId).firstOrNull;
  if (run == null) {
    throw const ApiException('无法读取当前版本的执行参数');
  }

  final parameters = Map<String, Object?>.from(run.parameters)
    ..remove('editedText')
    ..['operation'] = operation;
  if (editedText != null) {
    parameters['editedText'] = editedText;
  }

  final result = await data.api.executeFeature(
    feature: feature,
    taskTitle: detail.task.title,
    projectId: detail.task.projectId,
    existingTaskId: detail.task.id,
    baseArtifactId: baseArtifact.id,
    selectedModelCode: run.selectedModelCode,
    selectedModels: run.selectedModels,
    parameters: parameters,
    inputAssetIds: run.inputAssetIds,
    onStatus: onStatus,
  );
  await data.refresh();
  return result;
}

Future<TaskLaunchRequest> buildOutlineLaunchRequest({
  required AppDataController data,
  required ArtifactView baseArtifact,
}) async {
  final detail = await data.api.getTask(baseArtifact.taskId);
  var workspace = data.workspaceForFeature(detail.task.featureCode);
  var feature = data.featureByCode(detail.task.featureCode);
  if (workspace == null || feature == null) {
    await data.refresh();
    workspace = data.workspaceForFeature(detail.task.featureCode);
    feature = data.featureByCode(detail.task.featureCode);
  }
  final run =
      detail.runs.where((item) => item.id == baseArtifact.runId).firstOrNull;
  if (workspace == null || feature == null || run == null) {
    throw const ApiException('无法读取当前版本的功能和参数');
  }

  final parameters = Map<String, Object?>.from(run.parameters)
    ..remove('operation')
    ..remove('editedText');
  return TaskLaunchRequest(
    workspace: workspace,
    entry: feature,
    initialParameters: parameters,
    initialAssetIds: run.inputAssetIds,
    existingTaskId: detail.task.id,
    baseArtifactId: baseArtifact.id,
    baseVersion: baseArtifact.versionNumber,
    taskTitle: detail.task.title,
    projectId: detail.task.projectId,
    initialModelCode: run.selectedModelCode,
    initialModels: run.selectedModels,
  );
}

class OutlineResultPage extends StatefulWidget {
  const OutlineResultPage({
    super.key,
    required this.artifact,
    required this.onExecuteVersion,
    required this.onAdjustInput,
  });

  final ArtifactView artifact;
  final OutlineVersionExecutor onExecuteVersion;
  final OutlineInputAdjuster onAdjustInput;

  @override
  State<OutlineResultPage> createState() => _OutlineResultPageState();
}

class _OutlineResultPageState extends State<OutlineResultPage> {
  late ArtifactView _artifact;
  late final TextEditingController _controller;
  late String _savedText;
  bool _busy = false;
  String? _status;
  String? _error;

  bool get _dirty => _controller.text != _savedText;

  @override
  void initState() {
    super.initState();
    _artifact = widget.artifact;
    _savedText = _artifact.content['text']?.toString() ?? '';
    _controller = TextEditingController(text: _savedText)
      ..addListener(_onTextChanged);
  }

  @override
  void dispose() {
    _controller
      ..removeListener(_onTextChanged)
      ..dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_busy && !_dirty,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) _handleBack();
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('大纲与思路'),
          actions: [
            IconButton(
              onPressed: _busy ? null : _copy,
              tooltip: '复制当前内容',
              icon: const Icon(Icons.copy_all_outlined),
            ),
          ],
        ),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 32),
            children: [
              Text(
                _artifact.title,
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 5),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      'v${_artifact.versionNumber} · ${_sourceLabel(_artifact)} · '
                      '${_formatDate(_artifact.createdAt)}',
                      style: const TextStyle(
                        color: AppColors.muted,
                        fontSize: 11,
                      ),
                    ),
                  ),
                  if (_dirty)
                    const Text(
                      '未保存',
                      style: TextStyle(
                        color: Color(0xFFB33A32),
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 18),
              TextField(
                controller: _controller,
                enabled: !_busy,
                minLines: 16,
                maxLines: null,
                maxLength: 10000,
                keyboardType: TextInputType.multiline,
                decoration: const InputDecoration(
                  hintText: '生成的写作框架会显示在这里，可直接编辑。',
                  alignLabelWithHint: true,
                ),
              ),
              if (_status != null) ...[
                const SizedBox(height: 14),
                const LinearProgressIndicator(minHeight: 3),
                const SizedBox(height: 8),
                Text(
                  _status!,
                  style: const TextStyle(
                    color: AppColors.accent,
                    fontSize: 12,
                  ),
                ),
              ],
              if (_error != null) ...[
                const SizedBox(height: 14),
                _OutlineError(message: _error!),
              ],
              const SizedBox(height: 18),
              SizedBox(
                height: 48,
                child: FilledButton.icon(
                  onPressed: _busy || !_dirty ? null : _saveEditedVersion,
                  icon: const Icon(Icons.save_outlined),
                  label: const Text('保存新版本'),
                ),
              ),
              const SizedBox(height: 10),
              LayoutBuilder(
                builder: (context, constraints) {
                  final regenerate = OutlinedButton.icon(
                    onPressed: _busy ? null : _regenerate,
                    icon: const Icon(Icons.refresh_rounded),
                    label: const Text('重新生成'),
                  );
                  final adjust = OutlinedButton.icon(
                    onPressed: _busy ? null : _adjustInput,
                    icon: const Icon(Icons.tune_rounded),
                    label: const Text('调整输入'),
                  );
                  if (constraints.maxWidth < 360) {
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        SizedBox(height: 44, child: regenerate),
                        const SizedBox(height: 8),
                        SizedBox(height: 44, child: adjust),
                      ],
                    );
                  }
                  return Row(
                    children: [
                      Expanded(child: SizedBox(height: 44, child: regenerate)),
                      const SizedBox(width: 10),
                      Expanded(child: SizedBox(height: 44, child: adjust)),
                    ],
                  );
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _onTextChanged() {
    if (mounted) setState(() => _error = null);
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _controller.text));
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('当前内容已复制')));
    }
  }

  Future<bool> _saveEditedVersion() async {
    final text = _controller.text;
    if (text.trim().isEmpty) {
      setState(() => _error = '编辑内容不能为空');
      return false;
    }
    return _executeVersion(operation: 'save_edit', editedText: text);
  }

  Future<void> _regenerate() async {
    if (!await _resolveUnsavedChanges()) return;
    if (!mounted) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认重新生成'),
        content: const Text('重新生成将调用当前文本模型，可能产生费用。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('继续生成'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await _executeVersion(operation: 'regenerate');
    }
  }

  Future<void> _adjustInput() async {
    if (!await _resolveUnsavedChanges()) return;
    setState(() {
      _busy = true;
      _status = '正在打开输入设置';
      _error = null;
    });
    try {
      final result = await widget.onAdjustInput(_artifact);
      if (!mounted) return;
      if (result != null) {
        _replaceArtifact(result.artifact);
      } else {
        setState(() {
          _busy = false;
          _status = null;
        });
      }
    } catch (exception) {
      if (mounted) {
        setState(() {
          _busy = false;
          _status = null;
          _error = '$exception';
        });
      }
    }
  }

  Future<bool> _executeVersion({
    required String operation,
    String? editedText,
  }) async {
    setState(() {
      _busy = true;
      _status = operation == 'save_edit' ? '正在保存新版本' : '正在重新生成';
      _error = null;
    });
    try {
      final result = await widget.onExecuteVersion(
        baseArtifact: _artifact,
        operation: operation,
        editedText: editedText,
        onStatus: (status) {
          if (mounted) setState(() => _status = status);
        },
      );
      if (!mounted) return false;
      if (result == null) {
        setState(() {
          _busy = false;
          _status = null;
        });
        return false;
      }
      _replaceArtifact(result.artifact);
      return true;
    } catch (exception) {
      if (mounted) {
        setState(() {
          _busy = false;
          _status = null;
          _error = '$exception';
        });
      }
      return false;
    }
  }

  void _replaceArtifact(ArtifactView artifact) {
    final text = artifact.content['text']?.toString() ?? '';
    _controller.removeListener(_onTextChanged);
    _controller
      ..text = text
      ..selection = TextSelection.collapsed(offset: text.length);
    _controller.addListener(_onTextChanged);
    setState(() {
      _artifact = artifact;
      _savedText = text;
      _busy = false;
      _status = null;
      _error = null;
    });
  }

  void _discardChanges() {
    _controller.removeListener(_onTextChanged);
    _controller
      ..text = _savedText
      ..selection = TextSelection.collapsed(offset: _savedText.length);
    _controller.addListener(_onTextChanged);
    setState(() => _error = null);
  }

  Future<bool> _resolveUnsavedChanges() async {
    if (!_dirty) return true;
    final choice = await _showUnsavedDialog();
    if (choice == 'save') {
      return _saveEditedVersion();
    }
    if (choice == 'discard') {
      _discardChanges();
      return true;
    }
    return false;
  }

  Future<void> _handleBack() async {
    if (_busy || !_dirty) return;
    final choice = await _showUnsavedDialog(exitPage: true);
    if (choice == 'save') {
      final saved = await _saveEditedVersion();
      if (saved && mounted) Navigator.of(context).pop();
    } else if (choice == 'discard' && mounted) {
      Navigator.of(context).pop();
    }
  }

  Future<String?> _showUnsavedDialog({bool exitPage = false}) {
    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('存在未保存修改'),
        content: Text(exitPage ? '离开前要保存当前编辑内容吗？' : '继续操作前要保存当前编辑内容吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop('discard'),
            child: const Text('放弃修改'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop('save'),
            child: const Text('保存新版本'),
          ),
        ],
      ),
    );
  }
}

class _OutlineError extends StatelessWidget {
  const _OutlineError({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: const Color(0xFFFFF1F0),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Text(
          message,
          style: const TextStyle(color: Color(0xFFB33A32), fontSize: 12),
        ),
      );
}

String _sourceLabel(ArtifactView artifact) =>
    artifact.metadata['sourceType']?.toString() == 'manual' ? '人工编辑' : '模型生成';

String _formatDate(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:'
    '${value.minute.toString().padLeft(2, '0')}';
