import 'dart:async';

import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../models/prompt_optimization_undo_store.dart';
import '../network/api_exception.dart';
import '../network/native_file_picker.dart';
import '../network/task_execution_result.dart';
import '../pages/data_analysis_result_page.dart';
import '../pages/document_compare_result_page.dart';
import '../pages/outline_result_page.dart';
import '../pages/task_execution_page.dart';
import '../pages/writing_result_page.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import 'image_asset_picker_view.dart';
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
  if (rendererKey == 'data_analysis') {
    return MaterialPageRoute<void>(
      builder: (context) => DataAnalysisResultPage(
        artifact: artifact,
        onContinue: onContinue,
      ),
    );
  }
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
  if (rendererKey == 'document_compare') {
    return MaterialPageRoute<void>(
      builder: (context) => DocumentCompareResultPage(
        data: data,
        artifact: artifact,
        onContinue: onContinue,
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
  final Set<String> _uploadingAssetFields = {};
  final Set<String> _temporaryDerivedAssetIds = {};
  final Map<String, String> _selectedModelGroups = {};
  final PromptOptimizationUndoStore _promptUndoStore =
      PromptOptimizationUndoStore();
  final Map<String, String> _promptAssistErrors = {};
  String? _projectId;
  final Map<String, String> _selectedModels = {};
  String? _status;
  String? _error;
  String? _optimizingPromptField;
  bool _submitting = false;
  bool _initialized = false;
  bool _derivedAssetsHandedOff = false;
  bool _taskTitleEdited = false;
  int _modelSelectorEpoch = 0;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(
        text: widget.request.taskTitle ?? widget.request.entry.title);
    _taskTitleEdited =
        widget.request.taskTitle != null || widget.request.isRevision;
    _projectId = widget.request.projectId;
    _featureFuture = widget.data.api.getFeature(widget.request.entry.id);
  }

  @override
  void dispose() {
    if (!_derivedAssetsHandedOff) {
      for (final assetId in _temporaryDerivedAssetIds) {
        unawaited(widget.data.api.deleteAsset(assetId).catchError((_) {}));
      }
    }
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
      canPop: !_submitting && _uploadingAssetFields.isEmpty,
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
                      onPressed: _submitting || _uploadingAssetFields.isNotEmpty
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
                      onChanged: (_) => _taskTitleEdited = true,
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
    for (final group in feature.modelSelectionGroups) {
      final options = _availableGroupOptions(feature, group);
      final selected = options.where((option) {
        if (!group.capabilities.every(option.deployments.containsKey)) {
          return false;
        }
        return group.capabilities.every((capability) =>
            _selectedModels[capability] == option.deployments[capability]);
      }).firstOrNull;
      final fallback = selected ?? options.firstOrNull;
      if (fallback == null) continue;
      _selectedModelGroups[group.key] = fallback.value;
      for (final capability in group.capabilities) {
        final deployment = fallback.deployments[capability];
        if (deployment != null) _selectedModels[capability] = deployment;
      }
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
      final widgetType = feature.widgetFor(field) ?? 'text';
      if (_isAssetField(schema, widgetType)) {
        continue;
      }
      if (widgetType == 'slider') {
        _values[field] = _sliderConfig(
          schema,
          feature.fieldOptions(field),
          initial,
        ).value;
      } else if (schema['type'] == 'boolean') {
        _values[field] = initial == true;
      } else if (schema['enum'] is List) {
        _values[field] = feature.normalizedEnumValue(
          field,
          _selectedModels,
          initial,
        );
      } else {
        _controllers[field] =
            TextEditingController(text: initial?.toString() ?? '');
      }
    }
    _initializeRevisionArtifactReference(feature);
    _refreshTaskTitleFromAssets(feature);
  }

  void _initializeRevisionArtifactReference(FeatureDetail feature) {
    if (!widget.request.isRevision) return;
    final config = feature.revisionArtifactReference;
    if (config.isEmpty) return;
    final field = config['modeField']?.toString();
    if (field == null || field.isEmpty) return;
    final enabledValue = config['enabledValue']?.toString() ?? 'USE_BASE';
    final disabledValue = config['disabledValue']?.toString() ?? 'NONE';
    final enabledByDefault = config['defaultEnabled'] != false;
    _values[field] = enabledByDefault && _baseArtifactAsset != null
        ? enabledValue
        : disabledValue;
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

  AssetView? get _baseArtifactAsset {
    final supplied = widget.request.baseArtifactAssets
        .where((asset) => asset.available)
        .firstOrNull;
    if (supplied != null) return supplied;
    return _assetsForIds(widget.request.baseArtifactAssetIds)
        .where((asset) => asset.available)
        .firstOrNull;
  }

  ModelOption? _selectedReferenceModel(FeatureDetail feature) {
    final policy = feature.modelPolicies
        .where((item) => item.capability == 'IMAGE_GENERATION')
        .firstOrNull;
    if (policy == null) return null;
    final selectedCode = _selectedModels[policy.capability];
    return policy.options
        .where((option) => option.code == selectedCode)
        .firstOrNull;
  }

  bool _referenceInputsDisabledByModel(FeatureDetail feature) =>
      _selectedReferenceModel(feature)?.maxReferenceImages == 0;

  bool _assetFieldRequiresReferenceSupport(
    FeatureDetail feature,
    String field,
  ) =>
      feature.fieldOptions(field)['requiresReferenceImageSupport'] == true;

  bool _hasReferenceInputs(FeatureDetail feature) {
    final hasUserReferences = feature.fieldOrder.any((field) =>
        _assetFieldRequiresReferenceSupport(feature, field) &&
        (_assetsByField[field]?.isNotEmpty ?? false));
    if (hasUserReferences) return true;
    final config = feature.revisionArtifactReference;
    if (!widget.request.isRevision || config.isEmpty) return false;
    final field = config['modeField']?.toString();
    final enabledValue = config['enabledValue']?.toString() ?? 'USE_BASE';
    return field != null &&
        _values[field]?.toString() == enabledValue &&
        _baseArtifactAsset != null;
  }

  int _activeBaseReferenceCount(FeatureDetail feature) {
    if (_referenceInputsDisabledByModel(feature)) return 0;
    final config = feature.revisionArtifactReference;
    if (!widget.request.isRevision || config.isEmpty) return 0;
    final field = config['modeField']?.toString();
    final enabledValue = config['enabledValue']?.toString() ?? 'USE_BASE';
    return field != null &&
            _values[field]?.toString() == enabledValue &&
            _baseArtifactAsset != null
        ? 1
        : 0;
  }

  List<Widget> _buildModelSelectors(FeatureDetail feature) {
    final widgets = <Widget>[];
    final groupedCapabilities = <String>{};
    for (final group in feature.modelSelectionGroups) {
      final options = _availableGroupOptions(feature, group);
      if (options.isEmpty) continue;
      groupedCapabilities.addAll(group.capabilities);
      widgets.addAll(_buildModelSelectionGroup(feature, group, options));
    }
    for (final policy in feature.modelPolicies) {
      if (groupedCapabilities.contains(policy.capability)) continue;
      if (!policy.shouldShowSelector) continue;
      widgets.addAll(_buildModelSelector(feature, policy));
    }
    return widgets;
  }

  List<Widget> _buildModelSelectionGroup(
    FeatureDetail feature,
    ModelSelectionGroup group,
    List<ModelSelectionGroupOption> options,
  ) {
    final selectedValue = _selectedModelGroups[group.key];
    final selected =
        options.where((option) => option.value == selectedValue).firstOrNull;
    final sourceOption = selected?.deployments.entries
        .map((entry) => feature.modelOption(entry.key, entry.value))
        .whereType<ModelOption>()
        .firstOrNull;
    return [
      const SizedBox(height: 15),
      _FieldLabel(group.label),
      DropdownButtonFormField<String>(
        value: selectedValue,
        isExpanded: true,
        items: options.map((option) {
          final optionSource = option.deployments.entries
              .map((entry) => feature.modelOption(entry.key, entry.value))
              .whereType<ModelOption>()
              .firstOrNull;
          return DropdownMenuItem<String>(
            value: option.value,
            child: Row(children: [
              Expanded(
                child: Text(
                  option.displayName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              if (optionSource != null) ...[
                const SizedBox(width: 8),
                _ModelSourceBadge(optionSource.sourceLabel),
              ],
            ]),
          );
        }).toList(),
        onChanged: _submitting
            ? null
            : (value) {
                if (value == null) return;
                final option =
                    options.where((item) => item.value == value).firstOrNull;
                if (option == null) return;
                setState(() {
                  _selectedModelGroups[group.key] = value;
                  for (final capability in group.capabilities) {
                    final deployment = option.deployments[capability];
                    if (deployment != null) {
                      _selectedModels[capability] = deployment;
                    }
                  }
                });
              },
      ),
      if (selected != null &&
          (group.description.isNotEmpty ||
              selected.description.isNotEmpty ||
              sourceOption != null)) ...[
        const SizedBox(height: 6),
        Text(
          [
            sourceOption?.sourceName ?? '',
            selected.description.isNotEmpty
                ? selected.description
                : group.description,
          ].where((value) => value.isNotEmpty).join(' · '),
          style: const TextStyle(color: AppColors.muted, fontSize: 11),
        ),
      ],
    ];
  }

  List<ModelSelectionGroupOption> _availableGroupOptions(
    FeatureDetail feature,
    ModelSelectionGroup group,
  ) =>
      group.options.where((option) {
        return group.capabilities.every((capability) {
          final deployment = option.deployments[capability];
          return deployment != null &&
              feature.modelOption(capability, deployment) != null;
        });
      }).toList();

  List<Widget> _buildModelSelector(
    FeatureDetail feature,
    ModelPolicy policy,
  ) {
    final selectedCode =
        _selectedModels[policy.capability] ?? policy.defaultModelCode;
    final selected =
        policy.options.where((item) => item.code == selectedCode).firstOrNull;
    final selectorOptions = feature.modelSelectorOptions(policy.capability);
    final configuredLabel = selectorOptions['label']?.toString().trim();
    return [
      const SizedBox(height: 15),
      _FieldLabel(configuredLabel?.isNotEmpty == true
          ? configuredLabel!
          : _modelCapabilityLabel(policy.capability)),
      TaskModelSelector(
        key: ValueKey(
          '${policy.capability}:$selectedCode:$_modelSelectorEpoch',
        ),
        policy: policy,
        selectedCode: selectedCode,
        options: selectorOptions,
        enabled: !_submitting,
        onSelected: (value) => unawaited(_selectModel(feature, policy, value)),
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

  Future<void> _selectModel(
    FeatureDetail feature,
    ModelPolicy policy,
    String code,
  ) async {
    if (_selectedModels[policy.capability] == code) return;
    final option =
        policy.options.where((item) => item.code == code).firstOrNull;
    if (option?.maxReferenceImages == 0 && _hasReferenceInputs(feature)) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('当前模型不支持参考图'),
          content: const Text(
            '切换后，本次将暂时不使用上一版成果及已选择的参考图。切换回支持参考图的模型后会恢复当前选择。',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('继续切换'),
            ),
          ],
        ),
      );
      if (confirmed != true) {
        if (mounted) setState(() => _modelSelectorEpoch++);
        return;
      }
      if (!mounted) return;
    }
    setState(() {
      _selectedModels[policy.capability] = code;
      _normalizeModelConstrainedValues(feature);
    });
  }

  void _normalizeModelConstrainedValues(FeatureDetail feature) {
    final normalized = feature.normalizedEnumValues(_selectedModels, _values);
    _values
      ..clear()
      ..addAll(normalized);
  }

  List<Widget> _buildFields(FeatureDetail feature) {
    final fields = <Widget>[];
    var revisionReferenceAdded = false;
    for (final field in feature.fieldOrder) {
      if (!feature.isFieldVisible(field, _values)) continue;
      final schema =
          Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
      final widgetType = feature.widgetFor(field) ?? 'text';
      if (!revisionReferenceAdded &&
          _assetFieldRequiresReferenceSupport(feature, field) &&
          feature.revisionArtifactReference.isNotEmpty &&
          widget.request.isRevision) {
        fields
          ..add(const SizedBox(height: 15))
          ..add(_buildRevisionArtifactReference(feature));
        revisionReferenceAdded = true;
      }
      if (widgetType == 'hidden') continue;
      fields.add(const SizedBox(height: 15));
      if (widgetType == 'slider') {
        fields.add(_buildField(feature, field, schema, widgetType));
      } else {
        fields
          ..add(_FieldLabel(
            schema['title']?.toString() ?? field,
            required: feature.requiredFields.contains(field),
          ))
          ..add(_buildField(feature, field, schema, widgetType));
      }
      final promptError = _promptAssistErrors[field];
      if (promptError != null) {
        fields
          ..add(const SizedBox(height: 5))
          ..add(Text(
            promptError,
            style: const TextStyle(
              color: AppColors.danger,
              fontSize: 11,
              height: 1.4,
            ),
          ));
      }
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

  Widget _buildRevisionArtifactReference(FeatureDetail feature) {
    final config = feature.revisionArtifactReference;
    final modeField =
        config['modeField']?.toString() ?? 'generatedReferenceMode';
    final enabledValue = config['enabledValue']?.toString() ?? 'USE_BASE';
    final disabledValue = config['disabledValue']?.toString() ?? 'NONE';
    final enabled = _values[modeField]?.toString() == enabledValue;
    final disabledByModel = _referenceInputsDisabledByModel(feature);
    final asset = _baseArtifactAsset;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      _FieldLabel(config['title']?.toString() ?? '上一版成果'),
      const SizedBox(height: 6),
      Text(
        config['description']?.toString() ?? '默认作为额外参考图，可点击叉号仅在本次生成中移除。',
        style: const TextStyle(color: AppColors.muted, fontSize: 12),
      ),
      const SizedBox(height: 8),
      if (asset == null)
        const Text(
          '上一版成果已删除，本次无法作为参考图使用。',
          style: TextStyle(color: AppColors.danger, fontSize: 12),
        )
      else if (enabled)
        Opacity(
          opacity: disabledByModel ? 0.45 : 1,
          child: _AssetPreview(
            asset: asset,
            contentUrl: widget.data.api.assetContentUrl(asset.id),
            onRemove: _submitting || disabledByModel
                ? null
                : () => setState(() => _values[modeField] = disabledValue),
          ),
        )
      else
        OutlinedButton.icon(
          onPressed: _submitting || disabledByModel
              ? null
              : () => setState(() => _values[modeField] = enabledValue),
          icon: const Icon(Icons.restore_rounded),
          label: const Text('重新使用上一版成果'),
        ),
      if (disabledByModel) ...[
        const SizedBox(height: 7),
        const Text(
          '当前模型不支持参考图，以上选择已暂时禁用。',
          style: TextStyle(color: AppColors.muted, fontSize: 11),
        ),
      ],
    ]);
  }

  Widget _buildField(FeatureDetail feature, String field,
      Map<String, dynamic> schema, String widgetType) {
    if (_isAssetField(schema, widgetType)) {
      return _buildAssetField(feature, field, schema, widgetType);
    }
    if (widgetType == 'slider') {
      return _buildSliderField(feature, field, schema);
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
      final values = feature.enumValuesFor(field, _selectedModels);
      if (values.isEmpty) {
        return InputDecorator(
          decoration: const InputDecoration(enabled: false),
          child: const Text(
            '当前模型没有可用选项',
            style: TextStyle(color: AppColors.muted),
          ),
        );
      }
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
    final supportsPromptAssist =
        multiline && feature.supportsPromptAssist(field);
    final textField = TextField(
      controller: _controllers[field],
      enabled: !_submitting && _optimizingPromptField != field,
      minLines: multiline ? 3 : 1,
      maxLines: multiline ? 6 : 1,
      maxLength:
          configuredMaxLength is num ? configuredMaxLength.toInt() : null,
      keyboardType: numeric
          ? const TextInputType.numberWithOptions(decimal: true)
          : TextInputType.text,
      decoration: InputDecoration(hintText: schema['description']?.toString()),
      onChanged: supportsPromptAssist
          ? (_) => setState(() => _promptAssistErrors.remove(field))
          : null,
    );
    if (!supportsPromptAssist) return textField;

    final controller = _controllers[field]!;
    final optimizing = _optimizingPromptField == field;
    final optimizationBlocked = _submitting ||
        _optimizingPromptField != null ||
        controller.text.trim().isEmpty;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        textField,
        const SizedBox(height: 4),
        Wrap(
          alignment: WrapAlignment.end,
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: 4,
          children: [
            if (_promptUndoStore.contains(field))
              IconButton(
                onPressed: _submitting || optimizing
                    ? null
                    : () => _undoPromptOptimization(field),
                tooltip: '撤销提示词优化',
                icon: const Icon(Icons.undo_rounded, size: 19),
              ),
            TextButton.icon(
              onPressed: optimizationBlocked
                  ? null
                  : () => _optimizePrompt(feature, field),
              icon: optimizing
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.auto_fix_high_rounded, size: 18),
              label: Text(optimizing ? '优化中' : '优化提示词'),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildSliderField(
    FeatureDetail feature,
    String field,
    Map<String, dynamic> schema,
  ) {
    final options = feature.fieldOptions(field);
    final config = _sliderConfig(schema, options, _values[field]);
    final required = feature.requiredFields.contains(field);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: _FieldLabel(
                schema['title']?.toString() ?? field,
                required: required,
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(bottom: 7),
              child: Text(
                config.displayValue,
                key: ValueKey('task-slider-value-$field'),
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ],
        ),
        SliderTheme(
          data: SliderTheme.of(context).copyWith(
            trackHeight: 5,
            activeTrackColor: AppColors.accent,
            inactiveTrackColor: AppColors.line,
            thumbColor: AppColors.accent,
            overlayColor: AppColors.accent.withOpacity(0.12),
            thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 10),
          ),
          child: Slider(
            key: ValueKey('task-slider-$field'),
            value: config.value,
            min: config.minimum,
            max: config.maximum,
            divisions: config.divisions,
            semanticFormatterCallback: (_) => config.displayValue,
            onChanged: _submitting
                ? null
                : (value) => setState(() {
                      _values[field] =
                          _sliderConfig(schema, options, value).value;
                    }),
          ),
        ),
      ],
    );
  }

  Widget _buildAssetField(FeatureDetail feature, String field,
      Map<String, dynamic> schema, String widgetType) {
    if (widgetType == 'image_mask') {
      return _buildImageMaskField(feature, field, schema);
    }
    final options = feature.fieldOptions(field);
    final assets = _assetsByField[field] ?? const <AssetView>[];
    final maxItems = _assetItemBounds(feature, field, options).maxItems;
    final requiresReferenceSupport =
        _assetFieldRequiresReferenceSupport(feature, field);
    final disabledByModel =
        requiresReferenceSupport && _referenceInputsDisabledByModel(feature);
    final selectedReferenceModel = _selectedReferenceModel(feature);
    final totalReferenceLimit = selectedReferenceModel?.maxReferenceImages ??
        _integerOption(feature.config, 'maxTotalReferenceImages');
    final activeReferenceCount =
        assets.length + _activeBaseReferenceCount(feature);
    final acceptedMimeTypes = _stringListOption(options, 'acceptedMimeTypes',
        fallback: _mimeTypesForWidget(widgetType));
    final allowedExtensions = _stringListOption(options, 'allowedExtensions');
    final maxTotalSizeBytes = _integerOption(options, 'maxTotalSizeBytes');
    final allowAssetLibrarySelection =
        options['allowAssetLibrarySelection'] == true;
    final currentBytes =
        assets.fold<int>(0, (sum, asset) => sum + asset.sizeBytes);
    if (widgetType == 'image') {
      return _buildImageAssetField(
        feature,
        field,
        schema,
        assets: assets,
        maxItems: maxItems,
        disabledByModel: disabledByModel,
        acceptedMimeTypes: acceptedMimeTypes,
        allowedExtensions: allowedExtensions,
        maxSizeBytes: _integerOption(options, 'maxFileSizeBytes'),
        maxTotalSizeBytes: maxTotalSizeBytes,
        currentBytes: currentBytes,
        allowAssetLibrarySelection: allowAssetLibrarySelection,
      );
    }
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
          child: Text(
              disabledByModel
                  ? '已暂存 ${assets.length}/$maxItems · 当前模型不使用参考图'
                  : requiresReferenceSupport && totalReferenceLimit != null
                      ? '自行上传 ${assets.length}/$maxItems · 模型参考输入 $activeReferenceCount/$totalReferenceLimit'
                      : '已选择 ${assets.length}/$maxItems',
              style: const TextStyle(color: AppColors.muted, fontSize: 11)),
        ),
      if (assets.isNotEmpty)
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: assets
              .map((asset) => _AssetPreview(
                    asset: asset,
                    onRemove: _submitting || disabledByModel
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
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          OutlinedButton.icon(
            onPressed: _submitting ||
                    disabledByModel ||
                    assets.length >= maxItems
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
                : disabledByModel
                    ? '当前模型不支持参考图'
                    : options['uploadLabel']?.toString() ??
                        _defaultUploadLabel(widgetType)),
          ),
          if (allowAssetLibrarySelection)
            TextButton.icon(
              onPressed:
                  _submitting || disabledByModel || assets.length >= maxItems
                      ? null
                      : () => _chooseLibraryAssets(
                            feature,
                            field,
                            acceptedMimeTypes: acceptedMimeTypes,
                            allowedExtensions: allowedExtensions,
                            maximum: maxItems - assets.length,
                            maxSizeBytes:
                                _integerOption(options, 'maxFileSizeBytes'),
                            maxTotalSizeBytes: maxTotalSizeBytes,
                          ),
              icon: const Icon(Icons.folder_outlined),
              label: const Text('我的文件'),
            ),
        ],
      ),
    ]);
  }

  Widget _buildImageAssetField(
    FeatureDetail feature,
    String field,
    Map<String, dynamic> schema, {
    required List<AssetView> assets,
    required int maxItems,
    required bool disabledByModel,
    required List<String> acceptedMimeTypes,
    required List<String> allowedExtensions,
    required int? maxSizeBytes,
    required int? maxTotalSizeBytes,
    required int currentBytes,
    required bool allowAssetLibrarySelection,
  }) {
    final uploading = _uploadingAssetFields.contains(field);
    final replaceExisting = maxItems == 1 && assets.isNotEmpty;
    final remaining = replaceExisting ? 1 : maxItems - assets.length;
    final enabled = !_submitting && !disabledByModel;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (schema['description']?.toString().trim().isNotEmpty == true)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              schema['description'].toString(),
              style: const TextStyle(color: AppColors.muted, fontSize: 12),
            ),
          ),
        ImageAssetPickerView(
          assets: assets,
          maxItems: maxItems,
          uploading: uploading,
          enabled: enabled,
          disabledReason: disabledByModel ? '当前模型不使用参考图，已选择内容会暂时保留。' : null,
          contentUrlFor: (asset) => widget.data.api.assetContentUrl(asset.id),
          onPickImages: remaining <= 0
              ? null
              : () => _pickImages(
                    feature,
                    field,
                    maxItems: maxItems,
                    acceptedMimeTypes: acceptedMimeTypes,
                    allowedExtensions: allowedExtensions,
                    maxSizeBytes: maxSizeBytes,
                    maxTotalSizeBytes: maxTotalSizeBytes,
                    currentBytes: currentBytes,
                  ),
          onChooseLibrary: !allowAssetLibrarySelection || remaining <= 0
              ? null
              : () => _chooseLibraryAssets(
                    feature,
                    field,
                    acceptedMimeTypes: acceptedMimeTypes,
                    allowedExtensions: allowedExtensions,
                    maximum: remaining,
                    maxSizeBytes: maxSizeBytes,
                    maxTotalSizeBytes: maxTotalSizeBytes,
                    replaceExisting: replaceExisting,
                  ),
          onRemove: (asset) => _removeAsset(feature, field, asset),
        ),
        if (maxTotalSizeBytes != null && assets.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 7),
            child: Text(
              '${_formatBytes(currentBytes)} / ${_formatBytes(maxTotalSizeBytes)}',
              style: const TextStyle(color: AppColors.muted, fontSize: 11),
            ),
          ),
      ],
    );
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
      final mask = await widget.data.api.uploadAsset(
          PickedLocalFile(
            name:
                'local-edit-mask-${DateTime.now().millisecondsSinceEpoch}.png',
            mediaType: 'image/png',
            bytes: bytes,
            sizeBytes: bytes.length,
          ),
          origin: 'APP_DERIVED');
      _temporaryDerivedAssetIds.add(mask.id);
      for (final asset in previous) {
        try {
          await widget.data.api.deleteAsset(asset.id);
          _temporaryDerivedAssetIds.remove(asset.id);
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
        final title = feature.properties[field] is Map
            ? Map<String, dynamic>.from(
                    feature.properties[field] as Map)['title']
                ?.toString()
            : null;
        throw ApiException(
          '${title ?? '文件'}总大小不能超过 ${_formatBytes(maxTotalSizeBytes)}',
        );
      }
      if (asset != null && mounted) {
        final staleMasks = <AssetView>[];
        setState(() {
          (_assetsByField[field] ??= []).add(asset);
          staleMasks.addAll(_clearDependentMaskFields(feature, field));
        });
        _refreshTaskTitleFromAssets(feature);
        for (final stale in staleMasks) {
          await _deleteTemporaryDerivedAsset(stale);
        }
      }
    } catch (exception) {
      if (mounted) setState(() => _error = '$exception');
    }
  }

  Future<void> _pickImages(
    FeatureDetail feature,
    String field, {
    required int maxItems,
    required List<String> acceptedMimeTypes,
    required List<String> allowedExtensions,
    required int? maxSizeBytes,
    required int? maxTotalSizeBytes,
    required int currentBytes,
  }) async {
    final existing = _assetsByField[field] ?? const <AssetView>[];
    final replaceExisting = maxItems == 1 && existing.isNotEmpty;
    final remaining = replaceExisting ? 1 : maxItems - existing.length;
    if (remaining <= 0 || _uploadingAssetFields.contains(field)) return;
    setState(() {
      _error = null;
      _uploadingAssetFields.add(field);
    });
    try {
      final remainingBytes = maxTotalSizeBytes == null
          ? null
          : (maxTotalSizeBytes - (replaceExisting ? 0 : currentBytes))
              .clamp(0, maxTotalSizeBytes);
      final uploaded = await widget.data.pickImagesAndUpload(
        maxFiles: remaining,
        mimeTypes: acceptedMimeTypes,
        allowedExtensions: allowedExtensions,
        maxSizeBytes: maxSizeBytes,
        maxTotalSizeBytes: remainingBytes,
      );
      if (uploaded.isEmpty || !mounted) return;
      final staleMasks = <AssetView>[];
      setState(() {
        if (replaceExisting) {
          _assetsByField[field] = List<AssetView>.from(uploaded);
        } else {
          (_assetsByField[field] ??= []).addAll(uploaded);
        }
        staleMasks.addAll(_clearDependentMaskFields(feature, field));
      });
      _refreshTaskTitleFromAssets(feature);
      await _deleteTemporaryDerivedAssets(staleMasks);
    } catch (exception) {
      if (mounted) setState(() => _error = '$exception');
    } finally {
      if (mounted) {
        setState(() => _uploadingAssetFields.remove(field));
      }
    }
  }

  Future<void> _chooseLibraryAssets(
    FeatureDetail feature,
    String field, {
    required List<String> acceptedMimeTypes,
    required List<String> allowedExtensions,
    required int maximum,
    required int? maxSizeBytes,
    required int? maxTotalSizeBytes,
    bool replaceExisting = false,
  }) async {
    final selectedIds = (_assetsByField[field] ?? const <AssetView>[])
        .map((asset) => asset.id)
        .toSet();
    final excludedIds = _excludedAssetIds(feature, field);
    final candidates = widget.data.assets
        .where((asset) =>
            asset.available &&
            asset.origin == 'USER_UPLOAD' &&
            !selectedIds.contains(asset.id) &&
            !excludedIds.contains(asset.id) &&
            _assetMatchesOptions(
              asset,
              acceptedMimeTypes,
              allowedExtensions,
            ))
        .toList()
      ..sort((left, right) => right.createdAt.compareTo(left.createdAt));
    final selected = await showDialog<List<AssetView>>(
      context: context,
      builder: (context) => _AssetLibrarySelectionDialog(
        assets: candidates,
        maximum: maximum,
      ),
    );
    if (selected == null || selected.isEmpty || !mounted) return;
    final oversized = maxSizeBytes == null
        ? null
        : selected.where((asset) => asset.sizeBytes > maxSizeBytes).firstOrNull;
    if (oversized != null) {
      setState(() {
        _error = '${oversized.name} 不能超过 ${_formatBytes(maxSizeBytes!)}';
      });
      return;
    }
    final currentBytes = replaceExisting
        ? 0
        : (_assetsByField[field] ?? const <AssetView>[])
            .fold<int>(0, (sum, asset) => sum + asset.sizeBytes);
    final selectedBytes =
        selected.fold<int>(0, (sum, asset) => sum + asset.sizeBytes);
    if (maxTotalSizeBytes != null &&
        currentBytes + selectedBytes > maxTotalSizeBytes) {
      setState(() {
        _error = '所选文件总大小不能超过 ${_formatBytes(maxTotalSizeBytes)}';
      });
      return;
    }
    final staleMasks = <AssetView>[];
    setState(() {
      if (replaceExisting) {
        _assetsByField[field] = List<AssetView>.from(selected);
      } else {
        (_assetsByField[field] ??= []).addAll(selected);
      }
      staleMasks.addAll(_clearDependentMaskFields(feature, field));
      _error = null;
    });
    _refreshTaskTitleFromAssets(feature);
    await _deleteTemporaryDerivedAssets(staleMasks);
  }

  Set<String> _excludedAssetIds(
    FeatureDetail feature,
    String field,
  ) {
    final rawFields =
        feature.fieldOptions(field)['excludeAssetsSelectedInFields'];
    final fields = rawFields is List
        ? rawFields.map((value) => value.toString())
        : rawFields == null
            ? const <String>[]
            : <String>[rawFields.toString()];
    return {
      for (final excludedField in fields)
        for (final asset
            in _assetsByField[excludedField] ?? const <AssetView>[])
          asset.id,
    };
  }

  void _removeAsset(
    FeatureDetail feature,
    String field,
    AssetView asset,
  ) {
    final removed = <AssetView>[asset];
    setState(() {
      _assetsByField[field]?.remove(asset);
      removed.addAll(_clearDependentMaskFields(feature, field));
    });
    _refreshTaskTitleFromAssets(feature);
    unawaited(_deleteTemporaryDerivedAssets(removed));
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

  Future<void> _deleteTemporaryDerivedAssets(
    Iterable<AssetView> assets,
  ) async {
    for (final asset in assets) {
      await _deleteTemporaryDerivedAsset(asset);
    }
  }

  Future<void> _deleteTemporaryDerivedAsset(AssetView asset) async {
    if (!_temporaryDerivedAssetIds.contains(asset.id)) return;
    try {
      await widget.data.api.deleteAsset(asset.id);
      _temporaryDerivedAssetIds.remove(asset.id);
    } catch (_) {
      // The backend also clears submitted and expired temporary assets.
    }
  }

  Future<void> _optimizePrompt(FeatureDetail feature, String field) async {
    final controller = _controllers[field];
    if (controller == null) return;
    final originalText = controller.text;
    if (originalText.trim().isEmpty || _optimizingPromptField != null) return;

    setState(() {
      _optimizingPromptField = field;
      _promptAssistErrors.remove(field);
    });
    try {
      final optimized = await widget.data.api.optimizePrompt(
        featureCode: feature.id,
        field: field,
        currentText: originalText,
        parameters: _currentPromptParameters(feature),
        assetIdsByField: _currentPromptAssetIds(feature),
      );
      if (!mounted) return;
      setState(() {
        _promptUndoStore.captureOriginal(field, originalText);
        controller
          ..text = optimized
          ..selection = TextSelection.collapsed(offset: optimized.length);
        _optimizingPromptField = null;
        _promptAssistErrors.remove(field);
      });
    } catch (exception) {
      if (!mounted) return;
      setState(() {
        _optimizingPromptField = null;
        _promptAssistErrors[field] = '$exception';
      });
    }
  }

  void _undoPromptOptimization(String field) {
    final controller = _controllers[field];
    if (controller == null) return;
    final previous = _promptUndoStore.takeOriginal(field);
    if (previous == null) return;
    setState(() {
      controller
        ..text = previous
        ..selection = TextSelection.collapsed(offset: previous.length);
      _promptAssistErrors.remove(field);
    });
  }

  Map<String, Object?> _currentPromptParameters(FeatureDetail feature) {
    final parameters = <String, Object?>{};
    for (final field in feature.fieldOrder) {
      if (!feature.isFieldVisible(field, _values)) continue;
      final schema =
          Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
      final widgetType = feature.widgetFor(field) ?? 'text';
      if (_isAssetField(schema, widgetType)) continue;
      final value = _effectiveFieldValue(feature, field, schema);
      if (value != null && value.toString().isNotEmpty) {
        parameters[field] = value;
      }
    }
    return parameters;
  }

  Map<String, List<String>> _currentPromptAssetIds(FeatureDetail feature) {
    final assets = <String, List<String>>{};
    for (final field in feature.fieldOrder) {
      if (!feature.isFieldVisible(field, _values)) continue;
      if (_assetFieldRequiresReferenceSupport(feature, field) &&
          _referenceInputsDisabledByModel(feature)) {
        continue;
      }
      final ids = (_assetsByField[field] ?? const <AssetView>[])
          .map((asset) => asset.id)
          .toList();
      if (ids.isNotEmpty) assets[field] = ids;
    }
    return assets;
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
        if (_assetFieldRequiresReferenceSupport(feature, field) &&
            _referenceInputsDisabledByModel(feature)) {
          continue;
        }
        final assets = _assetsByField[field] ?? const <AssetView>[];
        final itemBounds =
            _assetItemBounds(feature, field, feature.fieldOptions(field));
        if (assets.length < itemBounds.minItems) {
          setState(() => _error =
              '${schema['title'] ?? field}至少需要 ${itemBounds.minItems} 个文件');
          return;
        }
        if (assets.length > itemBounds.maxItems) {
          setState(() => _error =
              '${schema['title'] ?? field}最多只能选择 ${itemBounds.maxItems} 个文件');
          return;
        }
        if (assets.isNotEmpty) {
          final assetIds = assets.map((asset) => asset.id).toList();
          parameters[field] =
              schema['type'] == 'array' ? assetIds : assetIds.first;
        }
        continue;
      }
      final value = _effectiveFieldValue(feature, field, schema);
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
      if (_assetFieldRequiresReferenceSupport(feature, field) &&
          _referenceInputsDisabledByModel(feature)) {
        continue;
      }
      for (final asset in _assetsByField[field] ?? const <AssetView>[]) {
        if (!inputAssetIds.contains(asset.id)) inputAssetIds.add(asset.id);
      }
    }
    final maxTotalSizeBytes =
        _integerOption(feature.config, 'maxTotalSizeBytes');
    if (maxTotalSizeBytes != null) {
      final selectedAssets = <String, AssetView>{};
      for (final assets in _assetsByField.values) {
        for (final asset in assets) {
          selectedAssets[asset.id] = asset;
        }
      }
      final totalBytes = selectedAssets.values
          .fold<int>(0, (sum, asset) => sum + asset.sizeBytes);
      if (totalBytes > maxTotalSizeBytes) {
        setState(() {
          _submitting = false;
          _error = '全部文件合计不能超过 ${_formatBytes(maxTotalSizeBytes)}';
        });
        return;
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
        onRunCreated: (runId) {
          _derivedAssetsHandedOff = true;
          _temporaryDerivedAssetIds.clear();
          executionController.attachRun(runId);
        },
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
            _clearHandedOffDerivedFields(feature);
            _submitting = false;
            _status = null;
            _error = null;
          });
          return;
        }
        executionController.fail(exception.message);
        setState(() {
          _clearHandedOffDerivedFields(feature);
          _submitting = false;
          _status = null;
          _error = exception.message;
        });
      }
    } catch (exception) {
      if (mounted) {
        executionController.fail('$exception');
        setState(() {
          _clearHandedOffDerivedFields(feature);
          _submitting = false;
          _status = null;
          _error = '$exception';
        });
      }
    }
  }

  Object? _effectiveFieldValue(
    FeatureDetail feature,
    String field,
    Map<String, dynamic> schema,
  ) {
    final revisionReference = feature.revisionArtifactReference;
    final modeField = revisionReference['modeField']?.toString();
    if (field == modeField && _referenceInputsDisabledByModel(feature)) {
      return revisionReference['disabledValue']?.toString() ?? 'NONE';
    }
    if (feature.widgetFor(field) == 'slider' ||
        schema['type'] == 'boolean' ||
        schema['enum'] is List) {
      return _values[field];
    }
    final raw = _controllers[field]?.text.trim() ?? '';
    return switch (schema['type']) {
      'integer' => int.tryParse(raw),
      'number' => double.tryParse(raw),
      _ => raw,
    };
  }

  void _clearHandedOffDerivedFields(FeatureDetail feature) {
    if (!_derivedAssetsHandedOff) return;
    for (final field in feature.fieldOrder) {
      if (feature.widgetFor(field) == 'image_mask') {
        _assetsByField[field] = [];
      }
    }
    _derivedAssetsHandedOff = false;
  }

  Widget _buildActions(FeatureDetail feature) {
    final executeButton = SizedBox(
      height: 48,
      child: FilledButton.icon(
        onPressed: _submitting ||
                _optimizingPromptField != null ||
                _uploadingAssetFields.isNotEmpty
            ? null
            : () => _execute(feature),
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
            onPressed: _submitting ||
                    _optimizingPromptField != null ||
                    _uploadingAssetFields.isNotEmpty
                ? null
                : () => _resetParameters(feature),
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
      _promptAssistErrors.remove(field);
    });
  }

  void _resetParameters(FeatureDetail feature) {
    final removedDerivedAssets = <AssetView>[];
    for (final field in feature.fieldOrder) {
      if (feature.widgetFor(field) == 'image_mask') {
        removedDerivedAssets.addAll(
          _assetsByField[field] ?? const <AssetView>[],
        );
      }
    }
    setState(() {
      _assetsByField.clear();
      _promptUndoStore.clear();
      _promptAssistErrors.clear();
      for (final field in feature.fieldOrder) {
        final schema =
            Map<String, dynamic>.from(feature.properties[field] as Map? ?? {});
        final defaultValue = schema['default'];
        final widgetType = feature.widgetFor(field) ?? 'text';
        if (widgetType == 'slider') {
          _values[field] = _sliderConfig(
            schema,
            feature.fieldOptions(field),
            defaultValue,
          ).value;
        } else if (schema['type'] == 'boolean') {
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
    _refreshTaskTitleFromAssets(feature);
    unawaited(_deleteTemporaryDerivedAssets(removedDerivedAssets));
  }

  static bool _isAssetField(Map<String, dynamic> schema, String widgetType) {
    return schema['format'] == 'binary' ||
        schema['type'] == 'asset' ||
        const {'file', 'image', 'image_mask', 'audio', 'video'}
            .contains(widgetType);
  }

  _AssetItemBounds _assetItemBounds(
    FeatureDetail feature,
    String field,
    Map<String, dynamic> options,
  ) {
    var minimum = feature.requiredFields.contains(field) ? 1 : 0;
    var maximum = _integerOption(options, 'maxItems') ?? 1;
    final rawRule = options['itemCountByFieldPresence'];
    if (rawRule is Map) {
      final rule = Map<String, dynamic>.from(rawRule);
      final dependency = rule['field']?.toString();
      if (dependency != null && dependency.isNotEmpty) {
        final present = _assetsByField[dependency]?.isNotEmpty ?? false;
        final rawBranch = rule[present ? 'present' : 'absent'];
        if (rawBranch is Map) {
          final branch = Map<String, dynamic>.from(rawBranch);
          minimum = _integerOption(branch, 'minItems') ?? minimum;
          maximum = _integerOption(branch, 'maxItems') ?? maximum;
        }
      }
    }
    maximum = maximum.clamp(1, 100);
    return _AssetItemBounds(
      minItems: minimum.clamp(0, maximum),
      maxItems: maximum,
    );
  }

  void _refreshTaskTitleFromAssets(FeatureDetail feature) {
    if (_taskTitleEdited || widget.request.isRevision) return;
    final rawConfig = feature.config['taskTitleFromAssets'];
    if (rawConfig is! Map) return;
    final config = Map<String, dynamic>.from(rawConfig);
    final field = config['field']?.toString();
    final baselineField = config['baselineField']?.toString();
    final comparisonField = config['comparisonField']?.toString();
    final source = field == null ? null : _assetsByField[field]?.firstOrNull;
    final baseline = baselineField == null
        ? null
        : _assetsByField[baselineField]?.firstOrNull;
    final comparisons = comparisonField == null
        ? const <AssetView>[]
        : _assetsByField[comparisonField] ?? const <AssetView>[];
    String? title;
    if (source != null) {
      title = (config['template']?.toString() ?? '{name}')
          .replaceAll('{name}', _assetBaseName(source.name));
    } else if (baseline != null) {
      title = (config['baselineTemplate']?.toString() ?? '{name} 多文档对比')
          .replaceAll('{name}', _assetBaseName(baseline.name));
    } else if (comparisons.isNotEmpty) {
      title =
          (config['comparisonTemplate']?.toString() ?? '{name} 等 {count} 份文档对比')
              .replaceAll('{name}', _assetBaseName(comparisons.first.name))
              .replaceAll('{count}', comparisons.length.toString());
    }
    if (title == null || title.trim().isEmpty) {
      title = widget.request.entry.title;
    }
    title = String.fromCharCodes(title.runes.take(240));
    _nameController.value = TextEditingValue(
      text: title,
      selection: TextSelection.collapsed(offset: title.length),
    );
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

class _AssetItemBounds {
  const _AssetItemBounds({
    required this.minItems,
    required this.maxItems,
  });

  final int minItems;
  final int maxItems;
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

class _AssetLibrarySelectionDialog extends StatefulWidget {
  const _AssetLibrarySelectionDialog({
    required this.assets,
    required this.maximum,
  });

  final List<AssetView> assets;
  final int maximum;

  @override
  State<_AssetLibrarySelectionDialog> createState() =>
      _AssetLibrarySelectionDialogState();
}

class _AssetLibrarySelectionDialogState
    extends State<_AssetLibrarySelectionDialog> {
  final Set<String> _selected = {};

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('从我的文件选择'),
        content: SizedBox(
          width: double.maxFinite,
          child: widget.assets.isEmpty
              ? const Padding(
                  padding: EdgeInsets.symmetric(vertical: 28),
                  child: Text(
                    '没有符合当前功能要求的文件',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppColors.muted),
                  ),
                )
              : ListView.builder(
                  shrinkWrap: true,
                  itemCount: widget.assets.length,
                  itemBuilder: (context, index) {
                    final asset = widget.assets[index];
                    final selected = _selected.contains(asset.id);
                    return CheckboxListTile(
                      value: selected,
                      controlAffinity: ListTileControlAffinity.leading,
                      title: Text(
                        asset.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(_formatBytes(asset.sizeBytes)),
                      onChanged: (value) {
                        setState(() {
                          if (value == true &&
                              _selected.length < widget.maximum) {
                            _selected.add(asset.id);
                          } else {
                            _selected.remove(asset.id);
                          }
                        });
                      },
                    );
                  },
                ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: _selected.isEmpty
                ? null
                : () => Navigator.pop(
                      context,
                      widget.assets
                          .where((asset) => _selected.contains(asset.id))
                          .toList(),
                    ),
            child: Text('添加 ${_selected.length} 个'),
          ),
        ],
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
  Widget build(BuildContext context) {
    if (asset.isImage) {
      return SizedBox(
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
            if (onRemove != null) _removeButton(),
          ],
        ),
      );
    }
    return SizedBox(
      width: 272,
      child: Stack(
        children: [
          Container(
            width: double.infinity,
            constraints: const BoxConstraints(minHeight: 72),
            padding: const EdgeInsets.fromLTRB(12, 10, 34, 10),
            decoration: BoxDecoration(
              color: AppColors.wash,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: AppColors.line),
            ),
            child: Row(children: [
              Icon(_fileIcon(asset.name), size: 27, color: AppColors.accent),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      asset.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        height: 1.25,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      _formatBytes(asset.sizeBytes),
                      style:
                          const TextStyle(color: AppColors.muted, fontSize: 10),
                    ),
                  ],
                ),
              ),
            ]),
          ),
          if (onRemove != null) _removeButton(dark: false),
        ],
      ),
    );
  }

  Widget _removeButton({bool dark = true}) => Positioned(
        top: 4,
        right: 4,
        child: InkWell(
          onTap: onRemove,
          borderRadius: BorderRadius.circular(12),
          child: Container(
            decoration: BoxDecoration(
              color: dark ? Colors.black54 : Colors.white,
              shape: BoxShape.circle,
              border: dark ? null : Border.all(color: AppColors.line),
            ),
            padding: const EdgeInsets.all(2),
            child: Icon(
              Icons.close_rounded,
              color: dark ? Colors.white : AppColors.muted,
              size: 16,
            ),
          ),
        ),
      );
}

