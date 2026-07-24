import 'dart:async';

import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../network/api_exception.dart';
import '../network/native_file_picker.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import 'asset_preview_page.dart';

class AssetLibraryPage extends StatefulWidget {
  const AssetLibraryPage({super.key, required this.data});

  final AppDataController data;

  @override
  State<AssetLibraryPage> createState() => _AssetLibraryPageState();
}

class _AssetLibraryPageState extends State<AssetLibraryPage> {
  static const int _maxSelectionSize = 1000;
  static const _categories = <(String, String)>[
    ('ALL', '全部'),
    ('IMAGE', '图片'),
    ('VIDEO', '视频'),
    ('AUDIO', '音频'),
    ('DOCUMENT', '文档'),
    ('OTHER', '其他'),
  ];

  final TextEditingController _searchController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  final Set<String> _selectedIds = {};
  Timer? _searchDebounce;
  List<AssetView> _items = const [];
  String _libraryType = 'USER_FILE';
  String _category = 'ALL';
  String? _nextCursor;
  String? _error;
  bool _loading = true;
  bool _loadingMore = false;
  bool _uploading = false;
  bool _searchVisible = false;
  bool _selectionLoading = false;

  bool get _selectionMode => _selectedIds.isNotEmpty;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_handleScroll);
    unawaited(_load());
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchController.dispose();
    _scrollController
      ..removeListener(_handleScroll)
      ..dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_selectionMode,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) setState(_selectedIds.clear);
      },
      child: Scaffold(
        appBar: AppBar(
          leading: _selectionMode
              ? IconButton(
                  onPressed: () => setState(_selectedIds.clear),
                  tooltip: '退出选择',
                  icon: const Icon(Icons.close_rounded),
                )
              : null,
          title: Text(_selectionMode ? '已选择 ${_selectedIds.length} 项' : '附件库'),
          actions: [
            if (!_selectionMode)
              IconButton(
                onPressed: () => setState(() {
                  _searchVisible = !_searchVisible;
                  if (!_searchVisible && _searchController.text.isNotEmpty) {
                    _searchController.clear();
                    unawaited(_load());
                  }
                }),
                tooltip: '搜索',
                icon: Icon(
                  _searchVisible
                      ? Icons.search_off_rounded
                      : Icons.search_rounded,
                ),
              ),
            if (!_selectionMode && _libraryType == 'USER_FILE')
              IconButton(
                onPressed: _uploading ? null : _upload,
                tooltip: '上传文件',
                icon: const Icon(Icons.upload_file_outlined),
              ),
          ],
        ),
        body: SafeArea(
          child: Column(
            children: [
              _buildControls(),
              const Divider(height: 1),
              Expanded(child: _buildBody()),
            ],
          ),
        ),
        bottomNavigationBar: _selectionMode
            ? SafeArea(
                top: false,
                child: Container(
                  padding: const EdgeInsets.fromLTRB(20, 10, 20, 12),
                  decoration: const BoxDecoration(
                    color: AppColors.paper,
                    border: Border(top: BorderSide(color: AppColors.line)),
                  ),
                  child: SizedBox(
                    height: 48,
                    child: Row(
                      children: [
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: _selectionLoading ? null : _selectAll,
                            style: _selectionActionStyle(),
                            icon:
                                const Icon(Icons.select_all_rounded, size: 19),
                            label: const Text('全选'),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed:
                                _selectionLoading ? null : _invertSelection,
                            style: _selectionActionStyle(),
                            icon: const Icon(Icons.compare_arrows_rounded,
                                size: 19),
                            label: const Text('反选'),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          flex: 2,
                          child: FilledButton.icon(
                            onPressed:
                                _selectionLoading ? null : _deleteSelected,
                            style: FilledButton.styleFrom(
                              padding:
                                  const EdgeInsets.symmetric(horizontal: 6),
                              backgroundColor: AppColors.danger,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(8),
                              ),
                            ),
                            icon: const Icon(Icons.delete_outline_rounded,
                                size: 19),
                            label: Text('删除（${_selectedIds.length}）'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              )
            : null,
      ),
    );
  }

  Widget _buildControls() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(
                value: 'USER_FILE',
                icon: Icon(Icons.folder_outlined, size: 18),
                label: Text('我的文件'),
              ),
              ButtonSegment(
                value: 'MODEL_ASSET',
                icon: Icon(Icons.auto_awesome_outlined, size: 18),
                label: Text('我的资产'),
              ),
            ],
            selected: {_libraryType},
            showSelectedIcon: false,
            onSelectionChanged: (selection) {
              setState(() {
                _libraryType = selection.first;
                _selectedIds.clear();
              });
              unawaited(_load());
            },
            style: ButtonStyle(
              shape: WidgetStatePropertyAll(
                RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              ),
            ),
          ),
          if (_searchVisible) ...[
            const SizedBox(height: 12),
            TextField(
              controller: _searchController,
              autofocus: true,
              textInputAction: TextInputAction.search,
              onChanged: _searchChanged,
              decoration: InputDecoration(
                hintText: '搜索文件名或关联任务',
                prefixIcon: const Icon(Icons.search_rounded),
                suffixIcon: _searchController.text.isEmpty
                    ? null
                    : IconButton(
                        onPressed: () {
                          _searchController.clear();
                          unawaited(_load());
                          setState(() {});
                        },
                        tooltip: '清空',
                        icon: const Icon(Icons.close_rounded),
                      ),
              ),
            ),
          ],
          const SizedBox(height: 12),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: _categories.map((item) {
                final selected = item.$1 == _category;
                return Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(
                    label: Text(item.$2),
                    selected: selected,
                    showCheckmark: false,
                    onSelected: (_) {
                      setState(() {
                        _category = item.$1;
                        _selectedIds.clear();
                      });
                      unawaited(_load());
                    },
                  ),
                );
              }).toList(),
            ),
          ),
          if (_uploading) ...[
            const SizedBox(height: 12),
            const LinearProgressIndicator(minHeight: 2),
            const SizedBox(height: 6),
            const Text(
              '正在上传文件',
              style: TextStyle(color: AppColors.muted, fontSize: 11),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null && _items.isEmpty) {
      return _EmptyState(
        icon: Icons.cloud_off_outlined,
        title: '附件加载失败',
        message: _error!,
        actionLabel: '重新加载',
        onAction: _load,
      );
    }
    if (_items.isEmpty) {
      final searching = _searchController.text.trim().isNotEmpty;
      return _EmptyState(
        icon: searching
            ? Icons.search_off_rounded
            : _libraryType == 'USER_FILE'
                ? Icons.folder_open_rounded
                : Icons.auto_awesome_outlined,
        title: searching
            ? '没有匹配结果'
            : _libraryType == 'USER_FILE'
                ? '还没有文件'
                : '还没有生成资产',
        message: searching
            ? '尝试更换文件名、任务名或文件类型。'
            : _libraryType == 'USER_FILE'
                ? '上传的文件会显示在这里。'
                : '模型生成的图片、音频、视频和文件会自动保存到这里。',
        actionLabel: !searching && _libraryType == 'USER_FILE' ? '上传文件' : null,
        onAction: !searching && _libraryType == 'USER_FILE' ? _upload : null,
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        controller: _scrollController,
        padding: const EdgeInsets.only(bottom: 28),
        itemCount: _items.length + (_loadingMore ? 1 : 0),
        separatorBuilder: (_, __) => const Padding(
          padding: EdgeInsets.symmetric(horizontal: 20),
          child: Divider(height: 1),
        ),
        itemBuilder: (context, index) {
          if (index == _items.length) {
            return const Padding(
              padding: EdgeInsets.all(18),
              child: Center(child: CircularProgressIndicator()),
            );
          }
          final asset = _items[index];
          return _AssetRow(
            asset: asset,
            selected: _selectedIds.contains(asset.id),
            selectionMode: _selectionMode,
            contentUrl: widget.data.api.assetContentUrl(asset.id),
            onTap: () => _tapAsset(asset),
            onLongPress: () => _toggleSelection(asset.id),
          );
        },
      ),
    );
  }

  Future<void> _load({bool append = false}) async {
    if (append) {
      if (_loadingMore || _nextCursor == null) return;
      setState(() => _loadingMore = true);
    } else {
      setState(() {
        _loading = true;
        _error = null;
        _nextCursor = null;
      });
    }
    try {
      final page = await widget.data.api.listAssetLibrary(
        libraryType: _libraryType,
        category: _category,
        query: _searchController.text.trim(),
        cursor: append ? _nextCursor : null,
      );
      if (!mounted) return;
      setState(() {
        _items = append ? [..._items, ...page.items] : page.items;
        _nextCursor = page.nextCursor;
        _loading = false;
        _loadingMore = false;
      });
    } catch (exception) {
      if (!mounted) return;
      setState(() {
        _error = exception.toString().replaceFirst('ApiException: ', '');
        _loading = false;
        _loadingMore = false;
      });
    }
  }

  void _handleScroll() {
    if (!_scrollController.hasClients || _nextCursor == null) return;
    if (_scrollController.position.extentAfter < 280) {
      unawaited(_load(append: true));
    }
  }

  void _searchChanged(String value) {
    _searchDebounce?.cancel();
    setState(() {});
    _searchDebounce = Timer(
      const Duration(milliseconds: 350),
      () => unawaited(_load()),
    );
  }

  void _tapAsset(AssetView asset) {
    if (_selectionMode) {
      _toggleSelection(asset.id);
      return;
    }
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (context) =>
          AssetPreviewPage(api: widget.data.api, asset: asset),
    ));
  }

  void _toggleSelection(String assetId) {
    setState(() {
      if (!_selectedIds.remove(assetId)) {
        if (_selectedIds.length < _maxSelectionSize) {
          _selectedIds.add(assetId);
        }
      }
    });
  }

  Future<void> _selectAll() => _replaceSelectionFromFullFilter(invert: false);

  Future<void> _invertSelection() =>
      _replaceSelectionFromFullFilter(invert: true);

  Future<void> _replaceSelectionFromFullFilter({
    required bool invert,
  }) async {
    final libraryType = _libraryType;
    final category = _category;
    final query = _searchController.text.trim();
    final scope = '$libraryType|$category|$query';
    setState(() => _selectionLoading = true);
    try {
      final allIds = <String>{};
      String? cursor;
      do {
        final page = await widget.data.api.listAssetLibrary(
          libraryType: libraryType,
          category: category,
          query: query,
          cursor: cursor,
          pageSize: 50,
        );
        allIds.addAll(page.items.map((asset) => asset.id));
        if (allIds.length > _maxSelectionSize) {
          throw const ApiException(
            '当前筛选结果超过 1000 个文件，请缩小分类或搜索范围后再全选',
          );
        }
        cursor = page.nextCursor;
      } while (cursor != null);
      if (!mounted || scope != _selectionScope) return;
      setState(() {
        final replacement =
            invert ? allIds.where((id) => !_selectedIds.contains(id)) : allIds;
        _selectedIds
          ..clear()
          ..addAll(replacement);
      });
    } catch (exception) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$exception')),
        );
      }
    } finally {
      if (mounted) setState(() => _selectionLoading = false);
    }
  }

  String get _selectionScope =>
      '$_libraryType|$_category|${_searchController.text.trim()}';

  static ButtonStyle _selectionActionStyle() {
    return OutlinedButton.styleFrom(
      padding: const EdgeInsets.symmetric(horizontal: 6),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(8),
      ),
    );
  }

  Future<void> _upload() async {
    var files = <PickedLocalFile>[];
    try {
      files = await NativeFilePicker.pickMultiple(maxFiles: 5);
      if (files.isEmpty) return;
      final totalBytes =
          files.fold<int>(0, (total, file) => total + file.sizeBytes);
      if (totalBytes > 1024 * 1024 * 1024) {
        throw const ApiException('单次上传文件总大小不能超过 1GB');
      }
      setState(() => _uploading = true);
      for (final file in files) {
        await widget.data.api.uploadAsset(file);
      }
      await widget.data.refresh();
      await _load();
    } catch (exception) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$exception')),
        );
      }
    } finally {
      for (final file in files) {
        await file.cleanup();
      }
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _deleteSelected() async {
    try {
      final impact = await widget.data.api.getAssetDeleteImpact(_selectedIds);
      if (!mounted) return;
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text('删除 ${impact.assetCount} 个文件？'),
          content: Text(
            '将释放约 ${_formatBytes(impact.totalBytes)} 存储空间。'
            '${impact.affectedTaskCount > 0 ? '\n这些文件关联 ${impact.affectedTaskCount} 个任务、${impact.affectedRunCount} 次执行。' : ''}'
            '\n\n删除后无法恢复，任务历史仍会保留文件名、大小和原提示词，并显示“原文件已删除”。',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.danger,
              ),
              child: const Text('确认删除'),
            ),
          ],
        ),
      );
      if (confirmed != true) return;
      await widget.data.deleteAssets(_selectedIds);
      if (!mounted) return;
      setState(_selectedIds.clear);
      await _load();
    } catch (exception) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$exception')),
        );
      }
    }
  }
}

