import 'dart:async';

import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../network/api_exception.dart';
import '../network/native_file_picker.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/markdown_output_view.dart';
import 'document_source_page.dart';
import 'task_history_page.dart';

class DocumentQaPage extends StatefulWidget {
  const DocumentQaPage({
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
  State<DocumentQaPage> createState() => _DocumentQaPageState();
}

class _DocumentQaPageState extends State<DocumentQaPage> {
  final TextEditingController _questionController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final List<AssetView> _setupAssets = [];
  TaskDetail? _detail;
  String? _taskId;
  String? _selectedBundleCode;
  String? _status;
  String? _error;
  String? _streamingAnswer;
  String? _pendingQuestion;
  String? _runningRunId;
  bool _loading = true;
  bool _uploading = false;
  bool _sending = false;

  List<ModelBundle> get _bundles => widget.feature.modelBundles;
  Map<String, dynamic> get _documentOptions =>
      widget.feature.fieldOptions('documents');
  Map<String, dynamic> get _documentSchema =>
      _map(widget.feature.properties['documents']);
  Map<String, dynamic> get _questionSchema =>
      _map(widget.feature.properties['question']);
  int get _maxFiles =>
      _positiveInteger(_documentOptions['maxItems']) ??
      _positiveInteger(_documentSchema['maxItems']) ??
      1;
  int get _maxFileBytes =>
      _positiveInteger(_documentOptions['maxFileSizeBytes']) ?? 1;
  int get _maxTotalBytes =>
      _positiveInteger(_documentOptions['maxTotalSizeBytes']) ?? _maxFileBytes;
  int get _maxQuestionLength =>
      _positiveInteger(_questionSchema['maxLength']) ?? 4000;
  List<String> get _extensions =>
      _stringList(_documentOptions['allowedExtensions']);
  List<String> get _mimeTypes {
    final configured = _stringList(_documentOptions['acceptedMimeTypes']);
    return configured.isEmpty ? const ['*/*'] : configured;
  }

  String get _uploadLabel =>
      _documentOptions['uploadLabel']?.toString().trim().isNotEmpty == true
          ? _documentOptions['uploadLabel'].toString().trim()
          : '上传文档';

  ModelBundle? get _selectedBundle {
    final selected = _selectedBundleCode;
    return _bundles.where((bundle) => bundle.code == selected).firstOrNull ??
        _bundles.firstOrNull;
  }

  List<AssetView> get _activeAssets {
    final detail = _detail;
    if (detail == null) return List.unmodifiable(_setupAssets);
    return detail.taskAssets
        .where(
            (relation) => relation.active && relation.role == 'DOCUMENT_SOURCE')
        .map((relation) => relation.asset)
        .toList();
  }

  @override
  void initState() {
    super.initState();
    _taskId = widget.taskId;
    _selectedBundleCode = _bundles.firstOrNull?.code;
    unawaited(_initialize());
  }

  @override
  void dispose() {
    _questionController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _initialize() async {
    if (_taskId != null) {
      await _loadTask();
    } else {
      setState(() => _loading = false);
    }
  }

  Future<void> _loadTask() async {
    final taskId = _taskId;
    if (taskId == null) return;
    try {
      final detail = await widget.data.api.getTask(taskId);
      final latestRun = detail.runs.firstOrNull;
      final matchingBundle = latestRun == null
          ? null
          : _bundles
              .where((bundle) =>
                  _sameModels(bundle.selectedModels, latestRun.selectedModels))
              .firstOrNull;
      if (!mounted) return;
      setState(() {
        _detail = detail;
        _selectedBundleCode = matchingBundle?.code ??
            _selectedBundleCode ??
            _bundles.firstOrNull?.code;
        _loading = false;
        _error = null;
      });
      _scrollToBottom();
    } catch (exception) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = _message(exception);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(
          _detail?.task.title ?? widget.feature.title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        actions: [
          if (_taskId != null)
            IconButton(
              onPressed: _openTaskHistory,
              tooltip: '任务历史',
              icon: const Icon(Icons.history_rounded),
            ),
        ],
      ),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _taskId == null
                ? _buildSetup()
                : _buildConversation(),
      ),
    );
  }

  Widget _buildSetup() {
    final totalBytes =
        _setupAssets.fold<int>(0, (sum, asset) => sum + asset.sizeBytes);
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 32),
      children: [
        Text('选择问答文档', style: Theme.of(context).textTheme.headlineMedium),
        const SizedBox(height: 6),
        const Text('上传新文档，或从“我的文件”选择已保存的文档。'),
        const SizedBox(height: 22),
        _DocumentCapacity(
          count: _setupAssets.length,
          totalBytes: totalBytes,
          maxFiles: _maxFiles,
          maxTotalBytes: _maxTotalBytes,
        ),
        const SizedBox(height: 12),
        if (_setupAssets.isEmpty)
          _SetupEmpty(
            maxFiles: _maxFiles,
            maxFileBytes: _maxFileBytes,
            maxTotalBytes: _maxTotalBytes,
          )
        else
          ..._setupAssets.map(
            (asset) => _DocumentRow(
              asset: asset,
              onRemove: _uploading ? null : () => _removeSetupAsset(asset),
            ),
          ),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _uploading || _setupAssets.length >= _maxFiles
                    ? null
                    : _pickAndUploadDocuments,
                icon: const Icon(Icons.upload_file_outlined),
                label: Text(_uploadLabel),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _uploading || _setupAssets.length >= _maxFiles
                    ? null
                    : _chooseLibraryDocuments,
                icon: const Icon(Icons.folder_outlined),
                label: const Text('我的文件'),
              ),
            ),
          ],
        ),
        const SizedBox(height: 22),
        _buildModelSelector(),
        if (_status != null) ...[
          const SizedBox(height: 14),
          _StatusLine(text: _status!),
        ],
        if (_error != null) ...[
          const SizedBox(height: 14),
          _ErrorLine(text: _error!),
        ],
        const SizedBox(height: 22),
        SizedBox(
          height: 48,
          child: FilledButton.icon(
            onPressed: _uploading || _setupAssets.isEmpty ? null : _startChat,
            icon: const Icon(Icons.forum_outlined),
            label: Text(widget.feature.submitLabel),
          ),
        ),
      ],
    );
  }

  Widget _buildConversation() {
    final artifacts = [
      ...?_detail?.artifacts
    ]..sort((left, right) => left.versionNumber.compareTo(right.versionNumber));
    return Column(
      children: [
        _ConversationToolbar(
          assets: _activeAssets,
          modelSelector: _buildModelSelector(compact: true),
          onManageDocuments:
              _uploading || _sending ? null : _showDocumentManager,
        ),
        if (_status != null) _StatusLine(text: _status!),
        if (_error != null) _ErrorLine(text: _error!),
        Expanded(
          child: artifacts.isEmpty &&
                  !_sending &&
                  (_pendingQuestion == null || _pendingQuestion!.isEmpty)
              ? const _ConversationEmpty()
              : ListView(
                  controller: _scrollController,
                  padding: const EdgeInsets.fromLTRB(16, 18, 16, 24),
                  children: [
                    ...artifacts.map(_buildTurn),
                    if (_pendingQuestion != null)
                      _UserMessage(text: _pendingQuestion!),
                    if (_sending)
                      _AssistantMessage(
                        markdown: _streamingAnswer ?? '',
                        streaming: true,
                        citations: const [],
                        onCitation: (_) {},
                      ),
                  ],
                ),
        ),
        _Composer(
          controller: _questionController,
          sending: _sending,
          canSend: _activeAssets.any((asset) => asset.available),
          onSend: _sendQuestion,
          onCancel: _cancelQuestion,
          maxLength: _maxQuestionLength,
        ),
      ],
    );
  }

  Widget _buildTurn(ArtifactView artifact) {
    final question = artifact.content['question']?.toString() ?? '';
    final answer = artifact.content['answerMarkdown']?.toString() ?? '';
    final citations = _citations(artifact.content['citations']);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _UserMessage(text: question),
        _AssistantMessage(
          markdown: answer,
          citations: citations,
          onCitation: _openCitation,
        ),
      ],
    );
  }

  Widget _buildModelSelector({bool compact = false}) {
    final bundles = _bundles;
    if (bundles.isEmpty) {
      return const _ErrorLine(text: '后端没有配置可用的文档问答模型');
    }
    final selected = _selectedBundle;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (!compact) ...[
          const Text(
            '使用模型',
            style: TextStyle(
              color: AppColors.muted,
              fontSize: 12,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 7),
        ],
        DropdownButtonFormField<String>(
          value: selected?.code,
          isExpanded: true,
          decoration: compact
              ? const InputDecoration(
                  isDense: true,
                  contentPadding:
                      EdgeInsets.symmetric(horizontal: 10, vertical: 10),
                )
              : null,
          items: bundles
              .map(
                (bundle) => DropdownMenuItem<String>(
                  value: bundle.code,
                  child: Text(
                    bundle.displayName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              )
              .toList(),
          onChanged: _sending
              ? null
              : (value) => setState(() => _selectedBundleCode = value),
        ),
        if (!compact && selected?.description.isNotEmpty == true) ...[
          const SizedBox(height: 6),
          Text(
            selected!.description,
            style: const TextStyle(color: AppColors.muted, fontSize: 11),
          ),
        ],
      ],
    );
  }

  Future<void> _startChat() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('开始文档问答'),
        content: Text(
          widget.feature.feeNotice ?? '文档解析、视觉识别、检索重排和回答可能产生模型费用。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确认并开始'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _uploading = true;
      _status = '正在创建问答会话';
      _error = null;
    });
    try {
      final task = await widget.data.api.createTask(
        featureCode: widget.feature.id,
        title: _taskTitle(_setupAssets),
      );
      await widget.data.api.addTaskAssets(
        task.id,
        _setupAssets.map((asset) => asset.id),
      );
      _taskId = task.id;
      await _loadTask();
      await widget.data.refresh();
      if (!mounted) return;
      setState(() {
        _uploading = false;
        _status = null;
      });
    } catch (exception) {
      if (!mounted) return;
      setState(() {
        _uploading = false;
        _status = null;
        _error = _message(exception);
      });
    }
  }

  Future<void> _sendQuestion() async {
    if (_sending) return;
    final question = _questionController.text.trim();
    final taskId = _taskId;
    final bundle = _selectedBundle;
    final assets = _activeAssets.where((asset) => asset.available).toList();
    if (question.isEmpty || taskId == null || bundle == null) return;
    if (assets.isEmpty) {
      setState(() => _error = '请先添加至少一个可用文档');
      return;
    }
    final latestArtifact = _detail?.artifacts
        .where((artifact) => artifact.kind == 'document_answer')
        .fold<ArtifactView?>(
          null,
          (latest, item) =>
              latest == null || item.versionNumber > latest.versionNumber
                  ? item
                  : latest,
        );
    setState(() {
      _sending = true;
      _pendingQuestion = question;
      _streamingAnswer = '';
      _status = '正在准备文档';
      _error = null;
    });
    _questionController.clear();
    _scrollToBottom();
    try {
      await widget.data.api.executeFeature(
        feature: widget.feature,
        taskTitle: _detail?.task.title ?? widget.feature.title,
        projectId: _detail?.task.projectId,
        existingTaskId: taskId,
        baseArtifactId: latestArtifact?.id,
        selectedModels: bundle.selectedModels,
        selectedModelCode: bundle.selectedModels['TEXT_GENERATION'],
        parameters: {
          'documents': assets.map((asset) => asset.id).toList(),
          'question': question,
          'strictGrounding': true,
        },
        inputAssetIds: assets.map((asset) => asset.id).toList(),
        onStatus: (value) {
          if (mounted) setState(() => _status = value);
        },
        onRunCreated: (runId) {
          if (mounted) setState(() => _runningRunId = runId);
        },
        onOutput: (snapshot) {
          if (!mounted || snapshot.channel != 'main') return;
          setState(() => _streamingAnswer = snapshot.content);
          _scrollToBottom();
        },
      );
      await _loadTask();
      await widget.data.refresh();
      if (!mounted) return;
      setState(() {
        _sending = false;
        _pendingQuestion = null;
        _streamingAnswer = null;
        _runningRunId = null;
        _status = null;
      });
    } catch (exception) {
      if (!mounted) return;
      if (_questionController.text.trim().isEmpty) {
        _questionController.text = question;
        _questionController.selection = TextSelection.collapsed(
          offset: _questionController.text.length,
        );
      }
      setState(() {
        _sending = false;
        _pendingQuestion = null;
        _streamingAnswer = null;
        _runningRunId = null;
        _status = null;
        _error = _message(exception);
      });
    }
  }

  Future<void> _cancelQuestion() async {
    final runId = _runningRunId;
    if (runId == null) return;
    try {
      await widget.data.api.cancelRun(runId);
      if (!mounted) return;
      setState(() => _status = '正在取消任务');
    } catch (exception) {
      if (mounted) setState(() => _error = _message(exception));
    }
  }

  Future<void> _showAddDocuments() async {
    if (_activeAssets.length >= _maxFiles) {
      setState(() => _error = '当前会话最多添加$_maxFiles个文档');
      return;
    }
    final choice = await showModalBottomSheet<String>(
      context: context,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.upload_file_outlined),
              title: const Text('上传新文档'),
              onTap: () => Navigator.pop(context, 'upload'),
            ),
            ListTile(
              leading: const Icon(Icons.folder_outlined),
              title: const Text('从我的文件选择'),
              onTap: () => Navigator.pop(context, 'library'),
            ),
          ],
        ),
      ),
    );
    if (!mounted) return;
    if (choice == 'upload') await _pickAndUploadDocuments();
    if (choice == 'library') await _chooseLibraryDocuments();
  }

  Future<void> _pickAndUploadDocuments() async {
    final remaining = _maxFiles - _activeAssets.length;
    if (remaining <= 0) return;
    setState(() {
      _uploading = true;
      _status = '正在选择文档';
      _error = null;
    });
    final uploaded = <AssetView>[];
    try {
      final files = await NativeFilePicker.pickMultiple(
        mimeTypes: _mimeTypes,
        maxFiles: remaining,
      );
      var totalBytes = _activeAssets.fold<int>(
        0,
        (sum, asset) => sum + asset.sizeBytes,
      );
      for (final file in files) {
        try {
          _validateLocalFile(file, totalBytes);
          setState(() => _status = '正在上传 ${file.name}');
          final asset = await widget.data.api.uploadAsset(file);
          uploaded.add(asset);
          totalBytes += asset.sizeBytes;
        } finally {
          await file.cleanup();
        }
      }
      await widget.data.refresh();
      await _attachUploadedDocuments(uploaded);
      if (!mounted) return;
      setState(() {
        _uploading = false;
        _status = null;
      });
    } catch (exception) {
      if (!mounted) return;
      try {
        await widget.data.refresh();
        await _attachUploadedDocuments(uploaded);
      } catch (_) {
        // Keep the original upload/validation error as the user-facing cause.
      }
      if (!mounted) return;
      setState(() {
        _uploading = false;
        _status = null;
        _error = _message(exception);
      });
    }
  }

  Future<void> _attachUploadedDocuments(List<AssetView> uploaded) async {
    if (uploaded.isEmpty) return;
    if (_taskId == null) {
      _addSetupAssets(uploaded);
      return;
    }
    await widget.data.api.addTaskAssets(
      _taskId!,
      uploaded.map((asset) => asset.id),
    );
    await _loadTask();
  }

  Future<void> _chooseLibraryDocuments() async {
    final currentIds = _activeAssets.map((asset) => asset.id).toSet();
    final available = widget.data.assets
        .where((asset) =>
            asset.available &&
            asset.category == 'DOCUMENT' &&
            !currentIds.contains(asset.id) &&
            _supportsName(asset.name))
        .toList();
    final selected = await showDialog<List<AssetView>>(
      context: context,
      builder: (context) => _DocumentLibraryDialog(
        assets: available,
        maximum: _maxFiles - currentIds.length,
      ),
    );
    if (selected == null || selected.isEmpty || !mounted) return;
    final oversized =
        selected.where((asset) => asset.sizeBytes > _maxFileBytes).firstOrNull;
    if (oversized != null) {
      setState(
        () =>
            _error = '单个文件不能超过${_formatBytes(_maxFileBytes)}：${oversized.name}',
      );
      return;
    }
    final total = [
      ..._activeAssets,
      ...selected,
    ].fold<int>(0, (sum, asset) => sum + asset.sizeBytes);
    if (total > _maxTotalBytes) {
      setState(
        () => _error = '会话文档总大小不能超过${_formatBytes(_maxTotalBytes)}',
      );
      return;
    }
    if (_taskId == null) {
      _addSetupAssets(selected);
      return;
    }
    try {
      await widget.data.api.addTaskAssets(
        _taskId!,
        selected.map((asset) => asset.id),
      );
      await _loadTask();
    } catch (exception) {
      if (mounted) setState(() => _error = _message(exception));
    }
  }

  void _addSetupAssets(Iterable<AssetView> assets) {
    setState(() {
      for (final asset in assets) {
        if (_setupAssets.length >= _maxFiles) break;
        if (_setupAssets.any((item) => item.id == asset.id)) continue;
        _setupAssets.add(asset);
      }
      _error = null;
    });
  }

  void _removeSetupAsset(AssetView asset) {
    setState(() => _setupAssets.removeWhere((item) => item.id == asset.id));
  }

  Future<void> _showDocumentManager() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(sheetContext).height * 0.75,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const ListTile(
                title: Text(
                  '当前会话文档',
                  style: TextStyle(fontWeight: FontWeight.w700),
                ),
              ),
              Flexible(
                child: ListView(
                  shrinkWrap: true,
                  children: _activeAssets
                      .map(
                        (asset) => ListTile(
                          leading: const Icon(Icons.description_outlined),
                          title: Text(
                            asset.name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          subtitle: Text(_formatBytes(asset.sizeBytes)),
                          trailing: IconButton(
                            onPressed: _sending
                                ? null
                                : () async {
                                    Navigator.pop(sheetContext);
                                    await _removeTaskAsset(asset);
                                  },
                            tooltip: '移出会话',
                            icon: const Icon(Icons.remove_circle_outline),
                          ),
                        ),
                      )
                      .toList(),
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(16),
                child: SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: _activeAssets.length >= _maxFiles
                        ? null
                        : () {
                            Navigator.pop(sheetContext);
                            _showAddDocuments();
                          },
                    icon: const Icon(Icons.add_rounded),
                    label: const Text('添加文档'),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _removeTaskAsset(AssetView asset) async {
    try {
      await widget.data.api.removeTaskAsset(_taskId!, asset.id);
      await _loadTask();
    } catch (exception) {
      if (mounted) setState(() => _error = _message(exception));
    }
  }

  void _openCitation(_DocumentCitationView citation) {
    final asset = _assetForCitation(citation.assetId);
    if (asset == null) {
      setState(() => _error = '无法读取该来源文件信息');
      return;
    }
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => DocumentSourcePage(
          api: widget.data.api,
          asset: asset,
          marker: citation.marker,
          excerpt: citation.excerpt,
          locator: citation.locator,
        ),
      ),
    );
  }

  AssetView? _assetForCitation(String assetId) {
    for (final relation in _detail?.taskAssets ?? const <TaskAssetView>[]) {
      if (relation.asset.id == assetId) return relation.asset;
    }
    for (final run in _detail?.runs ?? const <RunView>[]) {
      for (final asset in run.inputAssets) {
        if (asset.id == assetId) return asset;
      }
    }
    return widget.data.assets.where((asset) => asset.id == assetId).firstOrNull;
  }

  void _validateLocalFile(PickedLocalFile file, int currentBytes) {
    if (!_supportsName(file.name)) {
      throw ApiException('不支持该文件格式：${file.name}');
    }
    if (file.sizeBytes > _maxFileBytes) {
      throw ApiException(
        '单个文件不能超过${_formatBytes(_maxFileBytes)}：${file.name}',
      );
    }
    if (currentBytes + file.sizeBytes > _maxTotalBytes) {
      throw ApiException(
        '会话文档总大小不能超过${_formatBytes(_maxTotalBytes)}',
      );
    }
  }

  bool _supportsName(String name) {
    final normalized = name.toLowerCase();
    return _extensions.any(normalized.endsWith);
  }

  Future<void> _openTaskHistory() async {
    final taskId = _taskId;
    if (taskId == null) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => TaskHistoryPage(
          taskId: taskId,
          data: widget.data,
        ),
      ),
    );
    if (mounted) await _loadTask();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) return;
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOut,
      );
    });
  }
}

