import 'dart:async';

import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../network/api_exception.dart';
import '../network/native_file_picker.dart';
import '../network/task_execution_result.dart';
import '../pages/outline_result_page.dart';
import '../pages/task_execution_page.dart';
import '../pages/writing_result_page.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import 'image_mask_editor.dart';

Future<TaskExecutionResult?> showTaskSheet(
  BuildContext context, {
  required AppDataController data,
  required TaskLaunchRequest request,
  bool openResult = true,
}) async {
  final completion = Completer<_TaskSheetOutcome?>();
  final sheetFuture = showModalBottomSheet<_TaskSheetOutcome>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    enableDrag: false,
    builder: (context) => _TaskSheetContent(
      data: data,
      request: request,
      openResult: openResult,
      onCompleted: (outcome) {
        if (!completion.isCompleted) completion.complete(outcome);
      },
    ),
  );
  final outcome = await Future.any<_TaskSheetOutcome?>([
    sheetFuture,
    completion.future,
  ]);
  final result = outcome?.result;
  if (result != null && context.mounted) {
    await data.refresh();
    if (!context.mounted) return result;
    if (openResult && outcome?.resultPageOpened != true) {
      await openArtifactResultPage(
        context,
        data: data,
        artifact: result.artifact,
        rendererKey: result.feature.rendererKey,
      );
    }
  }
  return result;
}

Future<void> openArtifactResultPage(
  BuildContext context, {
  required AppDataController data,
  required ArtifactView artifact,
  required String? rendererKey,
  VoidCallback? onContinue,
}) async {
  await Navigator.of(context).push(_artifactResultRoute(
    data: data,
    artifact: artifact,
    rendererKey: rendererKey,
    onContinue: onContinue,
  ));
}

Route<void> _artifactResultRoute({
  required AppDataController data,
  required ArtifactView artifact,
  required String? rendererKey,
  VoidCallback? onContinue,
}) {
  if (rendererKey == 'outline_text_editor') {
    return MaterialPageRoute<void>(
      builder: (pageContext) => OutlineResultPage(
        artifact: artifact,
        onExecuteVersion: ({
          required baseArtifact,
          required operation,
          editedText,
          required onStatus,
        }) =>
            executeOutlineVersion(
          data: data,
          baseArtifact: baseArtifact,
          operation: operation,
          editedText: editedText,
          onStatus: onStatus,
        ),
        onAdjustInput: (baseArtifact) async {
          final request = await buildOutlineLaunchRequest(
            data: data,
            baseArtifact: baseArtifact,
          );
          if (!pageContext.mounted) return null;
          return showTaskSheet(
            pageContext,
            data: data,
            request: request,
            openResult: false,
          );
        },
      ),
    );
  }
  return MaterialPageRoute<void>(
    builder: (context) => ArtifactResultPage(
      artifact: artifact,
      rendererKey: rendererKey,
      onContinue: onContinue,
    ),
  );
}

class _TaskSheetContent extends StatefulWidget {
  const _TaskSheetContent({
    required this.data,
    required this.request,
    required this.openResult,
    required this.onCompleted,
  });
  final AppDataController data;
  final TaskLaunchRequest request;
  final bool openResult;
  final ValueChanged<_TaskSheetOutcome> onCompleted;
  @override
  State<_TaskSheetContent> createState() => _TaskSheetContentState();
}

class _TaskSheetOutcome {
  const _TaskSheetOutcome({
    required this.result,
    required this.resultPageOpened,
  });

  final TaskExecutionResult result;
  final bool resultPageOpened;
}

