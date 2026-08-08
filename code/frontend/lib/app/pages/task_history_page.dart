import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../network/backend_api.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import 'video_generate_page.dart';
import '../widgets/task_sheet.dart';

class TaskHistoryPage extends StatefulWidget {
  const TaskHistoryPage({super.key, required this.taskId, required this.data});
  final String taskId;
  final AppDataController data;
  @override
  State<TaskHistoryPage> createState() => _TaskHistoryPageState();
}

class _TaskHistoryPageState extends State<TaskHistoryPage> {
  late Future<TaskDetail> _future;

  @override
  void initState() {
    super.initState();
    _future = widget.data.api.getTask(widget.taskId);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('任务记录')),
      body: FutureBuilder<TaskDetail>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return _LoadError(
                message: snapshot.error.toString(), onRetry: _reload);
          }
          final detail = snapshot.requireData;
          final feature = widget.data.featureByCode(detail.task.featureCode);
          return RefreshIndicator(
            onRefresh: _reload,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 18, 20, 32),
              children: [
                Text(detail.task.title,
                    style: Theme.of(context).textTheme.headlineMedium),
                const SizedBox(height: 6),
                Text(feature?.title ?? detail.task.featureCode,
                    style: const TextStyle(color: AppColors.muted)),
                if (detail.artifacts.isNotEmpty && feature != null) ...[
                  const SizedBox(height: 18),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: () =>
                          _continueFrom(detail, detail.artifacts.first),
                      icon: const Icon(Icons.edit_note_rounded),
                      label:
                          Text('继续修改 v${detail.artifacts.first.versionNumber}'),
                    ),
                  ),
                ],
                const SizedBox(height: 28),
                Text('成果 (${detail.artifacts.length})',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                if (detail.artifacts.isEmpty)
                  const _EmptyLine(text: '这个任务还没有生成成果。')
                else
                  ...detail.artifacts.map((artifact) => _ArtifactRow(
                        artifact: artifact,
                        onTap: () {
                          openArtifactResultPage(
                            context,
                            data: widget.data,
                            artifact: artifact,
                            rendererKey: feature?.rendererKey,
                            onContinue: feature == null
                                ? null
                                : () {
                                    Navigator.of(context).pop();
                                    _continueFrom(detail, artifact);
                                  },
                          );
                        },
                      )),
                const SizedBox(height: 28),
                Text('执行记录 (${detail.runs.length})',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                ...detail.runs.map((run) => _RunRow(
                      run: run,
                      artifact: detail.artifacts
                          .where((artifact) => artifact.runId == run.id)
                          .firstOrNull,
                    )),
              ],
            ),
          );
        },
      ),
    );
  }

  Future<void> _reload() async {
    final next = widget.data.api.getTask(widget.taskId);
    setState(() {
      _future = next;
    });
    await next;
  }

  Future<void> _continueFrom(TaskDetail detail, ArtifactView artifact) async {
    final workspace = widget.data.workspaceForFeature(detail.task.featureCode);
    final feature = widget.data.featureByCode(detail.task.featureCode);
    final run =
        detail.runs.where((item) => item.id == artifact.runId).firstOrNull;
    if (workspace == null || feature == null || run == null) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('无法读取该版本的功能和参数')),
        );
      }
      return;
    }
    if (feature.id == 'video.generate') {
      final videoFeature = await widget.data.api.getFeature(feature.id);
      await Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (context) => VideoGeneratePage(
            data: widget.data,
            workspace: workspace,
            feature: videoFeature,
            taskId: detail.task.id,
          ),
        ),
      );
      if (mounted) await _reload();
      return;
    }
    await showTaskSheet(
      context,
      data: widget.data,
      request: TaskLaunchRequest(
        workspace: workspace,
        entry: feature,
        initialParameters: run.parameters,
        initialAssetIds: revisionInputAssetIds(
            feature: feature, run: run, artifact: artifact),
        existingTaskId: detail.task.id,
        baseArtifactId: artifact.id,
        baseVersion: artifact.versionNumber,
        taskTitle: detail.task.title,
        projectId: detail.task.projectId,
        initialModelCode: run.selectedModelCode,
        initialModels: run.selectedModels,
        baseArtifactText: artifact.content['text']?.toString(),
        baseArtifactAssetIds: _artifactAssetIds(artifact),
        baseArtifactAssets: artifact.assets,
      ),
    );
    if (mounted) await _reload();
  }
}

List<String> _artifactAssetIds(ArtifactView artifact) {
  final single = artifact.content['assetId']?.toString();
  if (single != null && single.isNotEmpty) return [single];
  final multiple = artifact.content['assetIds'];
  if (multiple is List) {
    return multiple
        .map((item) => item.toString())
        .where((item) => item.isNotEmpty)
        .toList();
  }
  return const [];
}