class _ConversationToolbar extends StatelessWidget {
  const _ConversationToolbar({
    required this.assets,
    required this.modelSelector,
    required this.onManageDocuments,
  });

  final List<AssetView> assets;
  final Widget modelSelector;
  final VoidCallback? onManageDocuments;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: AppColors.line)),
        ),
        child: Row(
          children: [
            Expanded(child: modelSelector),
            const SizedBox(width: 10),
            IconButton.outlined(
              onPressed: onManageDocuments,
              tooltip: '管理会话文档',
              icon: Badge(
                label: Text('${assets.length}'),
                child: const Icon(Icons.folder_open_outlined),
              ),
            ),
          ],
        ),
      );
}

class _Composer extends StatelessWidget {
  const _Composer({
    required this.controller,
    required this.sending,
    required this.canSend,
    required this.onSend,
    required this.onCancel,
    required this.maxLength,
  });

  final TextEditingController controller;
  final bool sending;
  final bool canSend;
  final VoidCallback onSend;
  final VoidCallback onCancel;
  final int maxLength;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.fromLTRB(16, 10, 10, 12),
        decoration: const BoxDecoration(
          color: AppColors.paper,
          border: Border(top: BorderSide(color: AppColors.line)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: TextField(
                controller: controller,
                enabled: !sending,
                minLines: 1,
                maxLines: 5,
                maxLength: maxLength,
                decoration: const InputDecoration(
                  hintText: '基于文档提问',
                  counterText: '',
                ),
                textInputAction: TextInputAction.newline,
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filled(
              onPressed: sending ? onCancel : (canSend ? onSend : null),
              tooltip: sending ? '停止回答' : '发送',
              icon: Icon(
                sending ? Icons.stop_rounded : Icons.arrow_upward_rounded,
              ),
            ),
          ],
        ),
      );
}