class _TaskSheetContentState extends State<_TaskSheetContent> {
  late final TextEditingController _nameController;
  late final Future<FeatureDetail> _featureFuture;
  final Map<String, TextEditingController> _controllers = {};
  final Map<String, Object?> _values = {};
  final Map<String, List<AssetView>> _assetsByField = {};
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
                    if (snapshot.requireData.feeNotice != null) ...[
                      const SizedBox(height: 16),
                      _FeeNotice(text: snapshot.requireData.feeNotice!),
                    ],
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
                  if (snapshot.hasData)
                    _buildActions(snapshot.requireData)
                  else
                    const SizedBox(height: 48),
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
    _initializeAssetFields(feature);
    for (final field in feature.fieldOrder) {
      final schema =
          Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
      final revisionText =
          widget.request.isRevision && field == feature.revisionSourceField
              ? widget.request.baseArtifactText
              : null;
      final initial = revisionText?.trim().isNotEmpty == true
          ? revisionText
          : widget.request.initialParameters[field] ?? schema['default'];
      if (_isAssetField(schema, feature.widgetFor(field) ?? 'text')) {
        continue;
      }
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

  void _initializeAssetFields(FeatureDetail feature) {
    final consumedIds = <String>{};
    final assetFields = <String>[];
    for (final field in feature.fieldOrder) {
      final schema =
          Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
      final widgetType = feature.widgetFor(field) ?? 'text';
      if (!_isAssetField(schema, widgetType)) continue;
      assetFields.add(field);

      final initialIds =
          _assetIdsFromValue(widget.request.initialParameters[field]);
      consumedIds.addAll(initialIds);
      final resetForRevision = widget.request.isRevision &&
          feature.revisionResetFields.contains(field);
      final revisionIds = resetForRevision
          ? const <String>[]
          : widget.request.isRevision &&
                  field == feature.revisionSourceAssetField &&
                  widget.request.baseArtifactAssetIds.isNotEmpty
              ? widget.request.baseArtifactAssetIds
              : initialIds;
      consumedIds.addAll(revisionIds);
      _assetsByField[field] = _assetsForIds(revisionIds);
    }

    if (assetFields.isEmpty) return;
    final legacyIds = widget.request.initialAssetIds
        .where((id) => !consumedIds.contains(id))
        .toList();
    if (legacyIds.isNotEmpty) {
      _assetsByField[assetFields.first] = [
        ...?_assetsByField[assetFields.first],
        ..._assetsForIds(legacyIds),
      ];
    }
  }

  List<AssetView> _assetsForIds(List<String> ids) {
    return widget.data.assets.where((asset) => ids.contains(asset.id)).toList();
  }

  List<Widget> _buildModelSelectors(FeatureDetail feature) {
    final widgets = <Widget>[];
    for (final policy in feature.modelPolicies) {
      if (!policy.shouldShowSelector) continue;
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
      if (!feature.isFieldVisible(field, _values)) continue;
      final schema =
          Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
      final widgetType = feature.widgetFor(field) ?? 'text';
      fields
        ..add(const SizedBox(height: 15))
        ..add(_FieldLabel(schema['title']?.toString() ?? field,
            required: feature.requiredFields.contains(field)))
        ..add(_buildField(feature, field, schema, widgetType));
      final fieldHelp = feature.fieldHelp(field, _values);
      final helpText = fieldHelp['text']?.toString().trim();
      if (helpText?.isNotEmpty == true) {
        fields
          ..add(const SizedBox(height: 5))
          ..add(Text(
            helpText!,
            style: TextStyle(
              color: fieldHelp['tone'] == 'danger'
                  ? AppColors.danger
                  : AppColors.muted,
              fontSize: 11,
              height: 1.4,
            ),
          ));
      }
      final example = feature.exampleFor(field);
      if (example != null) {
        fields
          ..add(const SizedBox(height: 4))
          ..add(Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed:
                  _submitting ? null : () => _insertExample(field, example),
              icon: const Icon(Icons.lightbulb_outline_rounded, size: 17),
              label: const Text('插入示例'),
            ),
          ));
      }
    }
    return fields;
  }

  Widget _buildField(FeatureDetail feature, String field,
      Map<String, dynamic> schema, String widgetType) {
    if (_isAssetField(schema, widgetType)) {
      return _buildAssetField(feature, field, schema, widgetType);
    }
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
        final fieldOptions = feature.fieldOptions(field);
        final showSelectedIcon = fieldOptions['showSelectedIcon'] != false;
        final labelMaxLines =
            _integerOption(fieldOptions, 'labelMaxLines')?.clamp(1, 2) ?? 2;
        final compact = fieldOptions['compact'] == true;
        return SizedBox(
          width: double.infinity,
          child: SegmentedButton<String>(
            segments: values.map((value) {
              final label = Text(
                feature.optionLabel(field, value),
                maxLines: labelMaxLines,
                overflow: labelMaxLines > 1
                    ? TextOverflow.ellipsis
                    : TextOverflow.visible,
                softWrap: labelMaxLines > 1,
                textAlign: TextAlign.center,
              );
              return ButtonSegment<String>(
                value: value,
                label: labelMaxLines == 1
                    ? FittedBox(fit: BoxFit.scaleDown, child: label)
                    : label,
              );
            }).toList(),
            selected: {_values[field]?.toString() ?? values.first},
            showSelectedIcon: showSelectedIcon,
            onSelectionChanged: _submitting
                ? null
                : (selection) =>
                    setState(() => _values[field] = selection.first),
            style: ButtonStyle(
              textStyle: const WidgetStatePropertyAll(TextStyle(fontSize: 12)),
              minimumSize:
                  compact ? const WidgetStatePropertyAll(Size(0, 44)) : null,
              padding: compact
                  ? const WidgetStatePropertyAll(
                      EdgeInsets.symmetric(horizontal: 5, vertical: 10),
                    )
                  : null,
              visualDensity: compact ? VisualDensity.compact : null,
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
    final configuredMaxLength = schema['maxLength'];
    return TextField(
      controller: _controllers[field],
      enabled: !_submitting,
      minLines: multiline ? 3 : 1,
      maxLines: multiline ? 6 : 1,
      maxLength:
          configuredMaxLength is num ? configuredMaxLength.toInt() : null,
      keyboardType: numeric
          ? const TextInputType.numberWithOptions(decimal: true)
          : TextInputType.text,
      decoration: InputDecoration(hintText: schema['description']?.toString()),
    );
  }

  Widget _buildAssetField(FeatureDetail feature, String field,
      Map<String, dynamic> schema, String widgetType) {
    if (widgetType == 'image_mask') {
      return _buildImageMaskField(feature, field, schema);
    }
    final options = feature.fieldOptions(field);
    final assets = _assetsByField[field] ?? const <AssetView>[];
    final maxItems = _integerOption(options, 'maxItems') ?? 1;
    final acceptedMimeTypes = _stringListOption(options, 'acceptedMimeTypes',
        fallback: _mimeTypesForWidget(widgetType));
    final allowedExtensions = _stringListOption(options, 'allowedExtensions');
    final maxTotalSizeBytes = _integerOption(options, 'maxTotalSizeBytes');
    final currentBytes =
        assets.fold<int>(0, (sum, asset) => sum + asset.sizeBytes);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (schema['description']?.toString().trim().isNotEmpty == true)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Text(
            schema['description'].toString(),
            style: const TextStyle(color: AppColors.muted, fontSize: 12),
          ),
        ),
      if (maxItems > 1)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Text('已选择 ${assets.length}/$maxItems',
              style: const TextStyle(color: AppColors.muted, fontSize: 11)),
        ),
      if (assets.isNotEmpty)
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: assets
              .map((asset) => _AssetPreview(
                    asset: asset,
                    onRemove: _submitting
                        ? null
                        : () => _removeAsset(feature, field, asset),
                    contentUrl: widget.data.api.assetContentUrl(asset.id),
                  ))
              .toList(),
        ),
      if (maxTotalSizeBytes != null && assets.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 7),
          child: Text(
            '${_formatBytes(currentBytes)} / ${_formatBytes(maxTotalSizeBytes)}',
            style: const TextStyle(color: AppColors.muted, fontSize: 11),
          ),
        ),
      const SizedBox(height: 8),
      OutlinedButton.icon(
        onPressed: _submitting || assets.length >= maxItems
            ? null
            : () => _pickAsset(
                  feature,
                  field,
                  widgetType,
                  acceptedMimeTypes: acceptedMimeTypes,
                  allowedExtensions: allowedExtensions,
                  maxSizeBytes: _integerOption(options, 'maxFileSizeBytes'),
                  maxTotalSizeBytes: maxTotalSizeBytes,
                ),
        icon: const Icon(Icons.upload_file_outlined),
        label: Text(assets.length >= maxItems
            ? '已达到数量上限'
            : options['uploadLabel']?.toString() ?? '选择并上传图片'),
      ),
    ]);
  }

  Widget _buildImageMaskField(
    FeatureDetail feature,
    String field,
    Map<String, dynamic> schema,
  ) {
    final options = feature.fieldOptions(field);
    final sourceField = options['sourceField']?.toString() ?? 'sourceImage';
    final sourceAssets = _assetsByField[sourceField] ?? const <AssetView>[];
    final masks = _assetsByField[field] ?? const <AssetView>[];
    final source = sourceAssets.firstOrNull;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (schema['description']?.toString().trim().isNotEmpty == true)
        Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Text(
            schema['description'].toString(),
            style: const TextStyle(color: AppColors.muted, fontSize: 12),
          ),
        ),
      if (masks.isNotEmpty)
        Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: const Color(0xFFE9F5EF),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: const Color(0xFFB8DCC8)),
          ),
          child: const Row(children: [
            Icon(Icons.check_circle_outline_rounded,
                size: 19, color: Color(0xFF246B4A)),
            SizedBox(width: 8),
            Expanded(
              child: Text(
                '已完成编辑区域涂抹',
                style: TextStyle(
                  color: Color(0xFF246B4A),
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ]),
        )
      else if (source == null)
        const Text(
          '请先上传原始图片，再涂抹编辑区域。',
          style: TextStyle(color: AppColors.muted, fontSize: 12),
        ),
      const SizedBox(height: 8),
      SizedBox(
        width: double.infinity,
        child: OutlinedButton.icon(
          onPressed: _submitting || source == null
              ? null
              : () => _editMask(
                    field,
                    source,
                    maxSizeBytes: _integerOption(options, 'maxFileSizeBytes'),
                  ),
          icon:
              Icon(masks.isEmpty ? Icons.brush_outlined : Icons.edit_outlined),
          label: Text(masks.isEmpty
              ? options['editorLabel']?.toString() ?? '在原图上涂抹编辑区域'
              : '重新涂抹编辑区域'),
        ),
      ),
    ]);
  }

  Future<void> _editMask(
    String field,
    AssetView source, {
    required int? maxSizeBytes,
  }) async {
    try {
      final bytes = await showImageMaskEditor(
        context,
        sourceAsset: source,
        api: widget.data.api,
      );
      if (bytes == null || !mounted) return;
      if (maxSizeBytes != null && bytes.length > maxSizeBytes) {
        throw ApiException(
          '编辑区域蒙版不能超过 ${_formatBytes(maxSizeBytes)}',
        );
      }
      setState(() {
        _error = null;
        _status = '正在上传编辑区域';
      });
      final previous =
          List<AssetView>.from(_assetsByField[field] ?? const <AssetView>[]);
      final mask = await widget.data.api.uploadAsset(PickedLocalFile(
        name: 'local-edit-mask-${DateTime.now().millisecondsSinceEpoch}.png',
        mediaType: 'image/png',
        bytes: bytes,
      ));
      for (final asset in previous) {
        try {
          await widget.data.api.deleteAsset(asset.id);
        } catch (_) {
          // Referenced historical masks remain in the asset library.
        }
      }
      await widget.data.refresh();
      if (!mounted) return;
      setState(() {
        _assetsByField[field] = [mask];
        _status = null;
      });
    } catch (exception) {
      if (mounted) {
        setState(() {
          _status = null;
          _error = '$exception';
        });
      }
    }
  }

  Future<void> _pickAsset(
    FeatureDetail feature,
    String field,
    String widgetType, {
    required List<String> acceptedMimeTypes,
    required List<String> allowedExtensions,
    required int? maxSizeBytes,
    required int? maxTotalSizeBytes,
  }) async {
    try {
      final asset = await widget.data.pickAndUpload(
        mimeTypes: acceptedMimeTypes,
        allowedExtensions: allowedExtensions,
        maxSizeBytes: maxSizeBytes,
      );
      if (asset != null &&
          maxTotalSizeBytes != null &&
          (_assetsByField[field] ?? const <AssetView>[])
                      .fold<int>(0, (sum, item) => sum + item.sizeBytes) +
                  asset.sizeBytes >
              maxTotalSizeBytes) {
        await widget.data.deleteAsset(asset.id);
        throw ApiException('图片总大小不能超过 ${_formatBytes(maxTotalSizeBytes)}');
      }
      if (asset != null && mounted) {
        final staleMasks = <AssetView>[];
        setState(() {
          (_assetsByField[field] ??= []).add(asset);
          staleMasks.addAll(_clearDependentMaskFields(feature, field));
        });
        for (final stale in staleMasks) {
          try {
            await widget.data.api.deleteAsset(stale.id);
          } catch (_) {
            // A mask already referenced by history cannot be removed.
          }
        }
      }
    } catch (exception) {
      if (mounted) setState(() => _error = '$exception');
    }
  }

  void _removeAsset(
    FeatureDetail feature,
    String field,
    AssetView asset,
  ) {
    setState(() {
      _assetsByField[field]?.remove(asset);
      _clearDependentMaskFields(feature, field);
    });
  }

  List<AssetView> _clearDependentMaskFields(
    FeatureDetail feature,
    String sourceField,
  ) {
    final removed = <AssetView>[];
    for (final field in feature.fieldOrder) {
      if (feature.widgetFor(field) != 'image_mask') continue;
      final configuredSource =
          feature.fieldOptions(field)['sourceField']?.toString();
      if (configuredSource != sourceField) continue;
      removed.addAll(_assetsByField[field] ?? const <AssetView>[]);
      _assetsByField[field] = [];
    }
    return removed;
  }

  Future<void> _execute(FeatureDetail feature) async {
    final taskName = _nameController.text.trim();
    if (taskName.isEmpty) {
      setState(() => _error = '请填写任务名称');
      return;
    }
    final parameters = <String, Object?>{};
    for (final field in feature.fieldOrder) {
      if (!feature.isFieldVisible(field, _values)) continue;
      final schema =
          Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
      final widgetType = feature.widgetFor(field) ?? 'text';
      if (_isAssetField(schema, widgetType)) {
        final assets = _assetsByField[field] ?? const <AssetView>[];
        if (feature.requiredFields.contains(field) && assets.isEmpty) {
          setState(() => _error = '请上传${schema['title'] ?? field}');
          return;
        }
        if (assets.isNotEmpty) {
          final assetIds = assets.map((asset) => asset.id).toList();
          parameters[field] =
              schema['type'] == 'array' ? assetIds : assetIds.first;
        }
        continue;
      }
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
      _status = null;
    });
    final inputAssetIds = <String>[];
    for (final field in feature.fieldOrder) {
      if (!feature.isFieldVisible(field, _values)) continue;
      for (final asset in _assetsByField[field] ?? const <AssetView>[]) {
        if (!inputAssetIds.contains(asset.id)) inputAssetIds.add(asset.id);
      }
    }

    final navigator = Navigator.of(context);
    final sheetRoute = ModalRoute.of(context);
    final executionController = TaskExecutionController(
      initialStatus: '正在创建任务',
      onCancelRun: widget.data.api.cancelRun,
      loadRunOutput: widget.data.api.getRunOutput,
    );
    unawaited(navigator.push<void>(MaterialPageRoute<void>(
      builder: (context) => TaskExecutionPage(
        title: feature.title,
        controller: executionController,
        openResult: widget.openResult,
        resultRouteBuilder: (result) => _artifactResultRoute(
          data: widget.data,
          artifact: result.artifact,
          rendererKey: result.feature.rendererKey,
        ),
      ),
    )));
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
        inputAssetIds: inputAssetIds,
        onStatus: executionController.updateStatus,
        onRunCreated: executionController.attachRun,
        onOutput: executionController.updateOutput,
      );
      executionController.complete(result);
      widget.onCompleted(_TaskSheetOutcome(
        result: result,
        resultPageOpened: widget.openResult,
      ));
      if (sheetRoute != null && sheetRoute.isActive) {
        navigator.removeRoute(sheetRoute);
      }
    } on ApiException catch (exception) {
      if (mounted) {
        if (exception.code == 'RUN_CANCELLED') {
          executionController.markCancelled();
          setState(() {
            _submitting = false;
            _status = null;
            _error = null;
          });
          return;
        }
        executionController.fail(exception.message);
        setState(() {
          _submitting = false;
          _status = null;
          _error = exception.message;
        });
      }
    } catch (exception) {
      if (mounted) {
        executionController.fail('$exception');
        setState(() {
          _submitting = false;
          _status = null;
          _error = '$exception';
        });
      }
    }
  }

  Widget _buildActions(FeatureDetail feature) {
    final executeButton = SizedBox(
      height: 48,
      child: FilledButton.icon(
        onPressed: _submitting ? null : () => _execute(feature),
        style: FilledButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
        icon: const Icon(Icons.auto_awesome_rounded, size: 19),
        label: Text(widget.request.isRevision
            ? feature.revisionSubmitLabel
            : feature.submitLabel),
      ),
    );
    if (!feature.showResetAction) {
      return SizedBox(width: double.infinity, child: executeButton);
    }
    return Row(children: [
      Expanded(
        child: SizedBox(
          height: 48,
          child: OutlinedButton.icon(
            onPressed: _submitting ? null : () => _resetParameters(feature),
            icon: const Icon(Icons.delete_outline_rounded, size: 19),
            label: const Text('重置内容'),
          ),
        ),
      ),
      const SizedBox(width: 10),
      Expanded(child: executeButton),
    ]);
  }

  void _insertExample(String field, String example) {
    final controller = _controllers[field];
    if (controller == null) return;
    setState(() {
      controller
        ..text = example
        ..selection = TextSelection.collapsed(offset: example.length);
      _error = null;
    });
  }

  void _resetParameters(FeatureDetail feature) {
    setState(() {
      _assetsByField.clear();
      for (final field in feature.fieldOrder) {
        final schema =
            Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
        final defaultValue = schema['default'];
        if (schema['type'] == 'boolean') {
          _values[field] = defaultValue == true;
        } else if (schema['enum'] is List) {
          final options = (schema['enum'] as List)
              .map((value) => value.toString())
              .toList();
          _values[field] = defaultValue?.toString() ??
              (options.isEmpty ? null : options.first);
        } else {
          _controllers[field]?.text = defaultValue?.toString() ?? '';
        }
      }
      _status = null;
      _error = null;
    });
  }

  static bool _isAssetField(Map<String, dynamic> schema, String widgetType) {
    return schema['format'] == 'binary' ||
        schema['type'] == 'asset' ||
        const {'file', 'image', 'image_mask', 'audio', 'video'}
            .contains(widgetType);
  }

  static List<String> _assetIdsFromValue(Object? value) {
    if (value is List) {
      return value
          .map((item) => item.toString())
          .where((item) => item.isNotEmpty)
          .toList();
    }
    final single = value?.toString();
    return single == null || single.isEmpty ? const [] : [single];
  }
}