IconData _fileIcon(String name) {
  final extension = name.contains('.')
      ? name.substring(name.lastIndexOf('.')).toLowerCase()
      : '';
  return switch (extension) {
    '.pdf' => Icons.picture_as_pdf_outlined,
    '.xls' || '.xlsx' || '.csv' => Icons.table_chart_outlined,
    '.ppt' || '.pptx' => Icons.slideshow_outlined,
    '.json' => Icons.data_object_outlined,
    '.md' || '.markdown' || '.txt' => Icons.subject_outlined,
    '.doc' || '.docx' => Icons.description_outlined,
    _ => Icons.insert_drive_file_outlined,
  };
}

String _assetBaseName(String name) {
  final trimmed = name.trim();
  final index = trimmed.lastIndexOf('.');
  return index <= 0 ? trimmed : trimmed.substring(0, index);
}

String _defaultUploadLabel(String widgetType) => switch (widgetType) {
      'file' => '选择并上传文件',
      'audio' => '选择并上传音频',
      'video' => '选择并上传视频',
      _ => '选择并上传图片',
    };

List<String> _mimeTypesForWidget(String widgetType) => switch (widgetType) {
      'image' => ['image/png', 'image/jpeg', 'image/webp'],
      'audio' => ['audio/*'],
      'video' => ['video/*'],
      _ => ['*/*'],
    };