class _UserMessage extends StatelessWidget {
  const _UserMessage({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Align(
        alignment: Alignment.centerRight,
        child: Container(
          constraints: const BoxConstraints(maxWidth: 330),
          margin: const EdgeInsets.only(left: 44, bottom: 14),
          padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 10),
          decoration: BoxDecoration(
            color: AppColors.accentSoft,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Text(text, style: const TextStyle(color: AppColors.ink)),
        ),
      );
}

class _AssistantMessage extends StatelessWidget {
  const _AssistantMessage({
    required this.markdown,
    required this.citations,
    required this.onCitation,
    this.streaming = false,
  });

  final String markdown;
  final List<_DocumentCitationView> citations;
  final ValueChanged<_DocumentCitationView> onCitation;
  final bool streaming;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(right: 24, bottom: 22),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Padding(
                  padding: EdgeInsets.only(top: 2),
                  child: Icon(
                    Icons.auto_awesome_rounded,
                    size: 19,
                    color: AppColors.accent,
                  ),
                ),
                const SizedBox(width: 9),
                Expanded(
                  child: streaming && markdown.isEmpty
                      ? const Padding(
                          padding: EdgeInsets.symmetric(vertical: 2),
                          child: Text(
                            '正在解析文档并检索来源...',
                            style: TextStyle(color: AppColors.muted),
                          ),
                        )
                      : MarkdownOutputView(
                          markdown: markdown,
                          streaming: streaming,
                        ),
                ),
              ],
            ),
            if (citations.isNotEmpty) ...[
              const SizedBox(height: 10),
              Padding(
                padding: const EdgeInsets.only(left: 28),
                child: Wrap(
                  spacing: 7,
                  runSpacing: 7,
                  children: citations
                      .map(
                        (citation) => ActionChip(
                          avatar: const Icon(
                            Icons.description_outlined,
                            size: 16,
                          ),
                          label: Text(
                            '[${citation.marker}] ${citation.fileName}',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          onPressed: () => onCitation(citation),
                        ),
                      )
                      .toList(),
                ),
              ),
            ],
          ],
        ),
      );
}

