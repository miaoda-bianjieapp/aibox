import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../network/api_exception.dart';
import '../network/task_execution_result.dart';
import '../pages/writing_result_page.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';

Future<void> showTaskSheet(
  BuildContext context, {
  required AppDataController data,
  required TaskLaunchRequest request,
}) async {
  final result = await showModalBottomSheet<TaskExecutionResult>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    enableDrag: false,
    builder: (context) => _TaskSheetContent(data: data, request: request),
  );
  if (result != null && context.mounted) {
    await data.refresh();
    if (!context.mounted) return;
    await Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (context) => ArtifactResultPage(
        artifact: result.artifact,
        rendererKey: result.feature.rendererKey,
      ),
    ));
  }
}

class _TaskSheetContent extends StatefulWidget {
  const _TaskSheetContent({required this.data, required this.request});
  final AppDataController data;
  final TaskLaunchRequest request;
  @override
  State<_TaskSheetContent> createState() => _TaskSheetContentState();
}

class _TaskSheetContentState extends State<_TaskSheetContent> {
  late final TextEditingController _nameController;
  late final Future<FeatureDetail> _featureFuture;
  final Map<String, TextEditingController> _controllers = {};
  final Map<String, Object?> _values = {};
  final List<AssetView> _assets = [];
  String? _projectId;
  final Map<String, String> _selectedModels = {};
  String? _status;
  String? _error;
  bool _submitting = false;
  bool _initialized = false;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(
        text: widget.request.taskTitle ?? widget.request.entry.title);
    _projectId = widget.request.projectId;
    _assets.addAll(widget.data.assets
        .where((asset) => widget.request.initialAssetIds.contains(asset.id)));
    _featureFuture = widget.data.api.getFeature(widget.request.entry.id);
  }

  @override
  void dispose() {
    _nameController.dispose();
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.viewInsetsOf(context).bottom;
    return PopScope(
      canPop: !_submitting,
      child: AnimatedPadding(
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOut,
        padding: EdgeInsets.only(bottom: bottomInset),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 24),
          child: FutureBuilder<FeatureDetail>(
            future: _featureFuture,
            builder: (context, snapshot) {
              if (snapshot.hasData) _initialize(snapshot.requireData);
              return Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Center(
                    child: Container(
                      width: 36,
                      height: 4,
                      decoration: BoxDecoration(
                          color: AppColors.line,
                          borderRadius: BorderRadius.circular(2)),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Row(children: [
                    Expanded(
                        child: Text(widget.request.entry.title,
                            style: Theme.of(context).textTheme.titleLarge)),
                    IconButton(
                      onPressed: _submitting
                          ? null
                          : () => Navigator.of(context).pop(),
                      tooltip: '关闭',
                      icon: const Icon(Icons.close_rounded),
                    ),
                  ]),
                  const SizedBox(height: 6),
                  Text(widget.request.entry.description),
                  if (widget.request.isRevision) ...[
                    const SizedBox(height: 6),
                    Text(
                      '基于 v${widget.request.baseVersion ?? '-'} 继续修改',
                      style: const TextStyle(
                          color: AppColors.accent,
                          fontSize: 12,
                          fontWeight: FontWeight.w700),
                    ),
                  ],
                  const SizedBox(height: 20),
                  if (snapshot.connectionState != ConnectionState.done)
                    const Center(
                        child: Padding(
                            padding: EdgeInsets.all(20),
                            child: CircularProgressIndicator()))
                  else if (snapshot.hasError)
                    _ErrorMessage(message: '功能配置加载失败：${snapshot.error}')
                  else ...[
                    const _FieldLabel('任务名称', required: true),
                    TextField(
                      controller: _nameController,
                      enabled: !_submitting && !widget.request.isRevision,
                      decoration:
                          const InputDecoration(hintText: '用于在历史任务中识别这项工作'),
                    ),
                    if (widget.data.projects.isNotEmpty &&
                        !widget.request.isRevision) ...[
                      const SizedBox(height: 15),
                      const _FieldLabel('所属项目'),
                      DropdownButtonFormField<String?>(
                        value: _projectId,
                        decoration: const InputDecoration(hintText: '不归入项目'),
                        items: [
                          const DropdownMenuItem<String?>(
                              value: null, child: Text('不归入项目')),
                          ...widget.data.projects.map((project) =>
                              DropdownMenuItem<String?>(
                                  value: project.id,
                                  child: Text(project.name))),
                        ],
                        onChanged: _submitting
                            ? null
                            : (value) => setState(() => _projectId = value),
                      ),
                    ],
                    ..._buildModelSelectors(snapshot.requireData),
                    ..._buildFields(snapshot.requireData),
                  ],
                  if (_status != null) ...[
                    const SizedBox(height: 18),
                    const LinearProgressIndicator(minHeight: 3),
                    const SizedBox(height: 9),
                    Text(_status!,
                        style: const TextStyle(
                            color: AppColors.accent, fontSize: 12)),
                  ],
                  if (_error != null) ...[
                    const SizedBox(height: 14),
                    _ErrorMessage(message: _error!),
                  ],
                  const SizedBox(height: 18),
                  SizedBox(
                    width: double.infinity,
                    height: 48,
                    child: FilledButton.icon(
                      onPressed: snapshot.hasData && !_submitting
                          ? () => _execute(snapshot.requireData)
                          : null,
                      style: FilledButton.styleFrom(
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(8)),
                      ),
                      icon: const Icon(Icons.auto_awesome_rounded, size: 19),
                      label: Text(widget.request.isRevision ? '生成新版本' : '开始执行'),
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  void _initialize(FeatureDetail feature) {
    if (_initialized) return;
    _initialized = true;
    for (final policy in feature.modelPolicies) {
      final requested = widget.request.initialModels[policy.capability] ??
          (feature.modelPolicies.length == 1
              ? widget.request.initialModelCode
              : null);
      _selectedModels[policy.capability] =
          policy.options.any((item) => item.code == requested)
              ? requested!
              : policy.defaultModelCode;
    }
    for (final field in feature.fieldOrder) {
      final schema =
          Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
      final initial =
          widget.request.initialParameters[field] ?? schema['default'];
      if (schema['type'] == 'boolean') {
        _values[field] = initial == true;
      } else if (schema['enum'] is List) {
        final options =
            (schema['enum'] as List).map((value) => value.toString()).toList();
        _values[field] =
            initial?.toString() ?? (options.isEmpty ? null : options.first);
      } else {
        _controllers[field] =
            TextEditingController(text: initial?.toString() ?? '');
      }
    }
  }

  List<Widget> _buildModelSelectors(FeatureDetail feature) {
    final widgets = <Widget>[];
    for (final policy in feature.modelPolicies) {
      if (!policy.allowUserSelection || policy.options.length <= 1) continue;
      widgets.addAll(_buildModelSelector(policy));
    }
    return widgets;
  }

  List<Widget> _buildModelSelector(ModelPolicy policy) {
    final selectedCode = _selectedModels[policy.capability];
    final selected =
        policy.options.where((item) => item.code == selectedCode).firstOrNull;
    return [
      const SizedBox(height: 15),
      _FieldLabel(_modelCapabilityLabel(policy.capability)),
      DropdownButtonFormField<String>(
        value: selectedCode,
        isExpanded: true,
        items: policy.options
            .map((option) => DropdownMenuItem<String>(
                  value: option.code,
                  child: Row(children: [
                    Expanded(
                      child: Text(
                        option.displayName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    const SizedBox(width: 8),
                    _ModelSourceBadge(option.sourceLabel),
                  ]),
                ))
            .toList(),
        onChanged: _submitting
            ? null
            : (value) => setState(() {
                  if (value != null) {
                    _selectedModels[policy.capability] = value;
                  }
                }),
      ),
      if (selected != null) ...[
        const SizedBox(height: 6),
        Text(
          [selected.sourceName, selected.description]
              .where((value) => value.isNotEmpty)
              .join(' · '),
          style: const TextStyle(color: AppColors.muted, fontSize: 11),
        ),
      ],
    ];
  }

  List<Widget> _buildFields(FeatureDetail feature) {
    final fields = <Widget>[];
    for (final field in feature.fieldOrder) {
      final schema =
          Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
      final widgetType = feature.widgetFor(field) ?? 'text';
      fields
        ..add(const SizedBox(height: 15))
        ..add(_FieldLabel(schema['title']?.toString() ?? field,
            required: feature.requiredFields.contains(field)))
        ..add(_buildField(feature, field, schema, widgetType));
    }
    return fields;
  }

  Widget _buildField(FeatureDetail feature, String field,
      Map<String, dynamic> schema, String widgetType) {
    if (_isAssetField(schema, widgetType)) return _buildAssetField(widgetType);
    if (schema['type'] == 'boolean') {
      return SwitchListTile.adaptive(
        contentPadding: EdgeInsets.zero,
        title: Text(schema['description']?.toString() ?? '启用'),
        value: _values[field] == true,
        onChanged: _submitting
            ? null
            : (value) => setState(() => _values[field] = value),
      );
    }
    final options = schema['enum'];
    if (options is List) {
      final values = options.map((item) => item.toString()).toList();
      if (widgetType == 'segmented' && values.length <= 4) {
        return SizedBox(
          width: double.infinity,
          child: SegmentedButton<String>(
            segments: values
                .map((value) => ButtonSegment<String>(
                      value: value,
                      label: Text(feature.optionLabel(field, value)),
                    ))
                .toList(),
            selected: {_values[field]?.toString() ?? values.first},
            onSelectionChanged: _submitting
                ? null
                : (selection) =>
                    setState(() => _values[field] = selection.first),
            style: ButtonStyle(
              textStyle: const WidgetStatePropertyAll(TextStyle(fontSize: 12)),
              shape: WidgetStatePropertyAll(RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8))),
            ),
          ),
        );
      }
      return DropdownButtonFormField<String>(
        value: _values[field]?.toString(),
        items: values
            .map((value) => DropdownMenuItem(
                  value: value,
                  child: Text(feature.optionLabel(field, value)),
                ))
            .toList(),
        onChanged: _submitting
            ? null
            : (value) => setState(() => _values[field] = value),
      );
    }
    final multiline = widgetType == 'textarea';
    final numeric = schema['type'] == 'integer' || schema['type'] == 'number';
    return TextField(
      controller: _controllers[field],
      enabled: !_submitting,
      minLines: multiline ? 3 : 1,
      maxLines: multiline ? 6 : 1,
      keyboardType: numeric
          ? const TextInputType.numberWithOptions(decimal: true)
          : TextInputType.text,
      decoration: InputDecoration(hintText: schema['description']?.toString()),
    );
  }

  Widget _buildAssetField(String widgetType) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      ..._assets.map((asset) => ListTile(
            contentPadding: EdgeInsets.zero,
            leading:
                const Icon(Icons.attach_file_rounded, color: AppColors.accent),
            title:
                Text(asset.name, maxLines: 1, overflow: TextOverflow.ellipsis),
            trailing: IconButton(
              onPressed: _submitting
                  ? null
                  : () => setState(() => _assets.remove(asset)),
              tooltip: '移除',
              icon: const Icon(Icons.close_rounded),
            ),
          )),
      OutlinedButton.icon(
        onPressed: _submitting ? null : () => _pickAsset(widgetType),
        icon: const Icon(Icons.upload_file_outlined),
        label: const Text('选择并上传文件'),
      ),
    ]);
  }

  Future<void> _pickAsset(String widgetType) async {
    final mimeTypes = switch (widgetType) {
      'image' => <String>['image/*'],
      'audio' => <String>['audio/*'],
      'video' => <String>['video/*'],
      _ => <String>['*/*'],
    };
    try {
      final asset = await widget.data.pickAndUpload(mimeTypes: mimeTypes);
      if (asset != null && mounted) setState(() => _assets.add(asset));
    } catch (exception) {
      if (mounted) setState(() => _error = '$exception');
    }
  }

  Future<void> _execute(FeatureDetail feature) async {
    final taskName = _nameController.text.trim();
    if (taskName.isEmpty) {
      setState(() => _error = '请填写任务名称');
      return;
    }
    final parameters = <String, Object?>{};
    for (final field in feature.fieldOrder) {
      final schema =
          Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
      final widgetType = feature.widgetFor(field) ?? 'text';
      if (_isAssetField(schema, widgetType)) continue;
      Object? value;
      if (schema['type'] == 'boolean' || schema['enum'] is List) {
        value = _values[field];
      } else {
        final raw = _controllers[field]?.text.trim() ?? '';
        value = switch (schema['type']) {
          'integer' => int.tryParse(raw),
          'number' => double.tryParse(raw),
          _ => raw,
        };
      }
      if (feature.requiredFields.contains(field) &&
          (value == null || value.toString().isEmpty)) {
        setState(() => _error = '请填写${schema['title'] ?? field}');
        return;
      }
      if (value != null && value.toString().isNotEmpty) {
        parameters[field] = value;
      }
    }

    setState(() {
      _submitting = true;
      _error = null;
      _status = '准备连接后端';
    });
    try {
      final result = await widget.data.api.executeFeature(
        feature: feature,
        taskTitle: taskName,
        projectId: _projectId,
        existingTaskId: widget.request.existingTaskId,
        baseArtifactId: widget.request.baseArtifactId,
        selectedModelCode:
            _selectedModels.length == 1 ? _selectedModels.values.first : null,
        selectedModels: _selectedModels,
        parameters: parameters,
        inputAssetIds: _assets.map((asset) => asset.id).toList(),
        onStatus: (status) {
          if (mounted) setState(() => _status = status);
        },
      );
      if (mounted) {
        Navigator.of(context).pop(result);
      }
    } on ApiException catch (exception) {
      if (mounted) {
        setState(() {
          _submitting = false;
          _status = null;
          _error = exception.message;
        });
      }
    } catch (exception) {
      if (mounted) {
        setState(() {
          _submitting = false;
          _status = null;
          _error = '$exception';
        });
      }
    }
  }

  static bool _isAssetField(Map<String, dynamic> schema, String widgetType) {
    return schema['format'] == 'binary' ||
        schema['type'] == 'asset' ||
        const {'file', 'image', 'audio', 'video'}.contains(widgetType);
  }
}

class _ModelSourceBadge extends StatelessWidget {
  const _ModelSourceBadge(this.label);

  final String label;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          color:
              label == '中转' ? const Color(0xFFFFF2D8) : const Color(0xFFE9F5EF),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: label == '中转'
                ? const Color(0xFF8A5A00)
                : const Color(0xFF246B4A),
            fontSize: 10,
            fontWeight: FontWeight.w700,
          ),
        ),
      );
}

