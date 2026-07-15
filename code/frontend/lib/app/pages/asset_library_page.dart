import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';

class AssetLibraryPage extends StatelessWidget {
  const AssetLibraryPage({super.key, required this.data});
  final AppDataController data;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: const Text('附件库'),
          actions: [
            IconButton(
                onPressed: () => _upload(context),
                tooltip: '上传文件',
                icon: const Icon(Icons.upload_file_outlined)),
          ],
        ),
        body: AnimatedBuilder(
          animation: data,
          builder: (context, _) => RefreshIndicator(
            onRefresh: data.refresh,
            child: data.assets.isEmpty
                ? ListView(children: [
                    const SizedBox(height: 170),
                    const Center(
                        child: Text('还没有上传附件',
                            style: TextStyle(color: AppColors.muted))),
                    Center(
                        child: TextButton.icon(
                            onPressed: () => _upload(context),
                            icon: const Icon(Icons.upload_file_outlined),
                            label: const Text('选择文件'))),
                  ])
                : ListView.separated(
                    padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                    itemCount: data.assets.length,
                    separatorBuilder: (_, __) => const Divider(height: 1),
                    itemBuilder: (context, index) => _AssetRow(
                      asset: data.assets[index],
                      onDelete: () => _delete(context, data.assets[index]),
                    ),
                  ),
          ),
        ),
      );

  Future<void> _upload(BuildContext context) async {
    try {
      await data.pickAndUpload();
    } catch (exception) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$exception')));
      }
    }
  }

  Future<void> _delete(BuildContext context, AssetView asset) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除附件'),
        content: Text('确认删除“${asset.name}”？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('删除')),
        ],
      ),
    );
    if (confirmed == true) await data.deleteAsset(asset.id);
  }
}

class _AssetRow extends StatelessWidget {
  const _AssetRow({required this.asset, required this.onDelete});
  final AssetView asset;
  final VoidCallback onDelete;
  @override
  Widget build(BuildContext context) => ListTile(
        contentPadding: EdgeInsets.zero,
        leading: Icon(_iconFor(asset.mediaType), color: AppColors.accent),
        title: Text(asset.name, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text('${_size(asset.sizeBytes)} · ${asset.mediaType}'),
        trailing: IconButton(
            onPressed: onDelete,
            tooltip: '删除',
            icon: const Icon(Icons.delete_outline_rounded)),
      );
}

IconData _iconFor(String type) {
  if (type.startsWith('image/')) return Icons.image_outlined;
  if (type.startsWith('audio/')) return Icons.audio_file_outlined;
  if (type.startsWith('video/')) return Icons.video_file_outlined;
  return Icons.insert_drive_file_outlined;
}

String _size(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
}