class _DocumentCapacity extends StatelessWidget {
  const _DocumentCapacity({
    required this.count,
    required this.totalBytes,
    required this.maxFiles,
    required this.maxTotalBytes,
  });

  final int count;
  final int totalBytes;
  final int maxFiles;
  final int maxTotalBytes;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Text(
            '$count/$maxFiles 个文件',
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          const Spacer(),
          Text(
            '${_formatBytes(totalBytes)} / ${_formatBytes(maxTotalBytes)}',
            style: const TextStyle(color: AppColors.muted, fontSize: 12),
          ),
        ],
      );
}

class _DocumentRow extends StatelessWidget {
  const _DocumentRow({
    required this.asset,
    required this.onRemove,
  });

  final AssetView asset;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 11),
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: AppColors.line)),
        ),
        child: Row(
          children: [
            const Icon(Icons.description_outlined, color: AppColors.accent),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    asset.name,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 3),
                  Text(
                    _formatBytes(asset.sizeBytes),
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 11,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              onPressed: onRemove,
              tooltip: '移除',
              icon: const Icon(Icons.close_rounded, size: 19),
            ),
          ],
        ),
      );
}

class _DocumentLibraryDialog extends StatefulWidget {
  const _DocumentLibraryDialog({
    required this.assets,
    required this.maximum,
  });

