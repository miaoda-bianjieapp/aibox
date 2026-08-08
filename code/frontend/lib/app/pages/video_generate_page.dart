import 'dart:async';

import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../network/api_exception.dart';
import '../network/task_execution_result.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/task_sheet.dart';
import 'task_execution_page.dart';
import 'video_storyboard_editing.dart';

class VideoGeneratePage extends StatefulWidget {
  const VideoGeneratePage({
    super.key,
    required this.data,
    required this.workspace,
    required this.feature,
    this.taskId,
  });

  final AppDataController data;
  final WorkspaceDefinition workspace;
  final FeatureDetail feature;
  final String? taskId;

  @override
  State<VideoGeneratePage> createState() => _VideoGeneratePageState();
}

class _VideoGeneratePageState extends State<VideoGeneratePage> {
  final _promptController = TextEditingController();
  final _scriptController = TextEditingController();
  final _taskTitleController = TextEditingController(text: 'AI视频生成');
  final _scrollController = ScrollController();

  AssetView? _firstFrame;
  AssetView? _lastFrame;
  final List<CreativeAssetView> _creativeAssets = [];
  final List<Map<String, Object?>> _storyboard = [];
  final Set<String> _selectedCreativeAssetIds = {};
  final Map<String, String> _selectedModels = {};

  String _mode = 'simple';
  String? _projectId;
  String? _taskId;
  String? _storyboardArtifactId;
  int _durationSeconds = 8;
  String _aspectRatio = '16:9';
  String _resolution = '720p';
  bool _loading = true;
  bool _busy = false;
  bool _uploading = false;
  String? _uploadingAssetReferenceId;
  String? _assetOperationLabel;
  TaskExecutionController? _assetOperationController;
  String? _status;
  String? _error;

  @override
  void initState() {
    super.initState();
    _taskId = widget.taskId;
    _initializeModels();
    _initializeSettings();
    unawaited(_load());
  }

