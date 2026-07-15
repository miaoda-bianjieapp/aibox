import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/app_icons.dart';
import '../widgets/brand_header.dart';
import '../widgets/task_sheet.dart';

class FeaturesPage extends StatefulWidget {
  const FeaturesPage({super.key, required this.data});

  final AppDataController data;

  @override
  State<FeaturesPage> createState() => _FeaturesPageState();
}

class _FeaturesPageState extends State<FeaturesPage> {
  final _searchController = TextEditingController();
  WorkspaceGroup? _selectedGroup;

  @override
  void initState() {
    super.initState();
    _searchController.addListener(_refresh);
  }

  @override
  void dispose() {
    _searchController
      ..removeListener(_refresh)
      ..dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      bottom: false,
      child: AnimatedBuilder(
        animation: widget.data,
        builder: (context, _) {
          final workspaces = widget.data.workspaces
              .where((item) =>
                  item.matches(_searchController.text, _selectedGroup))
              .toList();
          return RefreshIndicator(
            onRefresh: widget.data.refresh,
            child: CustomScrollView(
              slivers: [
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
                  sliver: SliverList.list(children: [
                    BrandHeader(
                      title: '功能',
                      actionIcon: Icons.refresh_rounded,
                      actionTooltip: '刷新后端目录',
                      onAction: widget.data.refresh,
                    ),
                    if (widget.data.loading) ...[
                      const SizedBox(height: 16),
                      const LinearProgressIndicator(minHeight: 2),
                    ],
                    const SizedBox(height: 26),
                    Text('你要交付什么？',
                        style: Theme.of(context).textTheme.headlineMedium),
                    const SizedBox(height: 6),
                    const Text('这里只展示后端已经发布并且可以执行的功能。'),
                    const SizedBox(height: 20),
                    TextField(
                      controller: _searchController,
                      decoration: InputDecoration(
                        hintText: '搜索功能或成果类型',
                        prefixIcon: const Icon(Icons.search_rounded,
                            color: AppColors.muted),
                        suffixIcon: _searchController.text.isEmpty
                            ? null
                            : IconButton(
                                onPressed: _searchController.clear,
                                tooltip: '清空',
                                icon: const Icon(Icons.close_rounded, size: 19),
                              ),
                      ),
                    ),
                    const SizedBox(height: 14),
                    SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      child: Row(children: [
                        _FilterButton(
                            label: '全部',
                            selected: _selectedGroup == null,
                            onTap: () => _selectGroup(null)),
                        const SizedBox(width: 8),
                        _FilterButton(
                            label: '创作成果',
                            selected: _selectedGroup == WorkspaceGroup.create,
                            onTap: () => _selectGroup(WorkspaceGroup.create)),
                        const SizedBox(width: 8),
                        _FilterButton(
                            label: '处理资料',
                            selected: _selectedGroup == WorkspaceGroup.process,
                            onTap: () => _selectGroup(WorkspaceGroup.process)),
                        const SizedBox(width: 8),
                        _FilterButton(
                            label: '多媒体',
                            selected: _selectedGroup == WorkspaceGroup.media,
                            onTap: () => _selectGroup(WorkspaceGroup.media)),
                      ]),
                    ),
                    const SizedBox(height: 22),
                  ]),
                ),
                if (widget.data.error != null && widget.data.workspaces.isEmpty)
                  SliverFillRemaining(
                    hasScrollBody: false,
                    child: _EmptyState(
                      icon: Icons.cloud_off_outlined,
                      title: '目录加载失败',
                      message: widget.data.error!,
                      onRetry: widget.data.refresh,
                    ),
                  )
                else if (workspaces.isEmpty)
                  const SliverFillRemaining(
                    hasScrollBody: false,
                    child: _EmptyState(
                      icon: Icons.search_off_rounded,
                      title: '没有匹配结果',
                      message: '尝试更换关键词或分类。',
                    ),
                  )
                else
                  SliverPadding(
                    padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
                    sliver: SliverList.builder(
                      itemCount: workspaces.length,
                      itemBuilder: (context, index) => _WorkspaceRow(
                        workspace: workspaces[index],
                        isFirst: index == 0,
                        onFeatureTap: (entry) =>
                            _openFeature(workspaces[index], entry),
                      ),
                    ),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }

  void _refresh() => setState(() {});
  void _selectGroup(WorkspaceGroup? group) =>
      setState(() => _selectedGroup = group);

  void _openFeature(WorkspaceDefinition workspace, FeatureEntry entry) {
    showTaskSheet(
      context,
      data: widget.data,
      request: TaskLaunchRequest(workspace: workspace, entry: entry),
    );
  }
}

class _FilterButton extends StatelessWidget {
  const _FilterButton(
      {required this.label, required this.selected, required this.onTap});
  final String label;
  final bool selected;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Material(
        color: selected ? AppColors.ink : Colors.white,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(8),
          side: BorderSide(color: selected ? AppColors.ink : AppColors.line),
        ),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(8),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 9),
            child: Text(label,
                style: TextStyle(
                    color: selected ? Colors.white : AppColors.muted,
                    fontSize: 12,
                    fontWeight: selected ? FontWeight.w700 : FontWeight.w500)),
          ),
        ),
      );
}

class _WorkspaceRow extends StatelessWidget {
  const _WorkspaceRow(
      {required this.workspace,
      required this.isFirst,
      required this.onFeatureTap});
  final WorkspaceDefinition workspace;
  final bool isFirst;
  final ValueChanged<FeatureEntry> onFeatureTap;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 18),
        decoration: BoxDecoration(
          border: Border(
            top: isFirst
                ? const BorderSide(color: AppColors.line)
                : BorderSide.none,
            bottom: const BorderSide(color: AppColors.line),
          ),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Container(
              width: 42,
              height: 42,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                  color: AppColors.wash,
                  borderRadius: BorderRadius.circular(8)),
              child: Icon(AppIcons.resolve(workspace.iconKey),
                  color: AppColors.ink, size: 22),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(workspace.title,
                        style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 4),
                    Text(workspace.description,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            color: AppColors.muted, fontSize: 11)),
                  ]),
            ),
            Text('${workspace.entries.length} 个功能',
                style: const TextStyle(color: AppColors.muted, fontSize: 11)),
          ]),
          const SizedBox(height: 13),
          Padding(
            padding: const EdgeInsets.only(left: 54),
            child: workspace.entries.isEmpty
                ? const Text('暂无已发布功能',
                    style: TextStyle(color: AppColors.muted, fontSize: 12))
                : Wrap(
                    spacing: 7,
                    runSpacing: 7,
                    children: workspace.entries
                        .map((entry) => ActionChip(
                              onPressed: () => onFeatureTap(entry),
                              label: Text(entry.title),
                              backgroundColor: AppColors.wash,
                              side: BorderSide.none,
                              shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(8)),
                            ))
                        .toList(),
                  ),
          ),
        ]),
      );
}

class _EmptyState extends StatelessWidget {
  const _EmptyState(
      {required this.icon,
      required this.title,
      required this.message,
      this.onRetry});
  final IconData icon;
  final String title;
  final String message;
  final VoidCallback? onRetry;
  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(36),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, color: AppColors.muted, size: 30),
            const SizedBox(height: 12),
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 6),
            Text(message,
                textAlign: TextAlign.center,
                style: const TextStyle(color: AppColors.muted)),
            if (onRetry != null) ...[
              const SizedBox(height: 12),
              TextButton(onPressed: onRetry, child: const Text('重新加载')),
            ],
          ]),
        ),
      );
}