  final List<AssetView> assets;
  final int maximum;

  @override
  State<_DocumentLibraryDialog> createState() => _DocumentLibraryDialogState();
}

class _DocumentLibraryDialogState extends State<_DocumentLibraryDialog> {
  final Set<String> _selected = {};

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('从我的文件选择'),
        content: SizedBox(
          width: double.maxFinite,
          child: widget.assets.isEmpty
              ? const Padding(
                  padding: EdgeInsets.symmetric(vertical: 30),
                  child: Text(
                    '没有可选择的文档',
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

class _SetupEmpty extends StatelessWidget {
  const _SetupEmpty({
    required this.maxFiles,
    required this.maxFileBytes,
    required this.maxTotalBytes,
  });

  final int maxFiles;
  final int maxFileBytes;
  final int maxTotalBytes;

  @override
  Widget build(BuildContext context) => Container(
        height: 180,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: AppColors.wash,
          border: Border.all(color: AppColors.line),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.upload_file_outlined,
              color: AppColors.muted,
              size: 32,
            ),
            const SizedBox(height: 10),
            const Text('上传或选择文档开始问答'),
            const SizedBox(height: 4),
            Text(
              '最多$maxFiles个文件，单个${_formatBytes(maxFileBytes)}，'
              '总计${_formatBytes(maxTotalBytes)}',
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.muted, fontSize: 11),
            ),
          ],
        ),
      );
}