List<String> revisionInputAssetIds({
  required FeatureEntry feature,
  required RunView run,
  required ArtifactView artifact,
}) {
  if (feature.id == 'image.generate') return run.inputAssetIds;
  if (feature.resultType != 'image') return run.inputAssetIds;
  final artifactAssetIds = _artifactAssetIds(artifact);
  if (artifactAssetIds.isNotEmpty) return artifactAssetIds;
  return run.inputAssetIds;
}

class _ArtifactRow extends StatelessWidget {
  const _ArtifactRow({required this.artifact, required this.onTap});
  final ArtifactView artifact;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final deleted = artifact.assets.isNotEmpty &&
        artifact.assets.every((asset) => !asset.available);
    return ListTile(
      contentPadding: EdgeInsets.zero,
      onTap: onTap,
      leading: Icon(
        deleted ? Icons.delete_outline_rounded : Icons.description_outlined,
        color: deleted ? AppColors.muted : AppColors.accent,
      ),
      title: Text(artifact.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text(
          'v${artifact.versionNumber} · ${artifact.kind} · ${_shortDate(artifact.createdAt)}'),
      trailing: const Icon(Icons.chevron_right_rounded, color: AppColors.muted),
    );
  }
}

class _RunRow extends StatelessWidget {
  const _RunRow({required this.run, required this.artifact});
  final RunView run;
  final ArtifactView? artifact;

  @override
  Widget build(BuildContext context) {
    final errorMessage = run.status == 'FAILED'
        ? BackendApi.runFailureMessage(run.errorCode, run.errorMessage)
        : null;
    final deletedInputs =
        run.inputAssets.where((asset) => !asset.available).length;
    final configuration = runConfigurationSummary(run, artifact);
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 12),
      decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: AppColors.line))),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('第 ${run.runNumber} 次执行 · ${_shortDate(run.createdAt)}'),
            if (configuration.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(
                configuration,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: AppColors.muted, fontSize: 12),
              ),
            ],
            if (errorMessage != null) ...[
              const SizedBox(height: 4),
              Text(
                errorMessage,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: AppColors.muted, fontSize: 12),
              ),
            ],
            if (deletedInputs > 0) ...[
              const SizedBox(height: 4),
              Text(
                '$deletedInputs 个原文件已删除，重试或继续修改时请重新上传',
                style: const TextStyle(
                  color: AppColors.danger,
                  fontSize: 12,
                ),
              ),
            ],
          ]),
        ),
        const SizedBox(width: 12),
        Text(run.executionPhase ?? run.status,
            style: TextStyle(
                color: run.status == 'SUCCEEDED'
                    ? AppColors.accent
                    : AppColors.muted,
                fontSize: 11,
                fontWeight: FontWeight.w700)),
      ]),
    );
  }
}

class _LoadError extends StatelessWidget {
  const _LoadError({required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton(onPressed: onRetry, child: const Text('重新加载')),
          ]),
        ),
      );
}

class _EmptyLine extends StatelessWidget {
  const _EmptyLine({required this.text});
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 16),
        child: Text(text, style: const TextStyle(color: AppColors.muted)),
      );
}

String runConfigurationSummary(RunView run, ArtifactView? artifact) {
  final values = <String>[];
  final metadata = artifact?.metadata ?? const <String, dynamic>{};
  final model = metadata['model']?.toString().trim();
  final selectedModel =
      run.selectedModels.values.firstOrNull ?? run.selectedModelCode?.trim();
  final modelValue = model?.isNotEmpty == true ? model : selectedModel;
  if (modelValue?.isNotEmpty == true) {
    values.add(_historyDisplayIdentifier(modelValue!));
  }
  for (final field in const ['voice', 'speed', 'emotion']) {
    final display = metadata['${field}Label']?.toString().trim();
    final raw = metadata[field]?.toString().trim() ??
        run.parameters[field]?.toString().trim();
    final value = display?.isNotEmpty == true
        ? display
        : _historyBusinessValue(field, raw);
    if (value?.isNotEmpty == true) values.add(value!);
  }
  return values.join(' · ');
}

String? _historyBusinessValue(String field, String? value) {
  if (value == null || value.isEmpty) return null;
  const labels = {
    'voice': {
      'science_female': '科普视频女声',
      'gentle_female': '温柔女声',
    },
    'speed': {
      'slow': '较慢',
      'normal': '正常',
      'fast': '较快',
      'very_fast': '快速',
    },
    'emotion': {'natural': '自然'},
  };
  return labels[field]?[value] ?? _historyDisplayIdentifier(value);
}

String _historyDisplayIdentifier(String value) => value
    .split(RegExp(r'[-_\s]+'))
    .where((part) => part.isNotEmpty)
    .map((part) => RegExp(
          r'^(?:ai|api|gpt|tts\d*|v\d+)$',
          caseSensitive: false,
        ).hasMatch(part)
            ? part.toUpperCase()
            : '${part[0].toUpperCase()}${part.substring(1)}')
    .join(' ');
String _shortDate(DateTime value) =>
    '${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}';