bool _assetMatchesOptions(
  AssetView asset,
  List<String> acceptedMimeTypes,
  List<String> allowedExtensions,
) {
  final normalizedName = asset.name.toLowerCase();
  final extensionMatches = allowedExtensions.isEmpty ||
      allowedExtensions
          .map((value) => value.toLowerCase())
          .any(normalizedName.endsWith);
  if (!extensionMatches) return false;
  if (acceptedMimeTypes.isEmpty || acceptedMimeTypes.contains('*/*')) {
    return true;
  }
  if (asset.mediaType == 'application/octet-stream') return true;
  return acceptedMimeTypes.any((value) {
    if (value == asset.mediaType) return true;
    return value.endsWith('/*') &&
        asset.mediaType.startsWith(value.substring(0, value.length - 1));
  });
}

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

double? _doubleOption(Map<String, dynamic> options, String key) {
  final value = options[key];
  return value is num
      ? value.toDouble()
      : double.tryParse(value?.toString() ?? '');
}

_TaskSliderConfig _sliderConfig(
  Map<String, dynamic> schema,
  Map<String, dynamic> options,
  Object? requested,
) {
  final minimum =
      _doubleOption(schema, 'minimum') ?? _doubleOption(options, 'min') ?? 0;
  final configuredMaximum =
      _doubleOption(schema, 'maximum') ?? _doubleOption(options, 'max') ?? 1;
  final maximum = configuredMaximum > minimum ? configuredMaximum : minimum + 1;
  final configuredStep = _doubleOption(options, 'step') ??
      _doubleOption(schema, 'multipleOf') ??
      (maximum - minimum) / 100;
  final step = configuredStep > 0 ? configuredStep : (maximum - minimum) / 100;
  final legacyValues = options['legacyValues'];
  Object? resolved = requested;
  if (requested is String && legacyValues is Map) {
    resolved = legacyValues[requested] ?? requested;
  }
  final fallback = _doubleOption(schema, 'default') ?? minimum;
  final parsed = resolved is num
      ? resolved.toDouble()
      : double.tryParse(resolved?.toString() ?? '') ?? fallback;
  final divisions = ((maximum - minimum) / step).round().clamp(1, 1000);
  final clamped = parsed.clamp(minimum, maximum).toDouble();
  final snappedIndex = ((clamped - minimum) / step).round();
  final snapped =
      (minimum + snappedIndex * step).clamp(minimum, maximum).toDouble();
  final value = double.parse(snapped.toStringAsFixed(8));
  final decimalPlaces =
      (_integerOption(options, 'decimalPlaces') ?? 2).clamp(0, 6);
  final minimumFractionDigits =
      (_integerOption(options, 'minimumFractionDigits') ?? 0)
          .clamp(0, decimalPlaces);
  final suffix = options['suffix']?.toString() ?? '';
  return _TaskSliderConfig(
    minimum: minimum,
    maximum: maximum,
    value: value,
    divisions: divisions,
    decimalPlaces: decimalPlaces,
    minimumFractionDigits: minimumFractionDigits,
    suffix: suffix,
  );
}