class _AssetRow extends StatelessWidget {
  const _AssetRow({
    required this.asset,
    required this.selected,
    required this.selectionMode,
    required this.contentUrl,
    required this.onTap,
    required this.onLongPress,
  });

  final AssetView asset;
  final bool selected;
  final bool selectionMode;
  final String contentUrl;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? AppColors.accentSoft : Colors.transparent,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 20),
          child: Row(
            children: [
              if (selectionMode) ...[
                Icon(
                  selected
                      ? Icons.check_circle_rounded
                      : Icons.radio_button_unchecked_rounded,
                  color: selected ? AppColors.accent : AppColors.muted,
                ),
                const SizedBox(width: 10),
              ],
              _AssetThumbnail(asset: asset, contentUrl: contentUrl),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      asset.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Text(
                      '${_shortDate(asset.createdAt)}  ${_formatBytes(asset.sizeBytes)}',
                      style: const TextStyle(
                        color: AppColors.muted,
                        fontSize: 11,
                      ),
                    ),
                    if (asset.associatedTaskCount > 0) ...[
                      const SizedBox(height: 3),
                      Text(
                        asset.latestTaskTitle == null
                            ? '关联 ${asset.associatedTaskCount} 个任务'
                            : '${asset.latestTaskTitle} · ${asset.associatedTaskCount} 个任务',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: AppColors.accent,
                          fontSize: 11,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              if (!selectionMode)
                const Icon(
                  Icons.visibility_outlined,
                  color: AppColors.muted,
                  size: 21,
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AssetThumbnail extends StatelessWidget {
  const _AssetThumbnail({required this.asset, required this.contentUrl});

  final AssetView asset;
  final String contentUrl;

  @override
  Widget build(BuildContext context) {
    if (asset.isImage) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(6),
        child: Image.network(
          contentUrl,
          width: 52,
          height: 52,
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => _fallback(),
        ),
      );
    }
    return _fallback();
  }

  Widget _fallback() {
    final (icon, color) = switch (asset.category) {
      'VIDEO' => (Icons.video_file_outlined, const Color(0xFF3667A6)),
      'AUDIO' => (Icons.audio_file_outlined, const Color(0xFF8A5A00)),
      'DOCUMENT' => (Icons.description_outlined, AppColors.accent),
      _ => (Icons.insert_drive_file_outlined, AppColors.muted),
    };
    return Container(
      width: 52,
      height: 52,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: AppColors.wash,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Icon(icon, color: color, size: 25),
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({
    required this.icon,
    required this.title,
    required this.message,
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return ListView(
      children: [
        const SizedBox(height: 140),
        Icon(icon, color: AppColors.muted, size: 38),
        const SizedBox(height: 14),
        Text(
          title,
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 7),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 36),
          child: Text(
            message,
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppColors.muted),
          ),
        ),
        if (actionLabel != null && onAction != null) ...[
          const SizedBox(height: 14),
          Center(
            child: TextButton.icon(
              onPressed: onAction,
              icon: const Icon(Icons.upload_file_outlined),
              label: Text(actionLabel!),
            ),
          ),
        ],
      ],
    );
  }
}

String _formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  if (bytes < 1024 * 1024 * 1024) {
    return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
  }
  return '${(bytes / 1024 / 1024 / 1024).toStringAsFixed(2)} GB';
}

String _shortDate(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-'
    '${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:'
    '${value.minute.toString().padLeft(2, '0')}';
