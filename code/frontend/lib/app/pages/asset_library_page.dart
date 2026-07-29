import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/feature_models.dart';
import '../network/api_exception.dart';
import '../network/backend_api.dart';
import '../network/native_file_picker.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import 'asset_preview_page.dart';

const int assetDownloadMaxCount = 30;
const int assetDownloadMaxTotalBytes = 5 * 1024 * 1024 * 1024;

Set<String> invertAssetSelection(
  Set<String> allAssetIds,
  Set<String> selectedAssetIds,
) {
  return allAssetIds.difference(Set<String>.of(selectedAssetIds));
}

String? validateAssetDownloadSelection(Iterable<AssetView> assets) {
  final selected = assets.toList();
  if (selected.isEmpty) return '请至少选择一个资产';
  if (selected.length > assetDownloadMaxCount) {
    return '单次最多下载 $assetDownloadMaxCount 个资产';
  }
  if (selected.any((asset) => !asset.available)) {
    return '所选资产中包含已不可用的文件';
  }
  final totalBytes =
      selected.fold<int>(0, (total, asset) => total + asset.sizeBytes);
  if (totalBytes > assetDownloadMaxTotalBytes) {
    return '所选资产总大小不能超过 5GB';
  }
  return null;
}

typedef AssetLibraryPageLoader = Future<AssetPage> Function({
  required String libraryType,
  required String category,
  required String query,
  String? cursor,
  int pageSize,
});

typedef AssetTemporaryDownloader = Future<File> Function(
  AssetView asset,
  bool Function() isCancelled,
);

typedef AssetDirectoryPicker = Future<String?> Function();

typedef AssetDirectorySaver = Future<SavedLocalFile> Function({
  required String directoryUri,
  required File source,
  required AssetView asset,
});

typedef AssetFileSaver = Future<SavedLocalFile?> Function({
  required File source,
  required AssetView asset,
});

typedef AssetTemporaryFileCleaner = Future<void> Function(File file);
typedef AssetTemporaryFileSizeReader = Future<int> Function(File file);

class AssetLibraryPage extends StatefulWidget {
  const AssetLibraryPage({
    super.key,
    required this.data,
    this.assetLoader,
    this.temporaryDownloader,
    this.directoryPicker,
    this.directorySaver,
    this.fileSaver,
    this.temporaryFileCleaner,
    this.temporaryFileSizeReader,
  });

  final AppDataController data;
  final AssetLibraryPageLoader? assetLoader;
  final AssetTemporaryDownloader? temporaryDownloader;
  final AssetDirectoryPicker? directoryPicker;
  final AssetDirectorySaver? directorySaver;
  final AssetFileSaver? fileSaver;
  final AssetTemporaryFileCleaner? temporaryFileCleaner;
  final AssetTemporaryFileSizeReader? temporaryFileSizeReader;

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
  final Map<String, AssetView> _selectedAssets = {};
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
  bool _downloading = false;
  bool _downloadCancellationRequested = false;
  int _downloadProcessed = 0;
  int _downloadTotal = 0;