class _TaskSliderConfig {
  const _TaskSliderConfig({
    required this.minimum,
    required this.maximum,
    required this.value,
    required this.divisions,
    required this.decimalPlaces,
    required this.minimumFractionDigits,
    required this.suffix,
  });

  final double minimum;
  final double maximum;
  final double value;
  final int divisions;
  final int decimalPlaces;
  final int minimumFractionDigits;
  final String suffix;

  String get displayValue {
    var formatted = value.toStringAsFixed(decimalPlaces);
    if (formatted.contains('.')) {
      while (formatted.endsWith('0') &&
          formatted.length - formatted.indexOf('.') - 1 >
              minimumFractionDigits) {
        formatted = formatted.substring(0, formatted.length - 1);
      }
      if (formatted.endsWith('.'))
        formatted = formatted.substring(0, formatted.length - 1);
    }
    return '$formatted$suffix';
  }
}

class TaskModelSelector extends StatelessWidget {
  const TaskModelSelector({
    super.key,
    required this.policy,
    required this.selectedCode,
    required this.options,
    required this.enabled,
    required this.onSelected,
  });

  final ModelPolicy policy;
  final String selectedCode;
  final Map<String, dynamic> options;
  final bool enabled;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    if (options['widget'] == 'segmented' && policy.options.length <= 4) {
      final showSelectedIcon = options['showSelectedIcon'] != false;
      final labelMaxLines =
          _integerOption(options, 'labelMaxLines')?.clamp(1, 2) ?? 1;
      final compact = options['compact'] == true;
      return SizedBox(
        width: double.infinity,
        child: SegmentedButton<String>(
          key: ValueKey(
            'task-model-selector-${policy.capability}-segmented',
          ),
          segments: policy.options.map((option) {
            final label = Text(
              option.displayName,
              maxLines: labelMaxLines,
              overflow: labelMaxLines > 1
                  ? TextOverflow.ellipsis
                  : TextOverflow.visible,
              softWrap: labelMaxLines > 1,
              textAlign: TextAlign.center,
            );
            return ButtonSegment<String>(
              value: option.code,
              label: labelMaxLines == 1
                  ? FittedBox(fit: BoxFit.scaleDown, child: label)
                  : label,
            );
          }).toList(),
          selected: {selectedCode},
          showSelectedIcon: showSelectedIcon,
          onSelectionChanged:
              enabled ? (selection) => onSelected(selection.first) : null,
          style: ButtonStyle(
            textStyle: const WidgetStatePropertyAll(
              TextStyle(fontSize: 12),
            ),
            minimumSize:
                compact ? const WidgetStatePropertyAll(Size(0, 44)) : null,
            padding: compact
                ? const WidgetStatePropertyAll(
                    EdgeInsets.symmetric(horizontal: 5, vertical: 10),
                  )
                : null,
            visualDensity: compact ? VisualDensity.compact : null,
            shape: WidgetStatePropertyAll(
              RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
          ),
        ),
      );
    }

    return DropdownButtonFormField<String>(
      key: ValueKey('task-model-selector-${policy.capability}-dropdown'),
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
      onChanged: enabled
          ? (value) {
              if (value != null) onSelected(value);
            }
          : null,
    );
  }
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
      'AUDIO_ENHANCEMENT' => '音频增强模型',
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