class _FeeNotice extends StatelessWidget {
  const _FeeNotice({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: const Color(0xFFFFF8E8),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: const Color(0xFFF1D79B)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.info_outline_rounded,
                size: 18, color: Color(0xFF8A5A00)),
            const SizedBox(width: 8),
            Expanded(
              child: Text(text,
                  style: const TextStyle(
                      color: Color(0xFF6E4A00), fontSize: 12, height: 1.4)),
            ),
          ],
        ),
      );
}

class _AssetPreview extends StatelessWidget {
  const _AssetPreview({
    required this.asset,
    required this.contentUrl,
    required this.onRemove,
  });
  final AssetView asset;
  final String contentUrl;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context) => SizedBox(
        width: 92,
        child: Stack(
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: Image.network(
                contentUrl,
                width: 92,
                height: 92,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => Container(
                  width: 92,
                  height: 92,
                  color: AppColors.wash,
                  alignment: Alignment.center,
                  child: const Icon(Icons.broken_image_outlined),
                ),
              ),
            ),
            if (onRemove != null)
              Positioned(
                top: 3,
                right: 3,
                child: InkWell(
                  onTap: onRemove,
                  child: Container(
                    decoration: const BoxDecoration(
                        color: Colors.black54, shape: BoxShape.circle),
                    padding: const EdgeInsets.all(2),
                    child: const Icon(Icons.close_rounded,
                        color: Colors.white, size: 16),
                  ),
                ),
              ),
          ],
        ),
      );
}

List<String> _mimeTypesForWidget(String widgetType) => switch (widgetType) {
      'image' => ['image/png', 'image/jpeg', 'image/webp'],
      'audio' => ['audio/*'],
      'video' => ['video/*'],
      _ => ['*/*'],
    };

List<String> _stringListOption(Map<String, dynamic> options, String key,
    {List<String> fallback = const []}) {
  final value = options[key];
  return value is List
      ? value.map((item) => item.toString()).toList()
      : fallback;
}

int? _integerOption(Map<String, dynamic> options, String key) {
  final value = options[key];
  return value is num ? value.toInt() : int.tryParse(value?.toString() ?? '');
}

String _formatBytes(int bytes) {
  final megabytes = bytes / (1024 * 1024);
  return megabytes >= 1
      ? '${megabytes.toStringAsFixed(megabytes == megabytes.roundToDouble() ? 0 : 1)} MB'
      : '$bytes B';
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
