import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/app_icons.dart';
import '../widgets/brand_header.dart';
import '../widgets/section_header.dart';
import '../widgets/task_sheet.dart';
import 'task_history_page.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.data, required this.onOpenFeatures});

  final AppDataController data;
  final VoidCallback onOpenFeatures;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _intentController = TextEditingController();

  @override
  void dispose() {
    _intentController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      bottom: false,
      child: AnimatedBuilder(
        animation: widget.data,
        builder: (context, _) => RefreshIndicator(
          onRefresh: widget.data.refresh,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
            children: [
              BrandHeader(
                title: '元作 AI',
                subtitle: '任务与成果工作台',
                actionIcon: Icons.refresh_rounded,
                actionTooltip: '刷新',
                onAction: widget.data.refresh,
              ),
              if (widget.data.loading) ...[
                const SizedBox(height: 18),
                const LinearProgressIndicator(minHeight: 2),
              ],
              if (widget.data.error != null) ...[
                const SizedBox(height: 18),
                _ErrorBand(
                    message: widget.data.error!, onRetry: widget.data.refresh),
              ],
              const SizedBox(height: 28),
              Text.rich(
                TextSpan(
                  text: '今天想完成',
                  style: Theme.of(context).textTheme.headlineLarge,
                  children: const [
                    TextSpan(
                        text: '什么？', style: TextStyle(color: AppColors.accent)),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              const Text('输入主题快速起草，生成结果会自动保存到任务历史。'),
              const SizedBox(height: 22),
              _IntentComposer(
                controller: _intentController,
                enabled: widget.data.featureByCode('writing.draft') != null,
                onAttach: _uploadAttachment,
                onSubmit: _submitIntent,
              ),
              const SizedBox(height: 32),
              SectionHeader(
                title: '可用工作台',
                actionLabel: '查看全部',
                onAction: widget.onOpenFeatures,
              ),
              const SizedBox(height: 10),
              _QuickWorkspaceGrid(
                workspaces: widget.data.workspaces
                    .where((item) => item.entries.isNotEmpty)
                    .take(4)
                    .toList(),
                onSelected: _openWorkspace,
              ),
              const SizedBox(height: 32),
              SectionHeader(
                title: '最近任务',
                actionLabel: '刷新',
                onAction: widget.data.refresh,
              ),
              const SizedBox(height: 6),
              if (widget.data.tasks.isEmpty && !widget.data.loading)
                const _EmptyBand(text: '还没有任务，先从上方创建第一项成果。')
              else
                ...widget.data.tasks.take(6).map(
                      (task) => _RecentTaskRow(
                        task: task,
                        workspace:
                            widget.data.workspaceForFeature(task.featureCode),
                        onTap: () => _openTask(task),
                      ),
                    ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _submitIntent() async {
    final intent = _intentController.text.trim();
    if (intent.isEmpty) {
      _showMessage('先填写写作主题');
      return;
    }
    final workspace = widget.data.workspaceForFeature('writing.draft');
    final entry = widget.data.featureByCode('writing.draft');
    if (workspace == null || entry == null) {
      _showMessage('后端当前没有发布快速起草功能');
      return;
    }
    await showTaskSheet(
      context,
      data: widget.data,
      request: TaskLaunchRequest(
        workspace: workspace,
        entry: entry,
        initialParameters: {'topic': intent},
      ),
    );
    _intentController.clear();
  }

  Future<void> _uploadAttachment() async {
    try {
      final asset = await widget.data.pickAndUpload();
      if (asset != null) _showMessage('已上传到附件库：${asset.name}');
    } catch (exception) {
      _showMessage(exception.toString().replaceFirst('ApiException: ', ''));
    }
  }

  void _openWorkspace(WorkspaceDefinition workspace) {
    if (workspace.entries.isEmpty) return;
    showTaskSheet(
      context,
      data: widget.data,
      request: TaskLaunchRequest(
          workspace: workspace, entry: workspace.entries.first),
    );
  }

  void _openTask(TaskView task) {
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (context) => TaskHistoryPage(taskId: task.id, data: widget.data),
    ));
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }
}

class _IntentComposer extends StatelessWidget {
  const _IntentComposer({
    required this.controller,
    required this.enabled,
    required this.onAttach,
    required this.onSubmit,
  });

  final TextEditingController controller;
  final bool enabled;
  final VoidCallback onAttach;
  final VoidCallback onSubmit;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(15, 15, 15, 14),
      decoration: BoxDecoration(
        color: const Color(0xFFFBFCFB),
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        children: [
          TextField(
            controller: controller,
            enabled: enabled,
            minLines: 1,
            maxLines: 4,
            onSubmitted: (_) => enabled ? onSubmit() : null,
            decoration: InputDecoration.collapsed(
              hintText: enabled ? '例如：整理本周产品工作并生成周报' : '后端暂无可用的快速功能',
            ),
          ),
          const SizedBox(height: 24),
          Row(
            children: [
              IconButton(
                onPressed: onAttach,
                tooltip: '上传到附件库',
                icon: const Icon(Icons.attach_file_rounded, size: 20),
              ),
              const Spacer(),
              IconButton.filled(
                onPressed: enabled ? onSubmit : null,
                tooltip: '开始任务',
                style: IconButton.styleFrom(
                  fixedSize: const Size(38, 38),
                  backgroundColor: AppColors.accent,
                  foregroundColor: Colors.white,
                  shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8)),
                ),
                icon: const Icon(Icons.arrow_forward_rounded, size: 20),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _QuickWorkspaceGrid extends StatelessWidget {
  const _QuickWorkspaceGrid(
      {required this.workspaces, required this.onSelected});

  final List<WorkspaceDefinition> workspaces;
  final ValueChanged<WorkspaceDefinition> onSelected;

  @override
  Widget build(BuildContext context) {
    if (workspaces.isEmpty) return const _EmptyBand(text: '后端目录暂时没有已发布功能。');
    return LayoutBuilder(builder: (context, constraints) {
      final columns =
          constraints.maxWidth < 330 ? 2 : workspaces.length.clamp(1, 4);
      return GridView.builder(
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        itemCount: workspaces.length,
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: columns,
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
          childAspectRatio: columns >= 3 ? .9 : 1.7,
        ),
        itemBuilder: (context, index) {
          final workspace = workspaces[index];
          return Material(
            color: AppColors.wash,
            borderRadius: BorderRadius.circular(8),
            child: InkWell(
              onTap: () => onSelected(workspace),
              borderRadius: BorderRadius.circular(8),
              child: Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 8, vertical: 11),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(AppIcons.resolve(workspace.iconKey),
                        color: AppColors.accent, size: 23),
                    const SizedBox(height: 9),
                    Text(
                      workspace.title,
                      maxLines: 2,
                      textAlign: TextAlign.center,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 12, fontWeight: FontWeight.w700),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      );
    });
  }
}

class _RecentTaskRow extends StatelessWidget {
  const _RecentTaskRow(
      {required this.task, required this.workspace, required this.onTap});

  final TaskView task;
  final WorkspaceDefinition? workspace;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 14),
          decoration: const BoxDecoration(
              border: Border(bottom: BorderSide(color: AppColors.line))),
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                    color: AppColors.accentSoft,
                    borderRadius: BorderRadius.circular(8)),
                child: Icon(AppIcons.resolve(workspace?.iconKey ?? 'edit'),
                    color: AppColors.accent, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(task.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 14, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 4),
                    Text(
                        '${workspace?.title ?? task.featureCode} · ${_relativeTime(task.updatedAt)}',
                        style: const TextStyle(
                            color: AppColors.muted, fontSize: 11)),
                  ],
                ),
              ),
              Text(task.currentArtifactId == null ? '处理中' : '有结果',
                  style: const TextStyle(
                      color: AppColors.accent,
                      fontSize: 11,
                      fontWeight: FontWeight.w700)),
            ],
          ),
        ),
      ),
    );
  }
}

class _EmptyBand extends StatelessWidget {
  const _EmptyBand({required this.text});
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 20),
        child: Text(text, style: const TextStyle(color: AppColors.muted)),
      );
}

class _ErrorBand extends StatelessWidget {
  const _ErrorBand({required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) => Row(
        children: [
          const Icon(Icons.error_outline_rounded,
              color: Color(0xFFB33A32), size: 18),
          const SizedBox(width: 8),
          Expanded(child: Text(message, style: const TextStyle(fontSize: 12))),
          TextButton(onPressed: onRetry, child: const Text('重试')),
        ],
      );
}

String _relativeTime(DateTime value) {
  final difference = DateTime.now().difference(value);
  if (difference.inMinutes < 1) return '刚刚';
  if (difference.inHours < 1) return '${difference.inMinutes} 分钟前';
  if (difference.inDays < 1) return '${difference.inHours} 小时前';
  if (difference.inDays < 30) return '${difference.inDays} 天前';
  return '${value.year}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';
}
