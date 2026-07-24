import 'dart:async';

import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import 'task_history_page.dart';

typedef HistoryTaskLoader = Future<List<TaskView>> Function(
  String? workspaceCode,
  String keyword,
);

class HistoryPage extends StatefulWidget {
  const HistoryPage({
    super.key,
    required this.data,
    this.taskLoader,
  });

  final AppDataController data;
  final HistoryTaskLoader? taskLoader;

  @override
  State<HistoryPage> createState() => _HistoryPageState();
}

class _HistoryPageState extends State<HistoryPage> {
  final ScrollController _listController = ScrollController();
  final TextEditingController _searchController = TextEditingController();
  String? _selectedWorkspaceCode;
  String _searchQuery = '';
  List<TaskView> _tasks = const [];
  String? _error;
  bool _loading = true;
  int _requestVersion = 0;
  Timer? _searchDebounce;

  @override
  void initState() {
    super.initState();
    _tasks = widget.data.tasks;
    _searchController.addListener(_onSearchChanged);
    unawaited(_loadTasks());
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchController
      ..removeListener(_onSearchChanged)
      ..dispose();
    _listController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            const Text('任务历史'),
            const SizedBox(width: 12),
            Expanded(
              child: Align(
                alignment: Alignment.centerRight,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 220),
                  child: SizedBox(
                    height: 40,
                    child: TextField(
                      key: const ValueKey<String>('history-search-field'),
                      controller: _searchController,
                      textInputAction: TextInputAction.search,
                      maxLength: 240,
                      decoration: InputDecoration(
                        hintText: '搜索任务',
                        counterText: '',
                        isDense: true,
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 10,
                        ),
                        prefixIcon: const Icon(Icons.search_rounded, size: 18),
                        prefixIconConstraints: const BoxConstraints(
                          minWidth: 34,
                          minHeight: 34,
                        ),
                        suffixIcon: _searchController.text.isEmpty
                            ? null
                            : IconButton(
                                onPressed: _searchController.clear,
                                tooltip: '清空搜索',
                                visualDensity: VisualDensity.compact,
                                icon: const Icon(
                                  Icons.close_rounded,
                                  size: 18,
                                ),
                              ),
                        suffixIconConstraints: const BoxConstraints(
                          minWidth: 36,
                          minHeight: 36,
                        ),
                      ),
                      onSubmitted: (_) => _applySearchImmediately(),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
      body: AnimatedBuilder(
        animation: widget.data,
        builder: (context, _) {
          final categories = <_HistoryCategory>[
            const _HistoryCategory(code: null, label: '全部'),
            ...widget.data.workspaces.map(
              (workspace) => _HistoryCategory(
                code: workspace.id,
                label: workspace.title,
              ),
            ),
          ];
          return Column(
            children: [
              _HistoryCategoryTabs(
                categories: categories,
                selectedCode: _selectedWorkspaceCode,
                onSelected: _selectWorkspace,
              ),
              if (_loading) const LinearProgressIndicator(minHeight: 2),
              Expanded(child: _buildBody()),
            ],
          );
        },
      ),
    );
  }

  Widget _buildBody() {
    if (_error != null) {
      return _HistoryLoadError(
        message: _error!,
        onRetry: () => unawaited(_loadTasks()),
      );
    }
    if (_loading && _tasks.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_tasks.isEmpty) {
      return RefreshIndicator(
        onRefresh: _loadTasks,
        child: ListView(
          controller: _listController,
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            const SizedBox(height: 180),
            Center(
              child: Text(
                _searchQuery.isNotEmpty
                    ? '未找到匹配任务'
                    : _selectedWorkspaceCode == null
                        ? '还没有任务记录'
                        : '暂无该分类的任务',
                style: const TextStyle(color: AppColors.muted),
              ),
            ),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _loadTasks,
      child: ListView.separated(
        key: const PageStorageKey<String>('history-task-list'),
        controller: _listController,
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
        itemCount: _tasks.length,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, index) {
          final task = _tasks[index];
          final workspace = widget.data.workspaceForFeature(task.featureCode);
          return ListTile(
            contentPadding: const EdgeInsets.symmetric(vertical: 5),
            title: Text(
              task.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            subtitle: _TaskHistorySubtitle(
              workspaceTitle: workspace?.title ?? task.featureCode,
              promptSnippet: task.promptSnippet,
              promptKey: ValueKey<String>(
                'history-prompt-snippet-${task.id}',
              ),
            ),
            trailing: const Icon(
              Icons.chevron_right_rounded,
              color: AppColors.muted,
            ),
            onTap: () => _openTask(task),
          );
        },
      ),
    );
  }

  Future<void> _selectWorkspace(String? workspaceCode) async {
    if (_selectedWorkspaceCode == workspaceCode) return;
    _searchDebounce?.cancel();
    setState(() {
      _selectedWorkspaceCode = workspaceCode;
      _searchQuery = _searchController.text.trim();
      _tasks = const [];
      _error = null;
    });
    if (_listController.hasClients) {
      _listController.jumpTo(0);
    }
    await _loadTasks();
  }

  Future<void> _loadTasks() async {
    final requestVersion = ++_requestVersion;
    final workspaceCode = _selectedWorkspaceCode;
    final keyword = _searchQuery;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final loader = widget.taskLoader ??
          (code, query) => widget.data.api.listTasks(
                workspaceCode: code,
                keyword: query,
              );
      final tasks = await loader(workspaceCode, keyword);
      if (!mounted || requestVersion != _requestVersion) return;
      setState(() {
        _tasks = tasks;
        _loading = false;
      });
    } catch (exception) {
      if (!mounted || requestVersion != _requestVersion) return;
      setState(() {
        _loading = false;
        _error = exception.toString().replaceFirst('ApiException: ', '');
      });
    }
  }

  Future<void> _openTask(TaskView task) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) =>
            TaskHistoryPage(taskId: task.id, data: widget.data),
      ),
    );
    if (mounted) await _loadTasks();
  }

  void _onSearchChanged() {
    if (!mounted) return;
    setState(() {});
    _searchDebounce?.cancel();
    final normalized = _searchController.text.trim();
    if (normalized == _searchQuery) return;
    if (normalized.isEmpty) {
      _applySearch('');
      return;
    }
    _searchDebounce = Timer(
      const Duration(milliseconds: 300),
      () => _applySearch(normalized),
    );
  }

  void _applySearchImmediately() {
    _searchDebounce?.cancel();
    _applySearch(_searchController.text.trim());
  }

  void _applySearch(String keyword) {
    if (!mounted || keyword == _searchQuery) return;
    setState(() {
      _searchQuery = keyword;
      _tasks = const [];
      _error = null;
    });
    if (_listController.hasClients) {
      _listController.jumpTo(0);
    }
    unawaited(_loadTasks());
  }
}