  bool get _selectionMode => _selectedAssets.isNotEmpty;
  bool get _selectionBusy => _selectionLoading || _downloading;

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
        if (didPop) return;
        if (_downloading) {
          _cancelDownload();
        } else {
          setState(_selectedAssets.clear);
        }
      },
      child: Scaffold(
        appBar: AppBar(
          leading: _selectionMode
              ? IconButton(
                  onPressed: _downloading
                      ? _cancelDownload
                      : () => setState(_selectedAssets.clear),
                  tooltip: _downloading ? '取消下载' : '退出选择',
                  icon: const Icon(Icons.close_rounded),
                )
              : null,
          title: Text(
            _selectionMode ? '已选择 ${_selectedAssets.length} 项' : '附件库',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          actions: [
            if (_selectionMode &&
                _libraryType == 'MODEL_ASSET' &&
                !_downloading)
              PopupMenuButton<String>(
                key: const ValueKey<String>('asset-selection-more'),
                tooltip: '更多操作',
                onSelected: (value) {
                  if (value == 'delete') unawaited(_deleteSelected());
                },
                itemBuilder: (context) => [
                  const PopupMenuItem<String>(
                    value: 'delete',
                    child: Row(
                      children: [
                        Icon(
                          Icons.delete_outline_rounded,
                          color: AppColors.danger,
                        ),
                        SizedBox(width: 10),
                        Text(
                          '永久删除所选资产',
                          style: TextStyle(color: AppColors.danger),
                        ),
                      ],
                    ),
                  ),
                ],
                icon: const Icon(Icons.more_vert_rounded),
              ),
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
                  child: _downloading
                      ? _buildDownloadProgress()
                      : _buildSelectionActions(),
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
            onSelectionChanged: _selectionBusy
                ? null
                : (selection) {
                    setState(() {
                      _libraryType = selection.first;
                      _selectedAssets.clear();
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
              enabled: !_selectionBusy,
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
                    onSelected: _selectionBusy
                        ? null
                        : (_) {
                            setState(() {
                              _category = item.$1;
                              _selectedAssets.clear();
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
            selected: _selectedAssets.containsKey(asset.id),
            selectionMode: _selectionMode,
            contentUrl: widget.data.api.assetContentUrl(asset.id),
            onTap: () => _tapAsset(asset),
            onLongPress: _downloading ? null : () => _toggleSelection(asset),
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
      final page = await _listAssetLibrary(
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
    if (_downloading) return;
    if (_selectionMode) {
      _toggleSelection(asset);
      return;
    }
    Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (context) =>
          AssetPreviewPage(api: widget.data.api, asset: asset),
    ));
  }

  void _toggleSelection(AssetView asset) {
    setState(() {
      if (_selectedAssets.remove(asset.id) == null) {
        if (_selectedAssets.length < _maxSelectionSize) {
          _selectedAssets[asset.id] = asset;
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
      final allAssets = <String, AssetView>{};
      String? cursor;
      do {
        final page = await _listAssetLibrary(
          libraryType: libraryType,
          category: category,
          query: query,
          cursor: cursor,
          pageSize: 50,
        );
        for (final asset in page.items) {
          allAssets[asset.id] = asset;
        }
        if (allAssets.length > _maxSelectionSize) {
          throw const ApiException(
            '当前筛选结果超过 1000 个文件，请缩小分类或搜索范围后再全选',
          );
        }
        cursor = page.nextCursor;
      } while (cursor != null);
      if (!mounted || scope != _selectionScope) return;
      setState(() {
        final previousSelection = Set<String>.of(_selectedAssets.keys);
        final replacementIds = invert
            ? invertAssetSelection(allAssets.keys.toSet(), previousSelection)
            : allAssets.keys.toSet();
        _selectedAssets
          ..clear()
          ..addEntries(replacementIds.map(
            (id) => MapEntry<String, AssetView>(id, allAssets[id]!),
          ));
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

  Future<AssetPage> _listAssetLibrary({
    required String libraryType,
    required String category,
    required String query,
    String? cursor,
    int pageSize = 20,
  }) {
    final loader = widget.assetLoader;
    if (loader != null) {
      return loader(
        libraryType: libraryType,
        category: category,
        query: query,
        cursor: cursor,
        pageSize: pageSize,
      );
    }
    return widget.data.api.listAssetLibrary(
      libraryType: libraryType,
      category: category,
      query: query,
      cursor: cursor,
      pageSize: pageSize,
    );
  }

  Widget _buildSelectionActions() {
    final modelAssets = _libraryType == 'MODEL_ASSET';
    return SizedBox(
      height: 48,
      child: Row(
        children: [
          Expanded(
            child: OutlinedButton.icon(
              onPressed: _selectionBusy ? null : _selectAll,
              style: _selectionActionStyle(),
              icon: const Icon(Icons.select_all_rounded, size: 19),
              label: const Text('全选'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: OutlinedButton.icon(
              onPressed: _selectionBusy ? null : _invertSelection,
              style: _selectionActionStyle(),
              icon: const Icon(Icons.compare_arrows_rounded, size: 19),
              label: const Text('反选'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            flex: 2,
            child: modelAssets
                ? FilledButton.icon(
                    key: const ValueKey<String>('asset-download-selected'),
                    onPressed: _selectionBusy ? null : _downloadSelected,
                    style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 6),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                    ),
                    icon: const Icon(Icons.download_outlined, size: 19),
                    label: Text('下载（${_selectedAssets.length}）'),
                  )
                : FilledButton.icon(
                    onPressed: _selectionBusy ? null : _deleteSelected,
                    style: FilledButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 6),
                      backgroundColor: AppColors.danger,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                    ),
                    icon: const Icon(Icons.delete_outline_rounded, size: 19),
                    label: Text('删除（${_selectedAssets.length}）'),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _buildDownloadProgress() {
    final current = _downloadTotal == 0
        ? 0
        : (_downloadProcessed + 1).clamp(1, _downloadTotal);
    final progress =
        _downloadTotal == 0 ? null : _downloadProcessed / _downloadTotal;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                _downloadCancellationRequested
                    ? '正在取消下载'
                    : '正在下载 $current/$_downloadTotal',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            ),
            TextButton(
              key: const ValueKey<String>('asset-download-cancel'),
              onPressed:
                  _downloadCancellationRequested ? null : _cancelDownload,
              child: const Text('取消'),
            ),
          ],
        ),
        LinearProgressIndicator(value: progress),
      ],
    );
  }

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

  Future<void> _downloadSelected() async {
    final selected = List<AssetView>.of(_selectedAssets.values);
    final validationError = validateAssetDownloadSelection(selected);
    if (validationError != null) {
      _showMessage(validationError);
      return;
    }

    String? directoryUri;
    try {
      directoryUri = await (widget.directoryPicker?.call() ??
          NativeFilePicker.pickDirectory());
    } catch (exception) {
      _showMessage('$exception');
      return;
    }
    if (!mounted || directoryUri == null || directoryUri.isEmpty) return;

    setState(() {
      _downloading = true;
      _downloadCancellationRequested = false;
      _downloadProcessed = 0;
      _downloadTotal = selected.length;
    });

    final successfulIds = <String>{};
    final failures = <_AssetDownloadFailure>[];
    var cancelled = false;
    var useSingleFileFallback = false;
    var fallbackUsed = false;
    for (final asset in selected) {
      if (_downloadCancellationRequested) {
        cancelled = true;
        break;
      }
      File? temporaryFile;
      try {
        temporaryFile = await _downloadAssetToTemporaryFile(asset);
        final downloadedBytes = await _temporaryFileSize(temporaryFile);
        if (downloadedBytes != asset.sizeBytes) {
          throw const ApiException('下载内容不完整，请重试');
        }
        if (_downloadCancellationRequested) {
          throw const AssetDownloadCancelledException();
        }
        SavedLocalFile? saved;
        if (!useSingleFileFallback) {
          try {
            saved = await _saveAssetToDirectory(
              directoryUri: directoryUri,
              source: temporaryFile,
              asset: asset,
            );
          } catch (exception) {
            if (_downloadCancellationRequested) {
              throw const AssetDownloadCancelledException();
            }
            useSingleFileFallback = true;
            fallbackUsed = true;
            debugPrint(
              'Asset directory save failed assetId=${asset.id} '
              'code=${_downloadErrorCode(exception)}',
            );
          }
        }
        if (useSingleFileFallback && saved == null) {
          saved = await _saveAssetWithPicker(
            source: temporaryFile,
            asset: asset,
          );
        }
        if (saved == null) {
          throw const ApiException('已取消系统保存');
        }
        if (saved.sizeBytes != downloadedBytes) {
          throw const ApiException('保存后的文件大小不一致，请重试');
        }
        successfulIds.add(asset.id);
      } on AssetDownloadCancelledException {
        cancelled = true;
      } catch (exception) {
        debugPrint(
          'Asset download failed assetId=${asset.id} '
          'code=${_downloadErrorCode(exception)}',
        );
        failures.add(
          _AssetDownloadFailure(asset, _downloadErrorMessage(exception)),
        );
      } finally {
        await _cleanupTemporaryFile(temporaryFile);
      }
      if (cancelled) break;
      if (mounted) {
        setState(() => _downloadProcessed++);
      }
      if (_downloadCancellationRequested) {
        cancelled = true;
        break;
      }
    }

    if (!mounted) return;
    final retainedCount = selected.length - successfulIds.length;
    setState(() {
      for (final assetId in successfulIds) {
        _selectedAssets.remove(assetId);
      }
      _downloading = false;
      _downloadCancellationRequested = false;
      _downloadProcessed = 0;
      _downloadTotal = 0;
    });

    if (cancelled) {
      _showMessage(
        '已保存 ${successfulIds.length} 个，下载已取消，'
        '$retainedCount 个保留待重试',
      );
    } else if (failures.isNotEmpty) {
      await _showDownloadFailures(
        successfulCount: successfulIds.length,
        failures: failures,
      );
    } else if (fallbackUsed) {
      _showMessage(
        '已保存 ${successfulIds.length} 个资产，'
        '设备目录写入不兼容，已改用系统保存',
      );
    } else {
      _showMessage('已保存 ${successfulIds.length} 个资产');
    }
  }

  Future<File> _downloadAssetToTemporaryFile(AssetView asset) {
    final downloader = widget.temporaryDownloader;
    if (downloader != null) {
      return downloader(asset, () => _downloadCancellationRequested);
    }
    return widget.data.api.downloadAssetToTemporaryFile(
      asset.id,
      fileName: asset.name,
      isCancelled: () => _downloadCancellationRequested,
    );
  }

  Future<SavedLocalFile> _saveAssetToDirectory({
    required String directoryUri,
    required File source,
    required AssetView asset,
  }) {
    final saver = widget.directorySaver;
    if (saver != null) {
      return saver(
        directoryUri: directoryUri,
        source: source,
        asset: asset,
      );
    }
    return NativeFilePicker.saveFileToDirectory(
      directoryUri: directoryUri,
      filePath: source.path,
      fileName: asset.name,
      mediaType: asset.mediaType,
    );
  }

  Future<SavedLocalFile?> _saveAssetWithPicker({
    required File source,
    required AssetView asset,
  }) {
    final saver = widget.fileSaver;
    if (saver != null) {
      return saver(source: source, asset: asset);
    }
    return NativeFilePicker.saveFileFromPath(
      filePath: source.path,
      fileName: asset.name,
      mediaType: asset.mediaType,
    );
  }

  Future<void> _showDownloadFailures({
    required int successfulCount,
    required List<_AssetDownloadFailure> failures,
  }) async {
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('下载未全部完成'),
        content: ConstrainedBox(
          constraints: const BoxConstraints(maxHeight: 360),
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  '已保存 $successfulCount 个，${failures.length} 个失败。'
                  '失败项仍保持选中，可重新下载。',
                ),
                const SizedBox(height: 14),
                ...failures.map(
                  (failure) => Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          failure.asset.name,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          failure.message,
                          style: const TextStyle(
                            color: AppColors.muted,
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
        actions: [
          FilledButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('知道了'),
          ),
        ],
      ),
    );
  }

  static String _downloadErrorCode(Object exception) {
    if (exception is PlatformException) return exception.code;
    if (exception is ApiException) return 'API_ERROR';
    if (exception is FileSystemException) return 'FILE_SYSTEM_ERROR';
    return exception.runtimeType.toString();
  }

  static String _downloadErrorMessage(Object exception) {
    if (exception is PlatformException) {
      return switch (exception.code) {
        'DIRECTORY_PERMISSION_DENIED' => '所选文件夹没有写入权限，请选择其他位置',
        'DESTINATION_UNAVAILABLE' => '目标位置不可用，请选择其他位置',
        'SOURCE_FILE_INVALID' => '下载缓存文件无效，请重新下载',
        'FILE_SAVE_CANCELLED' => '文件保存已取消',
        _ => exception.message ?? '文件写入失败，请选择其他位置',
      };
    }
    return exception
        .toString()
        .replaceFirst('ApiException: ', '')
        .replaceFirst('FileSystemException: ', '');
  }

  void _cancelDownload() {
    if (!_downloading || _downloadCancellationRequested) return;
    setState(() => _downloadCancellationRequested = true);
    if (widget.directorySaver == null) {
      unawaited(NativeFilePicker.cancelDirectorySave());
    }
  }

  Future<void> _cleanupTemporaryFile(File? file) async {
    if (file == null) return;
    final cleaner = widget.temporaryFileCleaner;
    if (cleaner != null) {
      await cleaner(file);
      return;
    }
    try {
      final directory = file.parent;
      if (await directory.exists()) {
        await directory.delete(recursive: true);
      }
    } on FileSystemException {
      // Download cache cleanup is best effort.
    }
  }

  Future<int> _temporaryFileSize(File file) {
    final reader = widget.temporaryFileSizeReader;
    return reader == null ? file.length() : reader(file);
  }

  void _showMessage(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  Future<void> _deleteSelected() async {
    try {
      final selected = List<AssetView>.of(_selectedAssets.values);
      final impact =
          await widget.data.api.getAssetDeleteImpact(_selectedAssets.keys);
      if (!mounted) return;
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => _AssetDeleteConfirmationDialog(
          selected: selected,
          impact: impact,
        ),
      );
      if (confirmed != true) return;
      await widget.data.deleteAssets(_selectedAssets.keys);
      if (!mounted) return;
      setState(_selectedAssets.clear);
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

class _AssetDownloadFailure {
  const _AssetDownloadFailure(this.asset, this.message);

  final AssetView asset;
  final String message;
}

class _AssetDeleteConfirmationDialog extends StatefulWidget {
  const _AssetDeleteConfirmationDialog({
    required this.selected,
    required this.impact,
  });

  final List<AssetView> selected;
  final AssetDeleteImpact impact;

  @override
  State<_AssetDeleteConfirmationDialog> createState() =>
      _AssetDeleteConfirmationDialogState();
}

class _AssetDeleteConfirmationDialogState
    extends State<_AssetDeleteConfirmationDialog> {
  Timer? _timer;
  int _remainingSeconds = 3;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!mounted) return;
      if (_remainingSeconds <= 1) {
        timer.cancel();
        setState(() => _remainingSeconds = 0);
      } else {
        setState(() => _remainingSeconds--);
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final names = widget.selected.take(3).map((asset) => asset.name).toList();
    final remainingNames = widget.selected.length - names.length;
    return AlertDialog(
      title: Text('永久删除 ${widget.impact.assetCount} 个资产？'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              '删除后文件内容无法恢复，任务历史只保留文件名和任务信息。',
              style: TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 12),
            ...names.map(
              (name) => Padding(
                padding: const EdgeInsets.only(bottom: 5),
                child: Text(
                  '• $name',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ),
            if (remainingNames > 0) Text('另有 $remainingNames 个资产'),
            const SizedBox(height: 10),
            Text('将释放约 ${_formatBytes(widget.impact.totalBytes)}。'),
            if (widget.impact.affectedTaskCount > 0)
              Text(
                '关联 ${widget.impact.affectedTaskCount} 个任务、'
                '${widget.impact.affectedRunCount} 次执行。',
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: _remainingSeconds == 0
              ? () => Navigator.of(context).pop(true)
              : null,
          style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
          child: Text(
            _remainingSeconds == 0 ? '永久删除' : '永久删除（$_remainingSeconds）',
          ),
        ),
      ],
    );
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
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    return Material(
      key: ValueKey<String>('asset-row-${asset.id}'),
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
                  key: ValueKey<String>('asset-selection-${asset.id}'),
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