String _modelCapabilityLabel(String capability) => switch (capability) {
      'TEXT_GENERATION' => '文本模型',
      'VISION' => '视觉理解模型',
      'AUDIO_TRANSCRIPTION' => '音频转写模型',
      'IMAGE_GENERATION' => '图片生成模型',
      'TEXT_TO_SPEECH' => '语音生成模型',
      'VIDEO_GENERATION' => '视频生成模型',
      _ => '使用模型',
    };

class _FieldLabel extends StatelessWidget {
  const _FieldLabel(this.text, {this.required = false});
  final String text;
  final bool required;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 7),
        child: Text.rich(TextSpan(
          text: text,
          style: const TextStyle(
              color: AppColors.muted,
              fontSize: 12,
              fontWeight: FontWeight.w600),
          children: required
              ? const [
                  TextSpan(
                      text: ' *', style: TextStyle(color: Color(0xFFB33A32)))
                ]
              : const [],
        )),
      );
}

class _ErrorMessage extends StatelessWidget {
  const _ErrorMessage({required this.message});
  final String message;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
            color: const Color(0xFFFFF1F0),
            borderRadius: BorderRadius.circular(8)),
        child: Text(message,
            style: const TextStyle(color: Color(0xFFB33A32), fontSize: 12)),
      );
}