class _ConversationEmpty extends StatelessWidget {
  const _ConversationEmpty();

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(
                Icons.forum_outlined,
                size: 34,
                color: AppColors.muted,
              ),
              const SizedBox(height: 12),
              Text(
                '可以开始提问了',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 5),
              const Text(
                '回答只依据当前会话文档，并提供可点击来源。',
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      );
}

class _StatusLine extends StatelessWidget {
  const _StatusLine({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        color: AppColors.accentSoft,
        child: Row(
          children: [
            const SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                text,
                style: const TextStyle(
                  color: AppColors.accent,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ],
        ),
      );
}

class _ErrorLine extends StatelessWidget {
  const _ErrorLine({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
        color: const Color(0xFFFFF1F0),
        child: Text(
          text,
          style: const TextStyle(color: AppColors.danger, fontSize: 12),
        ),
      );
}

class _DocumentCitationView {
  const _DocumentCitationView({
    required this.marker,
    required this.assetId,
    required this.fileName,
    required this.excerpt,
    required this.locator,
  });

  final String marker;
  final String assetId;
  final String fileName;
  final String excerpt;
  final Map<String, dynamic> locator;
}

List<_DocumentCitationView> _citations(Object? value) {
  if (value is! List) return const [];
  return value
      .whereType<Map>()
      .map((item) {
        final map = Map<String, dynamic>.from(item);
        return _DocumentCitationView(
          marker: map['marker']?.toString() ?? '',
          assetId: map['assetId']?.toString() ?? '',
          fileName: map['fileName']?.toString() ?? '',
          excerpt: map['excerpt']?.toString() ?? '',
          locator: map['locator'] is Map
              ? Map<String, dynamic>.from(map['locator'] as Map)
              : const {},
        );
      })
      .where((citation) => citation.assetId.isNotEmpty)
      .toList();
}

bool _sameModels(Map<String, String> left, Map<String, String> right) {
  if (left.length != right.length) return false;
  for (final entry in left.entries) {
    if (right[entry.key] != entry.value) return false;
  }
  return true;
}

String _taskTitle(List<AssetView> assets) {
  if (assets.isEmpty) return '文档问答';
  final first = assets.first.name.replaceFirst(RegExp(r'\.[^.]+$'), '');
  final suffix = assets.length == 1 ? '问答' : '等${assets.length}个文档';
  final value = '$first$suffix';
  return value.length <= 240 ? value : value.substring(0, 240);
}

String _formatBytes(int bytes) {
  if (bytes >= 1024 * 1024) {
    final value = bytes / (1024 * 1024);
    return '${value.toStringAsFixed(value == value.roundToDouble() ? 0 : 1)} MB';
  }
  if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  return '$bytes B';
}

String _message(Object exception) =>
    exception.toString().replaceFirst('ApiException: ', '');

Map<String, dynamic> _map(Object? value) =>
    value is Map ? Map<String, dynamic>.from(value) : const {};

List<String> _stringList(Object? value) =>
    value is List ? value.map((item) => item.toString()).toList() : const [];

int? _positiveInteger(Object? value) {
  final parsed =
      value is num ? value.toInt() : int.tryParse(value?.toString() ?? '');
  return parsed != null && parsed > 0 ? parsed : null;
}