  @override
  void dispose() {
    _assetOperationController?.dispose();
    _promptController.dispose();
    _scriptController.dispose();
    _taskTitleController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _initializeModels() {
    for (final policy in widget.feature.modelPolicies) {
      _selectedModels[policy.capability] = policy.defaultModelCode;
    }
  }

  void _initializeSettings() {
    final durationValues = _allowedValues('durationSeconds');
    final ratioValues = _allowedValues('aspectRatio');
    final resolutionValues = _allowedValues('resolution');
    if (durationValues.isNotEmpty) {
      _durationSeconds = int.tryParse(durationValues.first) ?? 8;
    }
    if (ratioValues.isNotEmpty) _aspectRatio = ratioValues.first;
    if (resolutionValues.isNotEmpty) _resolution = resolutionValues.first;
  }

  Future<void> _load() async {
    try {
      if (_taskId != null) {
        final detail = await widget.data.api.getTask(_taskId!);
        final run = detail.runs.firstOrNull;
        if (run != null) {
          _projectId = detail.task.projectId;
          _taskTitleController.text = detail.task.title;
          _mode = run.parameters['mode']?.toString() == 'expert'
              ? 'expert'
              : 'simple';
          _promptController.text =
              run.parameters['prompt']?.toString() ?? _promptController.text;
          _scriptController.text =
              run.parameters['script']?.toString() ?? _scriptController.text;
          _durationSeconds =
              int.tryParse('${run.parameters['durationSeconds']}') ??
                  _durationSeconds;
          _aspectRatio =
              run.parameters['aspectRatio']?.toString() ?? _aspectRatio;
          _resolution = run.parameters['resolution']?.toString() ?? _resolution;
          _selectedModels
            ..clear()
            ..addAll(run.selectedModels);
          for (final policy in widget.feature.modelPolicies) {
            _selectedModels.putIfAbsent(
              policy.capability,
              () => policy.defaultModelCode,
            );
          }
          final current = detail.artifacts
                  .where((item) =>
                      item.runId == run.id && item.kind == 'video_storyboard')
                  .firstOrNull ??
              detail.artifacts
                  .where((item) => item.id == run.baseArtifactId)
                  .firstOrNull ??
              detail.artifacts
                  .where((item) => item.kind == 'video_storyboard')
                  .firstOrNull;
          if (current != null && current.kind == 'video_storyboard') {
            _storyboardArtifactId = current.id;
            _readStoryboard(current);
          }
          final firstFrameId = run.parameters['firstFrameAssetId']?.toString();
          final lastFrameId = run.parameters['lastFrameAssetId']?.toString();
          final imageInputs =
              run.inputAssets.where((asset) => asset.isImage).toList();
          _firstFrame = imageInputs
                  .where((asset) => asset.id == firstFrameId)
                  .firstOrNull ??
              imageInputs.firstOrNull;
          _lastFrame =
              imageInputs.where((asset) => asset.id == lastFrameId).firstOrNull;
          if (lastFrameId == null && imageInputs.length > 1) {
            _lastFrame = imageInputs[1];
          }
          _normalizeSettingsForModels();
        }
      }
      await _reloadCreativeAssets();
      if (mounted) {
        setState(() => _loading = false);
      }
    } catch (exception) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = _message(exception);
        });
      }
    }
  }

  Future<void> _reloadCreativeAssets() async {
    final values = await Future.wait<List<CreativeAssetView>>([
      widget.data.api.listCreativeAssets(scope: 'GLOBAL'),
      if (_projectId != null)
        widget.data.api.listCreativeAssets(
          scope: 'PROJECT',
          projectId: _projectId,
        )
      else
        Future.value(const <CreativeAssetView>[]),
    ]);
    final merged = <String, CreativeAssetView>{
      for (final asset in values.expand((items) => items)) asset.id: asset,
    };
    _creativeAssets
      ..clear()
      ..addAll(merged.values);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('AI视频生成'),
        actions: [
          if (_assetOperationController != null)
            IconButton(
              onPressed: _showAssetOperationStatus,
              tooltip: '资产生成状态',
              icon: const Icon(Icons.pending_actions_outlined),
            ),
          IconButton(
            onPressed: _loading || _busy ? null : _showModelSelection,
            tooltip: '模型选择',
            icon: const Icon(Icons.model_training_outlined),
          ),
          IconButton(
            onPressed: _loading || _busy ? null : _showSettings,
            tooltip: '视频设置',
            icon: const Icon(Icons.tune_rounded),
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : LayoutBuilder(
              builder: (context, constraints) => Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 980),
                  child: ListView(
                    controller: _scrollController,
                    padding: const EdgeInsets.fromLTRB(20, 18, 20, 36),
                    children: [
                      _buildHeader(),
                      const SizedBox(height: 18),
                      _buildModeSelector(),
                      const SizedBox(height: 22),
                      _buildModelSummary(),
                      const SizedBox(height: 16),
                      if (_assetOperationController != null) ...[
                        _buildAssetOperationStatusButton(),
                        const SizedBox(height: 12),
                      ],
                      if (_busy && _status != null) ...[
                        const LinearProgressIndicator(
                          minHeight: 3,
                          color: AppColors.accent,
                        ),
                        const SizedBox(height: 7),
                        Text(
                          _status!,
                          style: const TextStyle(
                            color: AppColors.accent,
                            fontSize: 12,
                          ),
                        ),
                        const SizedBox(height: 14),
                      ],
                      if (_mode == 'simple')
                        _buildSimpleMode()
                      else
                        _buildExpertMode(),
                      if (_error != null) ...[
                        const SizedBox(height: 16),
                        _ErrorBanner(message: _error!),
                      ],
                    ],
                  ),
                ),
              ),
            ),
    );
  }

  Widget _buildHeader() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '把文字和资产变成一条完整视频',
          style: Theme.of(context).textTheme.headlineMedium,
        ),
        const SizedBox(height: 7),
        Text(
          _mode == 'simple'
              ? '按模型能力添加首帧或尾帧，输入画面描述，快速生成一条视频。'
              : '先拆分可编辑分镜，再生成资产和最终视频。',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
        if (widget.data.projects.isNotEmpty) ...[
          const SizedBox(height: 14),
          DropdownButtonFormField<String?>(
            value: _projectId,
            decoration: const InputDecoration(labelText: '所属项目'),
            items: [
              const DropdownMenuItem<String?>(
                value: null,
                child: Text('不归入项目，仅使用全局资产'),
              ),
              ...widget.data.projects.map(
                (project) => DropdownMenuItem<String?>(
                  value: project.id,
                  child: Text(project.name),
                ),
              ),
            ],
            onChanged: _busy || _taskId != null ? null : _changeProject,
          ),
        ],
        const SizedBox(height: 14),
        TextField(
          controller: _taskTitleController,
          enabled: !_busy,
          decoration: const InputDecoration(
            labelText: '任务名称',
            hintText: '用于历史记录识别',
          ),
        ),
      ],
    );
  }

  Future<void> _changeProject(String? projectId) async {
    setState(() {
      _projectId = projectId;
      _selectedCreativeAssetIds.clear();
    });
    await _reloadCreativeAssets();
    if (mounted) setState(() {});
  }

  Widget _buildModeSelector() {
    return SegmentedButton<String>(
      segments: const [
        ButtonSegment(
          value: 'simple',
          icon: Icon(Icons.flash_on_outlined),
          label: Text('简洁模式'),
        ),
        ButtonSegment(
          value: 'expert',
          icon: Icon(Icons.dashboard_customize_outlined),
          label: Text('专家模式'),
        ),
      ],
      selected: {_mode},
      showSelectedIcon: false,
      onSelectionChanged: _busy
          ? null
          : (selection) {
              final next = selection.first;
              setState(() {
                if (_mode == 'simple' && next == 'expert') {
                  _scriptController.text = _promptController.text;
                }
                _mode = next;
              });
            },
    );
  }

  Widget _buildModelSummary() {
    final capabilities = _mode == 'simple'
        ? const ['VIDEO_GENERATION']
        : const [
            'TEXT_GENERATION',
            'IMAGE_GENERATION',
            'VIDEO_GENERATION',
          ];
    return _Section(
      title: '模型选择',
      trailing: TextButton.icon(
        onPressed: _busy ? null : _showModelSelection,
        icon: const Icon(Icons.expand_more_rounded, size: 18),
        label: const Text('选择模型'),
      ),
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (final capability in capabilities)
            _SettingPill(
              label: _capabilityShortLabel(capability),
              value: _modelLabel(capability),
            ),
          if (widget.feature.modelPolicies.isEmpty)
            const Text(
              '当前功能没有返回可用模型策略',
              style: TextStyle(color: AppColors.danger, fontSize: 12),
            ),
        ],
      ),
    );
  }

  Widget _buildAssetOperationStatusButton() {
    final controller = _assetOperationController;
    if (controller == null) return const SizedBox.shrink();
    return SizedBox(
      width: double.infinity,
      child: OutlinedButton.icon(
        onPressed: _showAssetOperationStatus,
        icon: const Icon(Icons.pending_actions_outlined, size: 18),
        label: Text(
          '${_assetOperationLabel ?? '资产生成'} · ${controller.status}',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );
  }

  Widget _buildSimpleMode() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Section(
          title: '视频描述',
          child: TextField(
            controller: _promptController,
            enabled: !_busy,
            minLines: 5,
            maxLines: 10,
            maxLength: 4000,
            decoration: const InputDecoration(
              hintText: '描述主体、动作、环境、镜头和氛围',
            ),
          ),
        ),
        const SizedBox(height: 16),
        _Section(
          title: '首帧 / 尾帧',
          trailing: Text(
            _frameCapabilityLabel(),
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          child: _buildReferencePicker(),
        ),
        const SizedBox(height: 16),
        _buildSettingsSummary(),
        const SizedBox(height: 18),
        OutlinedButton.icon(
          onPressed: _busy
              ? null
              : () => setState(() {
                    _scriptController.text = _promptController.text;
                    _mode = 'expert';
                  }),
          icon: const Icon(Icons.arrow_forward_rounded),
          label: const Text('转为专家模式'),
        ),
        const SizedBox(height: 12),
        SizedBox(
          width: double.infinity,
          child: FilledButton.icon(
            onPressed: _busy ? null : _submitSimple,
            icon: const Icon(Icons.movie_creation_outlined),
            label: const Text('生成视频'),
          ),
        ),
      ],
    );
  }

  Widget _buildExpertMode() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Section(
          title: '剧本',
          child: Column(
            children: [
              TextField(
                controller: _scriptController,
                enabled: !_busy,
                minLines: 7,
                maxLines: 14,
                maxLength: 20000,
                decoration: const InputDecoration(
                  hintText: '输入完整剧本，模型会按时间片拆成分镜。',
                ),
              ),
              const SizedBox(height: 10),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton.tonalIcon(
                  onPressed: _busy ? null : _breakdownScript,
                  icon: const Icon(Icons.auto_awesome_rounded),
                  label: const Text('拆分为分镜'),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _Section(
          title: '首帧 / 尾帧',
          trailing: Text(
            _frameCapabilityLabel(),
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          child: _buildReferencePicker(),
        ),
        const SizedBox(height: 16),
        if (_storyboard.isNotEmpty) ...[
          _buildStoryboard(),
          const SizedBox(height: 16),
        ],
        _buildCreativeAssets(),
        const SizedBox(height: 16),
        _buildSettingsSummary(),
        const SizedBox(height: 18),
        SizedBox(
          width: double.infinity,
          child: FilledButton.icon(
            onPressed:
                _busy || _storyboard.isEmpty ? null : _generateFinalVideo,
            icon: const Icon(Icons.movie_creation_outlined),
            label: const Text('使用完整分镜生成视频'),
          ),
        ),
      ],
    );
  }

  Widget _buildReferencePicker() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        LayoutBuilder(
          builder: (context, constraints) {
            final width = (constraints.maxWidth - 12) / 2;
            return Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                _buildFrameSlot(
                  label: '首帧',
                  asset: _firstFrame,
                  width: width,
                  onPick: () => _pickFrame(first: true),
                  enabled: _canUseFrame(first: true),
                  onRemove: _firstFrame == null
                      ? null
                      : () => setState(() => _firstFrame = null),
                ),
                _buildFrameSlot(
                  label: '尾帧',
                  asset: _lastFrame,
                  width: width,
                  onPick: () => _pickFrame(first: false),
                  enabled: _canUseFrame(first: false),
                  onRemove: _lastFrame == null
                      ? null
                      : () => setState(() => _lastFrame = null),
                ),
              ],
            );
          },
        ),
        const SizedBox(height: 5),
        const Text(
          '支持 PNG、JPG、JPEG、WebP，单张不超过 20 MB。双帧是否可用由所选视频模型决定。',
          style: TextStyle(color: AppColors.muted, fontSize: 11),
        ),
      ],
    );
  }

  Widget _buildFrameSlot({
    required String label,
    required AssetView? asset,
    required double width,
    required VoidCallback onPick,
    required VoidCallback? onRemove,
    required bool enabled,
  }) {
    return SizedBox(
      width: width.clamp(164.0, 420.0).toDouble(),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          Container(
            height: 132,
            decoration: BoxDecoration(
              color: AppColors.wash,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: AppColors.line),
            ),
            child: asset == null
                ? const Center(child: Text('未添加'))
                : Row(
                    children: [
                      _AssetThumbnail(
                        asset: asset,
                        url: widget.data.api.assetContentUrl(asset.id),
                      ),
                      IconButton(
                        onPressed: _busy ? null : onRemove,
                        tooltip: '移除$label',
                        icon: const Icon(Icons.close_rounded),
                      ),
                    ],
                  ),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: _busy || _uploading || !enabled ? null : onPick,
            icon: _uploading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.add_photo_alternate_outlined),
            label: Text(_uploading ? '正在上传' : '添加$label'),
          ),
          if (!enabled) ...[
            const SizedBox(height: 5),
            const Text(
              '当前视频模型不支持该帧输入',
              style: TextStyle(color: AppColors.muted, fontSize: 11),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildSettingsSummary() {
    return _Section(
      title: '视频设置',
      trailing: TextButton.icon(
        onPressed: _busy ? null : _showSettings,
        icon: const Icon(Icons.tune_rounded, size: 18),
        label: const Text('调整'),
      ),
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          _SettingPill(label: '时长', value: '$_durationSeconds 秒'),
          _SettingPill(label: '比例', value: _aspectRatio),
          _SettingPill(label: '分辨率', value: _resolution),
          _SettingPill(
            label: '视频模型',
            value: _modelLabel('VIDEO_GENERATION'),
          ),
        ],
      ),
    );
  }

  Widget _buildStoryboard() {
    return _Section(
      title: '可编辑分镜',
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            '${_storyboard.length} 个时间片',
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          const SizedBox(width: 6),
          IconButton(
            key: const ValueKey<String>('storyboard-add'),
            onPressed:
                _busy || _storyboard.length >= VideoStoryboardEditing.maxShots
                    ? null
                    : () => _addStoryboardShot(_storyboard.length - 1),
            tooltip: '新增分镜',
            icon: const Icon(Icons.add_rounded),
          ),
          IconButton(
            onPressed: _busy ? null : () => _saveStoryboard(),
            tooltip: '保存分镜',
            icon: const Icon(Icons.save_outlined),
          ),
        ],
      ),
      child: Column(
        children: [
          for (var index = 0; index < _storyboard.length; index++)
            _buildShot(index),
        ],
      ),
    );
  }

  Widget _buildShot(int index) {
    final shot = _storyboard[index];
    final shotId = shot['id']?.toString() ?? 'shot-$index';
    final refs = _stringList(shot['assetRefs']);
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
      decoration: BoxDecoration(
        color: AppColors.wash,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
                '镜头 ${index + 1}',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              const Spacer(),
              Text(
                '${shot['startSecond']}s - ${shot['endSecond']}s',
                style: const TextStyle(
                  color: AppColors.accent,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          Align(
            alignment: Alignment.centerRight,
            child: Wrap(
              spacing: 2,
              runSpacing: 2,
              children: [
                IconButton(
                  key: ValueKey<String>('storyboard-move-up-$shotId'),
                  onPressed: _busy || index == 0
                      ? null
                      : () => _moveStoryboardShot(index, index - 1),
                  tooltip: '上移分镜',
                  icon: const Icon(Icons.arrow_upward_rounded, size: 18),
                  visualDensity: VisualDensity.compact,
                ),
                IconButton(
                  key: ValueKey<String>('storyboard-move-down-$shotId'),
                  onPressed: _busy || index == _storyboard.length - 1
                      ? null
                      : () => _moveStoryboardShot(index, index + 1),
                  tooltip: '下移分镜',
                  icon: const Icon(Icons.arrow_downward_rounded, size: 18),
                  visualDensity: VisualDensity.compact,
                ),
                IconButton(
                  key: ValueKey<String>('storyboard-add-after-$shotId'),
                  onPressed: _busy ||
                          _storyboard.length >= VideoStoryboardEditing.maxShots
                      ? null
                      : () => _addStoryboardShot(index),
                  tooltip: '在此镜头后新增',
                  icon: const Icon(Icons.add_circle_outline_rounded, size: 19),
                  visualDensity: VisualDensity.compact,
                ),
                IconButton(
                  key: ValueKey<String>('storyboard-delete-$shotId'),
                  onPressed: _busy || _storyboard.length <= 1
                      ? null
                      : () => _deleteStoryboardShot(index),
                  tooltip: '删除分镜',
                  icon: const Icon(Icons.delete_outline_rounded, size: 19),
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: TextFormField(
                  key: ValueKey<String>('storyboard-start-$shotId'),
                  initialValue: '${shot['startSecond'] ?? 0}',
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: '开始秒数'),
                  onChanged: (value) => setState(() {
                    shot['startSecond'] =
                        double.tryParse(value) ?? shot['startSecond'];
                  }),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: TextFormField(
                  key: ValueKey<String>('storyboard-end-$shotId'),
                  initialValue: '${shot['endSecond'] ?? 0}',
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: '结束秒数'),
                  onChanged: (value) => setState(() {
                    shot['endSecond'] =
                        double.tryParse(value) ?? shot['endSecond'];
                  }),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          TextFormField(
            key: ValueKey<String>('storyboard-description-$shotId'),
            initialValue: '${shot['shotDescription'] ?? ''}',
            minLines: 2,
            maxLines: 4,
            decoration: const InputDecoration(labelText: '镜头描述'),
            onChanged: (value) => shot['shotDescription'] = value,
          ),
          const SizedBox(height: 10),
          TextFormField(
            key: ValueKey<String>('storyboard-action-$shotId'),
            initialValue: '${shot['visualAction'] ?? ''}',
            minLines: 2,
            maxLines: 4,
            decoration: const InputDecoration(labelText: '画面动作'),
            onChanged: (value) => shot['visualAction'] = value,
          ),
          const SizedBox(height: 10),
          TextFormField(
            key: ValueKey<String>('storyboard-camera-$shotId'),
            initialValue: '${shot['cameraMovement'] ?? ''}',
            decoration: const InputDecoration(labelText: '镜头运动（可选）'),
            onChanged: (value) => shot['cameraMovement'] = value,
          ),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerLeft,
            child: Wrap(
              spacing: 6,
              runSpacing: 4,
              children: [
                for (final id in refs)
                  InputChip(
                    label: Text(_creativeName(id)),
                    onDeleted: _busy
                        ? null
                        : () => setState(() {
                              refs.remove(id);
                              shot['assetRefs'] = refs;
                            }),
                  ),
                ActionChip(
                  avatar: const Icon(Icons.link_rounded, size: 16),
                  label: const Text('引用资产'),
                  onPressed: _busy ? null : () => _editShotAssets(index),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildCreativeAssets() {
    return _Section(
      title: '资产库',
      trailing: FilledButton.tonalIcon(
        onPressed: _busy ? null : _createCreativeAsset,
        icon: const Icon(Icons.add_rounded),
        label: const Text('添加资产'),
      ),
      child: _creativeAssets.isEmpty
          ? const Padding(
              padding: EdgeInsets.symmetric(vertical: 18),
              child: Text('还没有项目或全局资产。先添加角色、场景或道具。'),
            )
          : Column(
              children: _creativeAssets.map(_buildCreativeAsset).toList(),
            ),
    );
  }

  Widget _buildCreativeAsset(CreativeAssetView asset) {
    final selected = _selectedCreativeAssetIds.contains(asset.id);
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        border: Border.all(color: selected ? AppColors.accent : AppColors.line),
        borderRadius: BorderRadius.circular(8),
        color: selected ? AppColors.accentSoft : Colors.white,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Checkbox(
                value: selected,
                onChanged: _busy
                    ? null
                    : (value) => setState(() {
                          if (value == true) {
                            _selectedCreativeAssetIds.add(asset.id);
                          } else {
                            _selectedCreativeAssetIds.remove(asset.id);
                          }
                        }),
              ),
              Expanded(
                child: Text(
                  asset.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
              ),
              _AssetTypeTag(asset.assetType),
              IconButton(
                key: ValueKey<String>('asset-delete-${asset.id}'),
                onPressed: _busy ? null : () => _deleteCreativeAsset(asset),
                tooltip: '删除资产',
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.delete_outline_rounded, size: 19),
              ),
            ],
          ),
          Text(
            asset.description,
            maxLines: 3,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          if (asset.isCharacter && asset.personality.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              '性格：${asset.personality}',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ],
          if (asset.currentPrimaryAssetId != null ||
              asset.currentThreeViewAssetId != null) ...[
            const SizedBox(height: 12),
            _buildGeneratedAssetPreviews(asset),
          ],
          const SizedBox(height: 9),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _AssetState(
                label: '参考图',
                ready: asset.currentPrimaryAssetId != null,
              ),
              if (asset.isCharacter)
                _AssetState(
                  label: '三视图',
                  ready: asset.currentThreeViewAssetId != null,
                ),
            ],
          ),
          const SizedBox(height: 9),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              OutlinedButton.icon(
                key: ValueKey<String>('asset-upload-reference-${asset.id}'),
                onPressed: _busy || _uploadingAssetReferenceId != null
                    ? null
                    : () => _uploadCreativeAssetReference(asset),
                icon: _uploadingAssetReferenceId == asset.id
                    ? const SizedBox(
                        width: 17,
                        height: 17,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.upload_file_outlined, size: 17),
                label: const Text('上传参考图'),
              ),
              if (asset.isCharacter &&
                  asset.currentPrimaryAssetId == null &&
                  asset.currentThreeViewAssetId == null)
                FilledButton.tonalIcon(
                  key: ValueKey<String>('asset-generate-set-${asset.id}'),
                  onPressed: _busy ? null : () => _generateCharacterSet(asset),
                  icon: const Icon(Icons.auto_awesome_rounded, size: 17),
                  label: const Text('生成角色整套'),
                ),
              OutlinedButton.icon(
                key: ValueKey<String>('asset-generate-primary-${asset.id}'),
                onPressed: _busy ? null : () => _generatePrimaryAsset(asset),
                icon: const Icon(Icons.refresh_rounded, size: 17),
                label: Text(
                  asset.currentPrimaryAssetId == null
                      ? (asset.isCharacter ? '生成参考图' : '生成资产图片')
                      : (asset.isCharacter ? '重生成参考图' : '重生成资产图片'),
                ),
              ),
              if (asset.isCharacter)
                OutlinedButton.icon(
                  key:
                      ValueKey<String>('asset-generate-three-view-${asset.id}'),
                  onPressed: _busy || asset.currentPrimaryAssetId == null
                      ? null
                      : () => _generateThreeViewAsset(asset),
                  icon: const Icon(Icons.view_week_outlined, size: 17),
                  label: Text(
                    asset.currentThreeViewAssetId == null ? '生成三视图' : '重生成三视图',
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildGeneratedAssetPreviews(CreativeAssetView asset) {
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: [
        if (asset.currentPrimaryAssetId != null)
          _GeneratedAssetPreview(
            label: asset.isCharacter ? '视频参考图' : '资产图片',
            url: widget.data.api.assetContentUrl(asset.currentPrimaryAssetId!),
            onTap: () => _showGeneratedAssetPreview(
              asset.currentPrimaryAssetId!,
              asset.isCharacter ? '视频参考图' : '资产图片',
            ),
          ),
        if (asset.currentThreeViewAssetId != null)
          _GeneratedAssetPreview(
            label: '角色三视图',
            url:
                widget.data.api.assetContentUrl(asset.currentThreeViewAssetId!),
            onTap: () => _showGeneratedAssetPreview(
              asset.currentThreeViewAssetId!,
              '角色三视图',
            ),
          ),
      ],
    );
  }

  Future<void> _showGeneratedAssetPreview(
    String assetId,
    String title,
  ) =>
      showDialog<void>(
        context: context,
        builder: (context) => Dialog(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 920, maxHeight: 720),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 10, 8, 8),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          title,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      IconButton(
                        onPressed: () => Navigator.of(context).pop(),
                        tooltip: '关闭',
                        icon: const Icon(Icons.close_rounded),
                      ),
                    ],
                  ),
                ),
                Flexible(
                  child: InteractiveViewer(
                    minScale: 0.5,
                    maxScale: 4,
                    child: Image.network(
                      widget.data.api.assetContentUrl(assetId),
                      fit: BoxFit.contain,
                      errorBuilder: (_, __, ___) => const SizedBox(
                        width: 320,
                        height: 240,
                        child: Center(
                          child: Icon(Icons.broken_image_outlined, size: 42),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      );

  Future<void> _uploadCreativeAssetReference(CreativeAssetView asset) async {
    if (_busy || _uploadingAssetReferenceId != null) return;
    setState(() {
      _busy = true;
      _uploadingAssetReferenceId = asset.id;
      _error = null;
      _status = '正在上传资产参考图';
    });
    try {
      final uploaded = await widget.data.pickImagesAndUpload(
        maxFiles: 1,
        mimeTypes: const ['image/*'],
        allowedExtensions: const ['.png', '.jpg', '.jpeg', '.webp'],
        maxSizeBytes: 20 * 1024 * 1024,
      );
      if (uploaded.isEmpty || !mounted) return;
      final updated = await widget.data.api.updateCreativeAsset(
        asset.id,
        currentPrimaryAssetId: uploaded.first.id,
        clearCurrentThreeViewAsset: asset.isCharacter,
      );
      _replaceCreativeAsset(updated);
    } catch (exception) {
      _showError(exception);
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _uploadingAssetReferenceId = null;
          _status = null;
        });
      }
    }
  }

  Future<void> _pickFrame({required bool first}) async {
    setState(() => _uploading = true);
    try {
      final uploaded = await widget.data.pickImagesAndUpload(
        maxFiles: 1,
        mimeTypes: const ['image/*'],
        allowedExtensions: const ['.png', '.jpg', '.jpeg', '.webp'],
        maxSizeBytes: 20 * 1024 * 1024,
      );
      if (uploaded.isNotEmpty && mounted) {
        setState(() {
          if (first) {
            _firstFrame = uploaded.first;
          } else {
            _lastFrame = uploaded.first;
          }
        });
      }
    } catch (exception) {
      _showError(exception);
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _submitSimple() async {
    if (_promptController.text.trim().isEmpty) {
      _showError(const ApiException('请输入视频描述'));
      return;
    }
    if (!_validateFrameInputsForSelectedModel()) return;
    if (!await _confirmFee('将调用视频模型生成一条视频，可能产生模型费用。')) {
      return;
    }
    await _runOperation(
      operation: 'SIMPLE_GENERATE',
      title: _taskTitleController.text.trim(),
      parameters: {
        'mode': 'simple',
        'operation': 'SIMPLE_GENERATE',
        'prompt': _promptController.text.trim(),
        ..._videoParameters(),
      },
      inputAssetIds: _frameInputIds(),
      openResult: true,
    );
  }

  Future<void> _breakdownScript() async {
    if (_scriptController.text.trim().isEmpty) {
      _showError(const ApiException('请输入剧本'));
      return;
    }
    if (!await _confirmFee('将调用文本模型拆分剧本并生成可编辑分镜。')) {
      return;
    }
    final result = await _runOperation(
      operation: 'BREAKDOWN_SCRIPT',
      title: _taskTitleController.text.trim(),
      parameters: {
        'mode': 'expert',
        'operation': 'BREAKDOWN_SCRIPT',
        'script': _scriptController.text.trim(),
        ..._videoParameters(),
      },
      openResult: false,
    );
    if (result == null || !mounted) return;
    _readStoryboard(result.artifact);
    setState(() => _storyboardArtifactId = result.artifact.id);
  }

  void _addStoryboardShot(int afterIndex) {
    try {
      final updated = VideoStoryboardEditing.addAfter(
        _storyboard,
        afterIndex,
        _durationSeconds.toDouble(),
      );
      setState(() {
        _storyboard
          ..clear()
          ..addAll(updated);
      });
    } on FormatException catch (exception) {
      _showError(ApiException(exception.message));
    }
  }

  void _deleteStoryboardShot(int index) {
    try {
      final updated = VideoStoryboardEditing.deleteAt(
        _storyboard,
        index,
        _durationSeconds.toDouble(),
      );
      setState(() {
        _storyboard
          ..clear()
          ..addAll(updated);
      });
    } on FormatException catch (exception) {
      _showError(ApiException(exception.message));
    }
  }

  void _moveStoryboardShot(int from, int to) {
    final updated = VideoStoryboardEditing.move(
      _storyboard,
      from,
      to,
      _durationSeconds.toDouble(),
    );
    setState(() {
      _storyboard
        ..clear()
        ..addAll(updated);
    });
  }

  Future<bool> _saveStoryboard() async {
    try {
      _validateLocalStoryboard();
    } catch (exception) {
      _showError(exception);
      return false;
    }
    final result = await _runOperation(
      operation: 'SAVE_STORYBOARD',
      title: _taskTitleController.text.trim(),
      parameters: {
        'mode': 'expert',
        'operation': 'SAVE_STORYBOARD',
        'script': _scriptController.text.trim(),
        'storyboard': _storyboard,
        ..._videoParameters(),
      },
      baseArtifactId: _storyboardArtifactId,
      openResult: false,
    );
    if (result != null && mounted) {
      setState(() => _storyboardArtifactId = result.artifact.id);
      return true;
    }
    return false;
  }

  Future<void> _generateCharacterSet(CreativeAssetView asset) async {
    if (asset.isCharacter &&
        _selectedModelOption('IMAGE_GENERATION')?.supportsReferenceImages ==
            false) {
      _showError(
        const ApiException('当前图片模型不支持参考图，无法生成一致的角色三视图，请切换图片模型。'),
      );
      return;
    }
    if (!await _confirmFee(
      asset.isCharacter
          ? '将先生成角色正面参考图，再生成一张正面、侧面、背面三栏三视图。'
          : '将生成一张资产参考图，可能产生模型费用。',
    )) {
      return;
    }
    final primary = await _runOperation(
      operation: 'GENERATE_ASSET_PRIMARY',
      title: '${asset.name}参考图',
      parameters: {
        'mode': 'expert',
        'operation': 'GENERATE_ASSET_PRIMARY',
        'assetType': asset.assetType,
        'assetName': asset.name,
        'assetDescription': asset.description,
        'personality': asset.personality,
        'creativeAssetId': asset.id,
      },
      openResult: false,
      showExecutionPage: false,
    );
    if (primary == null || !mounted) return;
    final primaryAssetId = _firstArtifactAssetId(primary.artifact);
    if (primaryAssetId == null) {
      _showError(const ApiException('资产参考图没有返回文件成果'));
      return;
    }
    var updated = await widget.data.api.updateCreativeAsset(
      asset.id,
      currentPrimaryAssetId: primaryAssetId,
      clearCurrentThreeViewAsset: asset.isCharacter,
    );
    if (asset.isCharacter && mounted) {
      final threeView = await _runOperation(
        operation: 'GENERATE_CHARACTER_THREE_VIEW',
        title: '${asset.name}角色三视图',
        parameters: {
          'mode': 'expert',
          'operation': 'GENERATE_CHARACTER_THREE_VIEW',
          'assetType': 'CHARACTER',
          'assetName': asset.name,
          'assetDescription': asset.description,
          'personality': asset.personality,
          'creativeAssetId': asset.id,
        },
        inputAssetIds: [primaryAssetId],
        openResult: false,
        showExecutionPage: false,
      );
      if (threeView != null) {
        final threeViewAssetId = _firstArtifactAssetId(threeView.artifact);
        if (threeViewAssetId != null) {
          updated = await widget.data.api.updateCreativeAsset(
            asset.id,
            currentPrimaryAssetId: primaryAssetId,
            currentThreeViewAssetId: threeViewAssetId,
          );
        }
      }
    }
    if (mounted) {
      setState(() {
        final index = _creativeAssets.indexWhere((item) => item.id == asset.id);
        if (index >= 0) _creativeAssets[index] = updated;
      });
    }
  }

  Future<void> _generatePrimaryAsset(CreativeAssetView asset) async {
    if (!await _confirmFee(
      asset.isCharacter ? '将生成一张角色正面参考图，可能产生模型费用。' : '将生成一张资产参考图，可能产生模型费用。',
    )) {
      return;
    }
    final primary = await _runOperation(
      operation: 'GENERATE_ASSET_PRIMARY',
      title: '${asset.name}参考图',
      parameters: {
        'mode': 'expert',
        'operation': 'GENERATE_ASSET_PRIMARY',
        'assetType': asset.assetType,
        'assetName': asset.name,
        'assetDescription': asset.description,
        'personality': asset.personality,
        'creativeAssetId': asset.id,
      },
      openResult: false,
      showExecutionPage: false,
    );
    if (primary == null || !mounted) return;
    final primaryAssetId = _firstArtifactAssetId(primary.artifact);
    if (primaryAssetId == null) {
      _showError(const ApiException('资产参考图没有返回文件成果'));
      return;
    }
    final updated = await widget.data.api.updateCreativeAsset(
      asset.id,
      currentPrimaryAssetId: primaryAssetId,
      clearCurrentThreeViewAsset: asset.isCharacter,
    );
    _replaceCreativeAsset(updated);
  }

  Future<void> _generateThreeViewAsset(CreativeAssetView asset) async {
    if (!asset.isCharacter) return;
    final primaryAssetId = asset.currentPrimaryAssetId;
    if (primaryAssetId == null) {
      _showError(const ApiException('请先生成角色参考图'));
      return;
    }
    if (_selectedModelOption('IMAGE_GENERATION')?.supportsReferenceImages ==
        false) {
      _showError(
        const ApiException('当前图片模型不支持参考图，无法生成一致的角色三视图，请切换图片模型。'),
      );
      return;
    }
    if (!await _confirmFee('将基于当前角色参考图生成一张正面、侧面、背面三栏三视图。')) {
      return;
    }
    final threeView = await _runOperation(
      operation: 'GENERATE_CHARACTER_THREE_VIEW',
      title: '${asset.name}角色三视图',
      parameters: {
        'mode': 'expert',
        'operation': 'GENERATE_CHARACTER_THREE_VIEW',
        'assetType': 'CHARACTER',
        'assetName': asset.name,
        'assetDescription': asset.description,
        'personality': asset.personality,
        'creativeAssetId': asset.id,
      },
      inputAssetIds: [primaryAssetId],
      openResult: false,
      showExecutionPage: false,
    );
    if (threeView == null || !mounted) return;
    final threeViewAssetId = _firstArtifactAssetId(threeView.artifact);
    if (threeViewAssetId == null) {
      _showError(const ApiException('角色三视图没有返回文件成果'));
      return;
    }
    final updated = await widget.data.api.updateCreativeAsset(
      asset.id,
      currentPrimaryAssetId: primaryAssetId,
      currentThreeViewAssetId: threeViewAssetId,
    );
    _replaceCreativeAsset(updated);
  }

  void _replaceCreativeAsset(CreativeAssetView updated) {
    if (!mounted) return;
    setState(() {
      final index = _creativeAssets.indexWhere((item) => item.id == updated.id);
      if (index >= 0) _creativeAssets[index] = updated;
    });
  }

  void _upsertCreativeAsset(CreativeAssetView updated) {
    if (!mounted) return;
    setState(() {
      final index = _creativeAssets.indexWhere((item) => item.id == updated.id);
      if (index >= 0) {
        _creativeAssets[index] = updated;
      } else {
        _creativeAssets.insert(0, updated);
      }
    });
  }

  Future<void> _generateFinalVideo() async {
    if (!await _saveStoryboard()) return;
    if (!_validateFrameInputsForSelectedModel()) return;
    final inputIds = _frameInputIds().toList(growable: true);
    final catalog = <Map<String, Object?>>[
      if (_firstFrame != null)
        {
          'id': 'first-frame-${_firstFrame!.id}',
          'name': _firstFrame!.name,
          'assetType': 'FIRST_FRAME',
          'primaryAssetId': _firstFrame!.id,
        },
      if (_lastFrame != null)
        {
          'id': 'last-frame-${_lastFrame!.id}',
          'name': _lastFrame!.name,
          'assetType': 'LAST_FRAME',
          'primaryAssetId': _lastFrame!.id,
        },
    ];
    final referencedIds = <String>{
      ..._selectedCreativeAssetIds,
      for (final shot in _storyboard) ..._stringList(shot['assetRefs']),
    };
    for (final asset in _creativeAssets) {
      if (!referencedIds.contains(asset.id)) continue;
      final primary = asset.preferredVideoAssetId;
      if (primary == null) continue;
      if (!inputIds.contains(primary)) inputIds.add(primary);
      catalog.add({
        'id': asset.id,
        'name': asset.name,
        'assetType': asset.assetType,
        'description': asset.description,
        'personality': asset.personality,
        'primaryAssetId': primary,
        'threeViewAssetId': asset.currentThreeViewAssetId,
      });
    }
    final videoOption = _selectedModelOption('VIDEO_GENERATION');
    final maxReferences = videoOption?.maxReferenceImages;
    if (maxReferences != null && inputIds.length > maxReferences) {
      _showError(ApiException(
        '当前视频模型最多接收 $maxReferences 张参考图，请减少分镜中的资产引用。',
      ));
      return;
    }
    if (!await _confirmFee(
      '将把完整分镜作为一次请求提交给视频模型，可能产生模型费用。',
    )) {
      return;
    }
    await _runOperation(
      operation: 'GENERATE_VIDEO',
      title: _taskTitleController.text.trim(),
      parameters: {
        'mode': 'expert',
        'operation': 'GENERATE_VIDEO',
        'storyboard': _storyboard,
        'assetCatalog': catalog,
        ..._videoParameters(),
      },
      inputAssetIds: inputIds,
      openResult: true,
    );
  }

  Future<TaskExecutionResult?> _runOperation({
    required String operation,
    required String title,
    required Map<String, Object?> parameters,
    List<String> inputAssetIds = const [],
    String? baseArtifactId,
    required bool openResult,
    bool showExecutionPage = true,
  }) async {
    if (_busy) return null;
    if (_assetOperationController != null) {
      _assetOperationController?.dispose();
      _assetOperationController = null;
      _assetOperationLabel = null;
    }
    if (!mounted) return null;
    setState(() {
      _busy = true;
      _error = null;
      _status = '正在准备任务';
    });
    final controller = TaskExecutionController(
      initialStatus: '正在准备任务',
      onCancelRun: widget.data.api.cancelRun,
      loadRunOutput: widget.data.api.getRunOutput,
    );
    Future<void>? executionPage;
    if (showExecutionPage) {
      executionPage = Navigator.of(context).push<void>(
        MaterialPageRoute<void>(
          builder: (context) => TaskExecutionPage(
            title: widget.feature.title,
            controller: controller,
            openResult: false,
            resultRouteBuilder: (_) => MaterialPageRoute<void>(
                builder: (_) => const SizedBox.shrink()),
          ),
        ),
      );
    } else {
      setState(() {
        _assetOperationController = controller;
        _assetOperationLabel = title.isEmpty ? '资产生成' : title;
      });
    }
    try {
      final result = await widget.data.api.executeFeature(
        feature: widget.feature,
        taskTitle: title.isEmpty ? 'AI视频生成' : title,
        projectId: _projectId,
        existingTaskId: _taskId,
        baseArtifactId: baseArtifactId,
        selectedModels: _selectedModels,
        parameters: parameters,
        inputAssetIds: inputAssetIds,
        onStatus: (value) {
          controller.updateStatus(value);
          if (mounted) setState(() => _status = value);
        },
        onRunCreated: controller.attachRun,
      );
      _taskId = result.taskId;
      controller.complete(result);
      if (!showExecutionPage && mounted) {
        setState(() {
          _assetOperationController = null;
          _assetOperationLabel = null;
        });
        controller.dispose();
      }
      if (executionPage != null) await executionPage;
      if (openResult && mounted) {
        await openArtifactResultPage(
          context,
          data: widget.data,
          artifact: result.artifact,
          rendererKey: result.feature.rendererKey,
        );
      }
      if (mounted) {
        setState(() {
          _busy = false;
          _status = null;
        });
      }
      return result;
    } catch (exception) {
      controller.fail(_message(exception));
      if (mounted) {
        setState(() {
          _busy = false;
          _status = null;
          _error = _message(exception);
          if (!showExecutionPage) {
            _assetOperationController = null;
            _assetOperationLabel = null;
          }
        });
        if (!showExecutionPage) controller.dispose();
        if (showExecutionPage) {
          Future<void>.delayed(const Duration(milliseconds: 250), () {
            if (mounted && Navigator.of(context).canPop()) {
              Navigator.of(context).pop();
            }
          });
        }
      }
      return null;
    }
  }

  Future<void> _showAssetOperationStatus() async {
    final controller = _assetOperationController;
    if (controller == null) return;
    await showModalBottomSheet<void>(
      context: context,
      builder: (context) => SafeArea(
        child: AnimatedBuilder(
          animation: controller,
          builder: (context, _) => Padding(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _assetOperationLabel ?? '资产生成',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 10),
                Text(controller.status),
                const SizedBox(height: 16),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed:
                        controller.running ? () => controller.cancel() : null,
                    icon: const Icon(Icons.cancel_outlined),
                    label: Text(controller.cancelling ? '正在取消' : '取消生成'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _showSettings() async {
    final result = await showModalBottomSheet<_VideoSettings>(
      context: context,
      isScrollControlled: true,
      builder: (context) => _VideoSettingsSheet(
        durationSeconds: _durationSeconds,
        aspectRatio: _aspectRatio,
        resolution: _resolution,
        durationValues: _allowedValues('durationSeconds'),
        aspectRatioValues: _allowedValues('aspectRatio'),
        resolutionValues: _allowedValues('resolution'),
      ),
    );
    if (result == null || !mounted) return;
    final durationChanged = result.durationSeconds != _durationSeconds;
    setState(() {
      _durationSeconds = result.durationSeconds;
      _aspectRatio = result.aspectRatio;
      _resolution = result.resolution;
      if (durationChanged && _storyboard.isNotEmpty) {
        final updated = VideoStoryboardEditing.redistribute(
          _storyboard,
          _durationSeconds.toDouble(),
        );
        _storyboard
          ..clear()
          ..addAll(updated);
      }
    });
  }

  Future<void> _showModelSelection() async {
    final capabilities = _mode == 'simple'
        ? const ['VIDEO_GENERATION']
        : const [
            'TEXT_GENERATION',
            'IMAGE_GENERATION',
            'VIDEO_GENERATION',
          ];
    final result = await showModalBottomSheet<Map<String, String>>(
      context: context,
      isScrollControlled: true,
      builder: (context) => VideoModelSelectionSheet(
        feature: widget.feature,
        selectedModels: _selectedModels,
        capabilities: capabilities,
      ),
    );
    if (result == null || !mounted) return;
    setState(() {
      _selectedModels
        ..clear()
        ..addAll(result);
      _normalizeSettingsForModels();
    });
  }

  Future<void> _createCreativeAsset() async {
    final draft = await _showAssetDraftDialog();
    if (draft == null) return;
    if (draft.scope == 'PROJECT' && _projectId == null) {
      _showError(const ApiException('项目资产需要先选择项目'));
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final asset = await widget.data.api.createCreativeAsset(
        scope: draft.scope,
        assetType: draft.assetType,
        name: draft.name,
        description: draft.description,
        personality: draft.personality,
        projectId: _projectId,
      );
      if (mounted) {
        _upsertCreativeAsset(asset);
      }
    } catch (exception) {
      _showError(exception);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _deleteCreativeAsset(CreativeAssetView asset) async {
    final referencedShots = _storyboard
        .where((shot) => _stringList(shot['assetRefs']).contains(asset.id))
        .length;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('删除“${asset.name}”？'),
        content: Text(
          referencedShots == 0
              ? '资产将从资产库移除。已生成图片和历史任务成果仍会保留。'
              : '该资产正被 $referencedShots 个分镜引用。删除后会同时移除当前分镜中的引用，已生成图片和历史任务成果仍会保留。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.data.api.deleteCreativeAsset(asset.id);
      if (!mounted) return;
      setState(() {
        _creativeAssets.removeWhere((item) => item.id == asset.id);
        _selectedCreativeAssetIds.remove(asset.id);
        for (final shot in _storyboard) {
          final refs = _stringList(shot['assetRefs'])
            ..removeWhere((id) => id == asset.id);
          shot['assetRefs'] = refs;
        }
      });
    } catch (exception) {
      _showError(exception);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _editShotAssets(int index) async {
    final selected =
        Set<String>.from(_stringList(_storyboard[index]['assetRefs']));
    final result = await showDialog<Set<String>>(
      context: context,
      builder: (context) => _AssetReferenceDialog(
        assets: _creativeAssets,
        selected: selected,
        maxCount: _selectedModelOption('VIDEO_GENERATION')?.maxReferenceImages,
      ),
    );
    if (result != null && mounted) {
      setState(() => _storyboard[index]['assetRefs'] = result.toList());
    }
  }

  Future<AssetDraft?> _showAssetDraftDialog() {
    return showDialog<AssetDraft>(
      context: context,
      builder: (context) => AssetDraftDialog(hasProject: _projectId != null),
    );
  }

  Map<String, Object?> _videoParameters() => {
        'durationSeconds': _durationSeconds,
        'aspectRatio': _aspectRatio,
        'resolution': _resolution,
        if (_firstFrame != null) 'firstFrameAssetId': _firstFrame!.id,
        if (_lastFrame != null) 'lastFrameAssetId': _lastFrame!.id,
      };

  List<String> _frameInputIds() => [
        if (_firstFrame != null) _firstFrame!.id,
        if (_lastFrame != null && _lastFrame!.id != _firstFrame?.id)
          _lastFrame!.id,
      ];

  bool _canUseFrame({required bool first}) {
    final maximum =
        _selectedModelOption('VIDEO_GENERATION')?.maxReferenceImages;
    if (maximum == null) return first;
    if (first) return maximum >= 1;
    return maximum >= 2;
  }

  String _frameCapabilityLabel() {
    final maximum =
        _selectedModelOption('VIDEO_GENERATION')?.maxReferenceImages;
    if (maximum == null) return '约束未声明 · 仅开放首帧';
    if (maximum == 0) return '当前模型不支持';
    if (maximum == 1) return '当前模型仅支持首帧';
    return '可选 · 支持首帧和尾帧';
  }

  bool _validateFrameInputsForSelectedModel() {
    if (_firstFrame != null &&
        _lastFrame != null &&
        _firstFrame!.id == _lastFrame!.id) {
      _showError(const ApiException('首帧和尾帧不能使用同一张图片'));
      return false;
    }
    final count = _frameInputIds().length;
    final maximum =
        _selectedModelOption('VIDEO_GENERATION')?.maxReferenceImages;
    if (maximum == null && _lastFrame != null) {
      _showError(const ApiException('当前视频模型未声明尾帧能力，请移除尾帧或切换模型'));
      return false;
    }
    if (maximum != null && count > maximum) {
      _showError(ApiException(
        maximum == 0 ? '当前视频模型不支持首帧或尾帧图片' : '当前视频模型最多接收 $maximum 张首帧/尾帧图片',
      ));
      return false;
    }
    return true;
  }

  List<String> _allowedValues(String field) {
    final values = widget.feature.enumValuesFor(field, _selectedModels);
    if (values.isNotEmpty) return values;
    return switch (field) {
      'durationSeconds' => const ['4', '8', '12'],
      'aspectRatio' => const ['16:9', '9:16'],
      'resolution' => const ['720p'],
      _ => const [],
    };
  }

  ModelOption? _selectedModelOption(String capability) {
    final selected = _selectedModels[capability];
    final policy = widget.feature.modelPolicies
        .where((item) => item.capability == capability)
        .firstOrNull;
    return policy?.options
        .where((option) => option.code == (selected ?? policy.defaultModelCode))
        .firstOrNull;
  }

  String _modelLabel(String capability) {
    final option = _selectedModelOption(capability);
    return option?.displayName ?? _selectedModels[capability] ?? '默认模型';
  }

  String _capabilityShortLabel(String capability) => switch (capability) {
        'TEXT_GENERATION' => '分镜文本',
        'IMAGE_GENERATION' => '资产图片',
        'VIDEO_GENERATION' => '视频',
        _ => capability,
      };

  void _normalizeSettingsForModels() {
    final previousDuration = _durationSeconds;
    final durations = _allowedValues('durationSeconds');
    final ratios = _allowedValues('aspectRatio');
    final resolutions = _allowedValues('resolution');
    if (!durations.contains('$_durationSeconds') && durations.isNotEmpty) {
      _durationSeconds = int.tryParse(durations.first) ?? _durationSeconds;
    }
    if (!ratios.contains(_aspectRatio) && ratios.isNotEmpty) {
      _aspectRatio = ratios.first;
    }
    if (!resolutions.contains(_resolution) && resolutions.isNotEmpty) {
      _resolution = resolutions.first;
    }
    if (_durationSeconds != previousDuration && _storyboard.isNotEmpty) {
      final updated = VideoStoryboardEditing.redistribute(
        _storyboard,
        _durationSeconds.toDouble(),
      );
      _storyboard
        ..clear()
        ..addAll(updated);
    }
  }

  void _readStoryboard(ArtifactView artifact) {
    final raw = artifact.content['shots'];
    if (raw is! List) return;
    _storyboard
      ..clear()
      ..addAll(
        raw.whereType<Map>().map(
              (item) => Map<String, Object?>.from(item),
            ),
      );
  }

  void _validateLocalStoryboard() {
    if (_storyboard.isEmpty) throw const ApiException('请先生成或编辑分镜');
    var previousEnd = 0.0;
    for (var index = 0; index < _storyboard.length; index++) {
      final shot = _storyboard[index];
      final start = double.tryParse('${shot['startSecond']}');
      final end = double.tryParse('${shot['endSecond']}');
      if (start == null ||
          end == null ||
          end <= start ||
          (start - previousEnd).abs() > 0.001 ||
          (index == 0 && start != 0)) {
        throw const ApiException('分镜时间必须从 0 开始并连续递增');
      }
      if ('${shot['shotDescription']}'.trim().isEmpty ||
          '${shot['visualAction']}'.trim().isEmpty) {
        throw const ApiException('每个分镜都需要镜头描述和画面动作');
      }
      previousEnd = end;
    }
    if ((previousEnd - _durationSeconds).abs() > 0.001) {
      throw const ApiException('最后一个分镜必须结束在视频总时长');
    }
  }

  String _creativeName(String id) =>
      _creativeAssets
          .where((asset) => asset.id == id)
          .map((asset) => asset.name)
          .firstOrNull ??
      id;

  String? _firstArtifactAssetId(ArtifactView artifact) {
    final id = artifact.content['assetId']?.toString();
    if (id != null && id.isNotEmpty) return id;
    return artifact.assets.where((asset) => asset.available).firstOrNull?.id;
  }

  List<String> _stringList(Object? value) => value is List
      ? value
          .map((item) => item.toString())
          .where((item) => item.isNotEmpty)
          .toList()
      : <String>[];

  Future<bool> _confirmFee(String message) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认本次生成'),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('确认并继续'),
          ),
        ],
      ),
    );
    return result == true;
  }

  void _showError(Object exception) {
    if (!mounted) return;
    setState(() => _error = _message(exception));
  }

  static String _message(Object exception) =>
      exception.toString().replaceFirst('ApiException: ', '');
}

class _VideoSettings {
  const _VideoSettings({
    required this.durationSeconds,
    required this.aspectRatio,
    required this.resolution,
  });

  final int durationSeconds;
  final String aspectRatio;
  final String resolution;
}

class _VideoSettingsSheet extends StatefulWidget {
  const _VideoSettingsSheet({
    required this.durationSeconds,
    required this.aspectRatio,
    required this.resolution,
    required this.durationValues,
    required this.aspectRatioValues,
    required this.resolutionValues,
  });

  final int durationSeconds;
  final String aspectRatio;
  final String resolution;
  final List<String> durationValues;
  final List<String> aspectRatioValues;
  final List<String> resolutionValues;

  @override
  State<_VideoSettingsSheet> createState() => _VideoSettingsSheetState();
}

class _VideoSettingsSheetState extends State<_VideoSettingsSheet> {
  late int _duration;
  late String _ratio;
  late String _resolution;

  @override
  void initState() {
    super.initState();
    _duration = widget.durationSeconds;
    _ratio = widget.aspectRatio;
    _resolution = widget.resolution;
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(
              child: Container(
                width: 38,
                height: 4,
                decoration: BoxDecoration(
                  color: AppColors.line,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 18),
            Text('视频设置', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            _dropdown(
              label: '时长',
              value: '$_duration',
              values: _values('durationSeconds'),
              onChanged: (value) =>
                  setState(() => _duration = int.tryParse(value) ?? _duration),
              suffix: '秒',
            ),
            const SizedBox(height: 12),
            _dropdown(
              label: '比例',
              value: _ratio,
              values: _values('aspectRatio'),
              onChanged: (value) => setState(() => _ratio = value),
            ),
            const SizedBox(height: 12),
            _dropdown(
              label: '分辨率',
              value: _resolution,
              values: _values('resolution'),
              onChanged: (value) => setState(() => _resolution = value),
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: () => Navigator.of(context).pop(_VideoSettings(
                  durationSeconds: _duration,
                  aspectRatio: _ratio,
                  resolution: _resolution,
                )),
                child: const Text('应用设置'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _dropdown({
    required String label,
    required String value,
    required List<String> values,
    required void Function(String) onChanged,
    String? suffix,
  }) {
    final normalized = values.contains(value) ? value : values.first;
    return DropdownButtonFormField<String>(
      value: normalized,
      decoration: InputDecoration(
          labelText: suffix == null ? label : '$label（$suffix）'),
      items: values
          .map((item) => DropdownMenuItem(value: item, child: Text(item)))
          .toList(),
      onChanged:
          values.isEmpty ? null : (next) => onChanged(next ?? normalized),
    );
  }

  List<String> _values(String field) {
    return switch (field) {
      'durationSeconds' => widget.durationValues,
      'aspectRatio' => widget.aspectRatioValues,
      'resolution' => widget.resolutionValues,
      _ => const [''],
    };
  }
}

class VideoModelSelectionSheet extends StatefulWidget {
  const VideoModelSelectionSheet({
    super.key,
    required this.feature,
    required this.selectedModels,
    required this.capabilities,
  });

  final FeatureDetail feature;
  final Map<String, String> selectedModels;
  final List<String> capabilities;

  @override
  State<VideoModelSelectionSheet> createState() =>
      _VideoModelSelectionSheetState();
}

class _VideoModelSelectionSheetState extends State<VideoModelSelectionSheet> {
  late final Map<String, String> _models;

  @override
  void initState() {
    super.initState();
    _models = Map<String, String>.from(widget.selectedModels);
    for (final policy in widget.feature.modelPolicies) {
      final selected = _models[policy.capability];
      final valid = policy.options.any((option) => option.code == selected);
      _models[policy.capability] = valid
          ? selected!
          : policy.defaultModelCode.isNotEmpty
              ? policy.defaultModelCode
              : policy.options.firstOrNull?.code ?? '';
    }
  }

  @override
  Widget build(BuildContext context) {
    final policies =
        widget.capabilities.map(_policy).whereType<ModelPolicy>().toList();
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(
              child: Container(
                width: 38,
                height: 4,
                decoration: BoxDecoration(
                  color: AppColors.line,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 18),
            Text('模型选择', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            if (policies.isEmpty)
              const _ErrorBanner(message: '后端尚未返回当前功能的模型策略')
            else
              for (final policy in policies) ...[
                _buildSelector(policy),
                const SizedBox(height: 14),
              ],
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: policies.isEmpty
                    ? null
                    : () => Navigator.of(context).pop(_models),
                child: const Text('应用模型'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSelector(ModelPolicy policy) {
    final selected = _models[policy.capability] ?? policy.defaultModelCode;
    if (policy.options.isEmpty) {
      return InputDecorator(
        decoration: InputDecoration(
          labelText: _capabilityLabel(policy.capability),
        ),
        child: const Text('当前没有可用模型'),
      );
    }
    final normalized = policy.options.any((option) => option.code == selected)
        ? selected
        : policy.options.first.code;
    final current =
        policy.options.where((option) => option.code == normalized).firstOrNull;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        DropdownButtonFormField<String>(
          key: ValueKey<String>('video-model-${policy.capability}'),
          value: normalized,
          decoration: InputDecoration(
            labelText: _capabilityLabel(policy.capability),
          ),
          items: policy.options
              .map(
                (option) => DropdownMenuItem<String>(
                  value: option.code,
                  child: Text(option.displayName),
                ),
              )
              .toList(),
          onChanged: policy.allowUserSelection
              ? (value) {
                  if (value == null) return;
                  setState(() => _models[policy.capability] = value);
                }
              : null,
        ),
        if (current != null && current.description.isNotEmpty) ...[
          const SizedBox(height: 6),
          Text(
            current.description,
            style: const TextStyle(color: AppColors.muted, fontSize: 11),
          ),
        ],
      ],
    );
  }

  ModelPolicy? _policy(String capability) => widget.feature.modelPolicies
      .where((policy) => policy.capability == capability)
      .firstOrNull;

  static String _capabilityLabel(String capability) => switch (capability) {
        'TEXT_GENERATION' => '分镜文本模型',
        'IMAGE_GENERATION' => '资产图片模型',
        'VIDEO_GENERATION' => '视频生成模型',
        _ => capability,
      };
}

class AssetDraft {
  const AssetDraft({
    required this.scope,
    required this.assetType,
    required this.name,
    required this.description,
    required this.personality,
  });

  final String scope;
  final String assetType;
  final String name;
  final String description;
  final String personality;
}

class AssetDraftDialog extends StatefulWidget {
  const AssetDraftDialog({
    super.key,
    required this.hasProject,
  });

  final bool hasProject;

  @override
  State<AssetDraftDialog> createState() => _AssetDraftDialogState();
}

class _AssetDraftDialogState extends State<AssetDraftDialog> {
  final TextEditingController _name = TextEditingController();
  final TextEditingController _description = TextEditingController();
  final TextEditingController _personality = TextEditingController();

  String _type = 'CHARACTER';
  late String _scope;

  @override
  void initState() {
    super.initState();
    _scope = widget.hasProject ? 'PROJECT' : 'GLOBAL';
  }

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    _personality.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('添加资产'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            DropdownButtonFormField<String>(
              value: _type,
              decoration: const InputDecoration(labelText: '资产类型'),
              items: const [
                DropdownMenuItem(value: 'CHARACTER', child: Text('角色')),
                DropdownMenuItem(value: 'SCENE', child: Text('场景')),
                DropdownMenuItem(value: 'PROP', child: Text('道具')),
              ],
              onChanged: (value) => setState(() => _type = value ?? _type),
            ),
            DropdownButtonFormField<String>(
              value: _scope,
              decoration: const InputDecoration(labelText: '资产范围'),
              items: [
                if (widget.hasProject)
                  const DropdownMenuItem(
                    value: 'PROJECT',
                    child: Text('当前项目'),
                  ),
                const DropdownMenuItem(
                  value: 'GLOBAL',
                  child: Text('全局资产库'),
                ),
              ],
              onChanged: (value) => setState(() => _scope = value ?? _scope),
            ),
            TextField(
              key: const ValueKey<String>('asset-draft-name'),
              controller: _name,
              decoration: const InputDecoration(labelText: '名称'),
            ),
            const SizedBox(height: 10),
            TextField(
              key: const ValueKey<String>('asset-draft-description'),
              controller: _description,
              minLines: 3,
              maxLines: 6,
              decoration: const InputDecoration(labelText: '文字描述'),
            ),
            if (_type == 'CHARACTER') ...[
              const SizedBox(height: 10),
              TextField(
                key: const ValueKey<String>('asset-draft-personality'),
                controller: _personality,
                minLines: 2,
                maxLines: 4,
                decoration: const InputDecoration(labelText: '角色性格'),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: const ValueKey<String>('asset-draft-submit'),
          onPressed: _submit,
          child: const Text('添加'),
        ),
      ],
    );
  }

  void _submit() {
    if (_name.text.trim().isEmpty || _description.text.trim().isEmpty) {
      return;
    }
    if (_type == 'CHARACTER' && _personality.text.trim().isEmpty) {
      return;
    }
    Navigator.of(context).pop(AssetDraft(
      scope: _scope,
      assetType: _type,
      name: _name.text.trim(),
      description: _description.text.trim(),
      personality: _personality.text.trim(),
    ));
  }
}

class _AssetReferenceDialog extends StatefulWidget {
  const _AssetReferenceDialog({
    required this.assets,
    required this.selected,
    required this.maxCount,
  });

  final List<CreativeAssetView> assets;
  final Set<String> selected;
  final int? maxCount;

  @override
  State<_AssetReferenceDialog> createState() => _AssetReferenceDialogState();
}

class _AssetReferenceDialogState extends State<_AssetReferenceDialog> {
  late final Set<String> _selected;

  @override
  void initState() {
    super.initState();
    _selected = Set<String>.from(widget.selected);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('引用资产'),
      content: SizedBox(
        width: 520,
        child: widget.assets.isEmpty
            ? const Text('请先在资产库添加角色、场景或道具。')
            : ListView(
                shrinkWrap: true,
                children: widget.assets.map((asset) {
                  final canSelect = _selected.contains(asset.id) ||
                      widget.maxCount == null ||
                      _selected.length < widget.maxCount!;
                  return CheckboxListTile(
                    value: _selected.contains(asset.id),
                    enabled: canSelect,
                    title: Text(asset.name),
                    subtitle: Text(asset.assetType),
                    onChanged: (value) => setState(() {
                      if (value == true) {
                        _selected.add(asset.id);
                      } else {
                        _selected.remove(asset.id);
                      }
                    }),
                  );
                }).toList(),
              ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(_selected),
          child: const Text('应用'),
        ),
      ],
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child, this.trailing});

  final String title;
  final Widget child;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Expanded(
              child:
                  Text(title, style: Theme.of(context).textTheme.titleMedium),
            ),
            if (trailing != null) trailing!,
          ],
        ),
        const SizedBox(height: 10),
        child,
      ],
    );
  }
}

class _SettingPill extends StatelessWidget {
  const _SettingPill({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: AppColors.wash,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.line),
      ),
      child: Text('$label $value', style: const TextStyle(fontSize: 12)),
    );
  }
}

class _AssetTypeTag extends StatelessWidget {
  const _AssetTypeTag(this.value);

  final String value;

  @override
  Widget build(BuildContext context) {
    final label = switch (value) {
      'CHARACTER' => '角色',
      'SCENE' => '场景',
      'PROP' => '道具',
      _ => '其他',
    };
    return Text(
      label,
      style: const TextStyle(
        color: AppColors.accent,
        fontSize: 12,
        fontWeight: FontWeight.w700,
      ),
    );
  }
}

class _AssetState extends StatelessWidget {
  const _AssetState({required this.label, required this.ready});

  final String label;
  final bool ready;

  @override
  Widget build(BuildContext context) {
    return Chip(
      avatar: Icon(
        ready
            ? Icons.check_circle_outline_rounded
            : Icons.radio_button_unchecked,
        size: 16,
        color: ready ? AppColors.accent : AppColors.muted,
      ),
      label: Text(label),
      side: const BorderSide(color: AppColors.line),
      backgroundColor: Colors.white,
    );
  }
}

class _GeneratedAssetPreview extends StatelessWidget {
  const _GeneratedAssetPreview({
    required this.label,
    required this.url,
    required this.onTap,
  });

  final String label;
  final String url;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 156,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 6),
          InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(8),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: Image.network(
                url,
                width: 156,
                height: 112,
                fit: BoxFit.cover,
                loadingBuilder: (context, child, progress) => progress == null
                    ? child
                    : Container(
                        width: 156,
                        height: 112,
                        color: AppColors.wash,
                        alignment: Alignment.center,
                        child: const CircularProgressIndicator(strokeWidth: 2),
                      ),
                errorBuilder: (_, __, ___) => Container(
                  width: 156,
                  height: 112,
                  color: AppColors.wash,
                  alignment: Alignment.center,
                  child: const Icon(Icons.broken_image_outlined),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _AssetThumbnail extends StatelessWidget {
  const _AssetThumbnail({required this.asset, required this.url});

  final AssetView asset;
  final String url;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: Image.network(
        url,
        width: 106,
        height: 106,
        fit: BoxFit.cover,
        errorBuilder: (_, __, ___) => Container(
          width: 106,
          height: 106,
          color: AppColors.wash,
          alignment: Alignment.center,
          child: const Icon(Icons.broken_image_outlined),
        ),
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF3F3),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: const Color(0xFFF0CCCC)),
      ),
      child: Text(message, style: const TextStyle(color: AppColors.danger)),
    );
  }
}
