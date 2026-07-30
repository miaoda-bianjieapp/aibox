import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../theme/app_theme.dart';

class ImageAssetPickerView extends StatelessWidget {
  const ImageAssetPickerView({
    super.key,
    required this.assets,
    required this.maxItems,
    required this.uploading,
    required this.enabled,
    required this.contentUrlFor,
    required this.onPickImages,
    required this.onChooseLibrary,
    required this.onRemove,
    this.disabledReason,
  });

  final List<AssetView> assets;
  final int maxItems;
  final bool uploading;
  final bool enabled;
  final String Function(AssetView asset) contentUrlFor;
  final VoidCallback? onPickImages;
  final VoidCallback? onChooseLibrary;
  final ValueChanged<AssetView> onRemove;
  final String? disabledReason;

  bool get _single => maxItems == 1;
  bool get _atLimit => !_single && assets.length >= maxItems;
  bool get _canPick => enabled && !uploading && !_atLimit;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (assets.isEmpty)
          _EmptyImageSelection(maxItems: maxItems)
        else if (_single)
          _SingleImagePreview(
            asset: assets.first,
            contentUrl: contentUrlFor(assets.first),
            onRemove:
                enabled && !uploading ? () => onRemove(assets.first) : null,
          )
        else
          _ImageGrid(
            assets: assets,
            contentUrlFor: contentUrlFor,
            onRemove: enabled && !uploading ? onRemove : null,
          ),
        if (!_single && assets.isNotEmpty) ...[
          const SizedBox(height: 7),
          Text(
            '已选择 ${assets.length}/$maxItems',
            style: const TextStyle(color: AppColors.muted, fontSize: 11),
          ),
        ],
        const SizedBox(height: 9),
        SizedBox(
          width: double.infinity,
          height: 48,
          child: OutlinedButton.icon(
            onPressed: _canPick ? onPickImages : null,
            icon: uploading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.photo_library_outlined),
            label: Text(_galleryLabel),
          ),
        ),
        if (onChooseLibrary != null)
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: _canPick ? onChooseLibrary : null,
              icon: const Icon(Icons.folder_outlined, size: 19),
              label: const Text('我的文件'),
            ),
          ),
        if (disabledReason?.trim().isNotEmpty == true)
          Padding(
            padding: const EdgeInsets.only(top: 3),
            child: Text(
              disabledReason!,
              style: const TextStyle(color: AppColors.muted, fontSize: 11),
            ),
          ),
      ],
    );
  }

  String get _galleryLabel {
    if (uploading) return '正在上传';
    if (_atLimit) return '已达到数量上限';
    if (assets.isEmpty) return '从相册选择图片';
    if (_single) return '更换图片';
    return '继续从相册添加';
  }
}

class _EmptyImageSelection extends StatelessWidget {
  const _EmptyImageSelection({required this.maxItems});

  final int maxItems;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        height: 112,
        decoration: BoxDecoration(
          color: AppColors.wash,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: AppColors.line),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(
              Icons.photo_library_outlined,
              color: AppColors.accent,
              size: 30,
            ),
            const SizedBox(height: 7),
            const Text(
              '选择图片',
              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 3),
            Text(
              maxItems == 1 ? '选择 1 张' : '最多 $maxItems 张',
              style: const TextStyle(color: AppColors.muted, fontSize: 11),
            ),
          ],
        ),
      );
}

class _SingleImagePreview extends StatelessWidget {
  const _SingleImagePreview({
    required this.asset,
    required this.contentUrl,
    required this.onRemove,
  });

  final AssetView asset;
  final String contentUrl;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context) => AspectRatio(
        key: const ValueKey<String>('image-picker-single-preview'),
        aspectRatio: 4 / 3,
        child: Stack(
          fit: StackFit.expand,
          children: [
            _PreviewImage(contentUrl: contentUrl),
            if (onRemove != null)
              _RemoveImageButton(
                key: ValueKey<String>('image-picker-remove-${asset.id}'),
                onPressed: onRemove!,
              ),
          ],
        ),
      );
}

class _ImageGrid extends StatelessWidget {
  const _ImageGrid({
    required this.assets,
    required this.contentUrlFor,
    required this.onRemove,
  });

  final List<AssetView> assets;
  final String Function(AssetView asset) contentUrlFor;
  final ValueChanged<AssetView>? onRemove;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
        builder: (context, constraints) {
          final availableWidth =
              constraints.maxWidth.isFinite ? constraints.maxWidth : 360.0;
          final columnCount = availableWidth >= 280
              ? 3
              : availableWidth >= 184
                  ? 2
                  : 1;
          final tileSize =
              ((availableWidth - (columnCount - 1) * 8) / columnCount)
                  .clamp(0.0, 132.0)
                  .toDouble();
          return Wrap(
            key: const ValueKey<String>('image-picker-grid'),
            spacing: 8,
            runSpacing: 8,
            children: assets
                .map(
                  (asset) => SizedBox.square(
                    dimension: tileSize,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        _PreviewImage(contentUrl: contentUrlFor(asset)),
                        if (onRemove != null)
                          _RemoveImageButton(
                            key: ValueKey<String>(
                              'image-picker-remove-${asset.id}',
                            ),
                            onPressed: () => onRemove!(asset),
                          ),
                      ],
                    ),
                  ),
                )
                .toList(),
          );
        },
      );
}

class _PreviewImage extends StatelessWidget {
  const _PreviewImage({required this.contentUrl});

  final String contentUrl;

  @override
  Widget build(BuildContext context) => ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: ColoredBox(
          color: AppColors.wash,
          child: Image.network(
            contentUrl,
            fit: BoxFit.contain,
            errorBuilder: (_, __, ___) => const Center(
              child: Icon(Icons.broken_image_outlined, color: AppColors.muted),
            ),
          ),
        ),
      );
}

class _RemoveImageButton extends StatelessWidget {
  const _RemoveImageButton({
    super.key,
    required this.onPressed,
  });

  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) => Positioned(
        top: 6,
        right: 6,
        child: IconButton.filledTonal(
          onPressed: onPressed,
          tooltip: '移除图片',
          style: IconButton.styleFrom(
            fixedSize: const Size(32, 32),
            minimumSize: const Size(32, 32),
            padding: EdgeInsets.zero,
            backgroundColor: Colors.white.withOpacity(0.9),
            foregroundColor: AppColors.muted,
          ),
          icon: const Icon(Icons.close_rounded, size: 18),
        ),
      );
}