class _TaskHistorySubtitle extends StatelessWidget {
  const _TaskHistorySubtitle({
    required this.workspaceTitle,
    required this.promptSnippet,
    required this.promptKey,
  });

  final String workspaceTitle;
  final String? promptSnippet;
  final Key promptKey;

  @override
  Widget build(BuildContext context) {
    final prompt = promptSnippet?.trim();
    if (prompt == null || prompt.isEmpty) {
      return Text(workspaceTitle);
    }
    return Row(
      children: [
        SizedBox(
          width: 96,
          child: Text(
            workspaceTitle,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            prompt,
            key: promptKey,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: AppColors.muted,
              fontSize: 12,
            ),
          ),
        ),
      ],
    );
  }
}

class _HistoryCategoryTabs extends StatefulWidget {
  const _HistoryCategoryTabs({
    required this.categories,
    required this.selectedCode,
    required this.onSelected,
  });

  final List<_HistoryCategory> categories;
  final String? selectedCode;
  final ValueChanged<String?> onSelected;

  @override
  State<_HistoryCategoryTabs> createState() => _HistoryCategoryTabsState();
}

class _HistoryCategoryTabsState extends State<_HistoryCategoryTabs>
    with TickerProviderStateMixin {
  late TabController _controller;

  @override
  void initState() {
    super.initState();
    _controller = _createController();
  }

  @override
  void didUpdateWidget(covariant _HistoryCategoryTabs oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.categories.length != widget.categories.length) {
      _controller.dispose();
      _controller = _createController();
      return;
    }
    final selectedIndex = _selectedIndex();
    if (_controller.index != selectedIndex) {
      _controller.animateTo(
        selectedIndex,
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeOutCubic,
      );
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: AppColors.paper,
        border: Border(bottom: BorderSide(color: AppColors.line)),
      ),
      child: TabBar(
        controller: _controller,
        isScrollable: true,
        tabAlignment: TabAlignment.start,
        padding: EdgeInsets.zero,
        labelPadding: const EdgeInsets.symmetric(horizontal: 18),
        indicatorColor: AppColors.accent,
        indicatorSize: TabBarIndicatorSize.label,
        indicatorWeight: 2.5,
        dividerColor: Colors.transparent,
        labelColor: AppColors.accent,
        unselectedLabelColor: AppColors.muted,
        labelStyle: const TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w700,
        ),
        unselectedLabelStyle: const TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.w500,
        ),
        onTap: (index) => widget.onSelected(widget.categories[index].code),
        tabs: widget.categories
            .map(
              (category) => Tab(
                key: ValueKey<String>(
                  'history-category-${category.code ?? 'all'}',
                ),
                height: 48,
                text: category.label,
              ),
            )
            .toList(),
      ),
    );
  }

  TabController _createController() => TabController(
        length: widget.categories.length,
        initialIndex: _selectedIndex(),
        animationDuration: const Duration(milliseconds: 200),
        vsync: this,
      );

  int _selectedIndex() {
    final index = widget.categories.indexWhere(
      (category) => category.code == widget.selectedCode,
    );
    return index < 0 ? 0 : index;
  }
}

class _HistoryLoadError extends StatelessWidget {
  const _HistoryLoadError({
    required this.message,
    required this.onRetry,
  });

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.cloud_off_outlined,
              color: AppColors.muted,
              size: 30,
            ),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: onRetry,
              child: const Text('重新加载'),
            ),
          ],
        ),
      ),
    );
  }
}

class _HistoryCategory {
  const _HistoryCategory({
    required this.code,
    required this.label,
  });

  final String? code;
  final String label;
}
