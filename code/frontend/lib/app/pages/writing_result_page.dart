import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/feature_models.dart';
import '../network/backend_api.dart';
import '../network/native_file_picker.dart';
import '../theme/app_theme.dart';
import '../widgets/markdown_output_view.dart';
import 'asset_preview_page.dart';

class ArtifactResultPage extends StatelessWidget {
  const ArtifactResultPage({
    super.key,
    required this.artifact,
    this.rendererKey,
    this.onContinue,
  });

  final ArtifactView artifact;
  final String? rendererKey;
  final VoidCallback? onContinue;

  @override
  Widget build(BuildContext context) {
    final copyText = artifact.content['text']?.toString();
    final downloadableFiles = artifact.assets
        .where((asset) => asset.available && !asset.isImage)
        .toList();
    return Scaffold(
      appBar: AppBar(
        title: const Text('任务成果'),
        actions: [
          if (artifact.assets.any((asset) => asset.available && asset.isImage))
            IconButton(
              onPressed: () => _downloadImages(context),
              tooltip: '下载图片',
              icon: const Icon(Icons.download_outlined),
            ),
          if (downloadableFiles.isNotEmpty)
            IconButton(
              onPressed: () => _downloadFiles(context, downloadableFiles),
              tooltip: '下载文件',
              icon: const Icon(Icons.download_outlined),
            ),
          if (onContinue != null)
            IconButton(
              onPressed: onContinue,
              tooltip: '基于此版本继续修改',
              icon: const Icon(Icons.edit_note_rounded),
            ),
          if (copyText != null)
            IconButton(
              onPressed: () => _copy(context, copyText),
              tooltip: '复制全文',
              icon: const Icon(Icons.copy_all_outlined),
            ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 32),
          children: [
            Text(artifact.title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 5),
            Text(
              'v${artifact.versionNumber} · ${artifact.kind} · ${_formatDate(artifact.createdAt)}',
              style: const TextStyle(color: AppColors.muted, fontSize: 11),
            ),
            const SizedBox(height: 20),
            const Divider(height: 1),
            const SizedBox(height: 20),
            _ArtifactBody(artifact: artifact, rendererKey: rendererKey),
          ],
        ),
      ),
    );
  }

  Future<void> _copy(BuildContext context, String text) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('全文已复制')));
    }
  }

  Future<void> _downloadImages(BuildContext context) async {
    final assetIds = artifact.assets
        .where((asset) => asset.available && asset.isImage)
        .map((asset) => asset.id)
        .toList();
    try {
      for (var index = 0; index < assetIds.length; index++) {
        final assetId = assetIds[index];
        final bytes = await BackendApi.instance.downloadAssetContent(assetId);
        final extension = switch (artifact.mimeType) {
          'image/jpeg' => 'jpg',
          'image/webp' => 'webp',
          _ => 'png',
        };
        final suffix = assetIds.length == 1 ? '' : '-${index + 1}';
        final saved = await NativeFilePicker.save(
          fileName: '${_safeFileName(artifact.title)}$suffix.$extension',
          mediaType: artifact.mimeType,
          bytes: bytes,
        );
        if (!saved) return;
      }
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('图片已保存')));
      }
    } catch (exception) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$exception')));
      }
    }
  }

  Future<void> _downloadFiles(
    BuildContext context,
    List<AssetView> assets,
  ) async {
    try {
      for (final asset in assets) {
        final bytes = await BackendApi.instance.downloadAssetContent(asset.id);
        final saved = await NativeFilePicker.save(
          fileName: asset.name,
          mediaType: asset.mediaType,
          bytes: bytes,
        );
        if (!saved) return;
      }
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('文件已保存')));
      }
    } catch (exception) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$exception')));
      }
    }
  }
}

String _safeFileName(String value) => value
    .replaceAll(RegExp(r'[\\/:*?"<>|]'), '_')
    .trim()
    .replaceAll(RegExp(r'\s+'), '_');

class _ArtifactBody extends StatelessWidget {
  const _ArtifactBody({required this.artifact, required this.rendererKey});
  final ArtifactView artifact;
  final String? rendererKey;

  @override
  Widget build(BuildContext context) {
    final text = artifact.content['text']?.toString() ?? '';
    final format = artifact.content['format']?.toString();
    if (artifact.assets.isNotEmpty &&
        artifact.assets.every((asset) => !asset.available)) {
      return const _DeletedAssetResult();
    }
    if (artifact.mimeType == 'text/plain' || format == 'plain_text') {
      return SelectableText(
        text,
        style: Theme.of(context).textTheme.bodyLarge,
      );
    }
    if (artifact.kind == 'rich_text' || artifact.mimeType.startsWith('text/')) {
      return MarkdownOutputView(markdown: text);
    }
    if (artifact.kind == 'transcript' || rendererKey == 'transcript') {
      return _TranscriptRenderer(content: artifact.content);
    }
    if (artifact.kind == 'image' || artifact.mimeType.startsWith('image/')) {
      return _ImageRenderer(content: artifact.content, assets: artifact.assets);
    }
    if (artifact.kind == 'audio' || artifact.mimeType.startsWith('audio/')) {
      final asset = artifact.assets.firstOrNull;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _MediaRenderer(
            icon: Icons.graphic_eq_rounded,
            label: '音频成果',
            content: artifact.content,
            asset: asset,
          ),
          _GenerationParameters(metadata: artifact.metadata, asset: asset),
        ],
      );
    }
    if (artifact.kind == 'video' || artifact.mimeType.startsWith('video/')) {
      return _MediaRenderer(
          icon: Icons.play_circle_outline_rounded,
          label: '视频成果',
          content: artifact.content,
          asset: artifact.assets.firstOrNull);
    }
    if (artifact.kind == 'file' || artifact.content['assetId'] != null) {
      return _MediaRenderer(
          icon: Icons.insert_drive_file_outlined,
          label: '文件成果',
          content: artifact.content,
          asset: artifact.assets.firstOrNull);
    }
    return SelectableText(
      const JsonEncoder.withIndent('  ').convert(artifact.content),
      style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
    );
  }
}

class _TranscriptRenderer extends StatefulWidget {
  const _TranscriptRenderer({required this.content});
  final Map<String, dynamic> content;

  @override
  State<_TranscriptRenderer> createState() => _TranscriptRendererState();
}

class _TranscriptRendererState extends State<_TranscriptRenderer> {
  var _showSupplement = false;

  @override
  Widget build(BuildContext context) {
    final content = widget.content;
    final supplement = content['supplement'] is Map
        ? Map<String, dynamic>.from(content['supplement'] as Map)
        : null;
    final hasSupplement = supplement != null;
    final supplementLabel =
        supplement?['type'] == 'meeting_minutes' ? '会议纪要' : '摘要';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (hasSupplement) ...[
          SizedBox(
            width: double.infinity,
            child: SegmentedButton<bool>(
              segments: [
                const ButtonSegment(value: false, label: Text('逐字稿')),
                ButtonSegment(value: true, label: Text(supplementLabel)),
              ],
              selected: {_showSupplement},
              showSelectedIcon: false,
              onSelectionChanged: (selection) =>
                  setState(() => _showSupplement = selection.first),
              style: ButtonStyle(
                shape: WidgetStatePropertyAll(RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                )),
              ),
            ),
          ),
          const SizedBox(height: 20),
        ],
        if (_showSupplement && hasSupplement)
          _TranscriptSupplement(supplement: supplement)
        else
          _TranscriptSegments(content: content),
      ],
    );
  }
}

class _TranscriptSegments extends StatelessWidget {
  const _TranscriptSegments({required this.content});
  final Map<String, dynamic> content;

  @override
  Widget build(BuildContext context) {
    final segments = content['segments'];
    if (segments is! List || segments.isEmpty) {
      return SelectableText(content['text']?.toString() ?? '没有转写内容');
    }
    final showTimestamps = content['timestampMode'] != 'none';
    final showSpeakers = content['speakerDiarization'] == true;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: segments.whereType<Map>().map((segment) {
        final value = Map<String, dynamic>.from(segment);
        final speaker = value['speaker']?.toString().trim();
        return Padding(
          padding: const EdgeInsets.only(bottom: 16),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            if (showTimestamps)
              SizedBox(
                width: 52,
                child: Text(
                  _transcriptTimestamp(value),
                  style: const TextStyle(
                    color: AppColors.muted,
                    fontSize: 11,
                  ),
                ),
              ),
            Expanded(child: SelectableText(value['text']?.toString() ?? '')),
            if (showSpeakers && speaker?.isNotEmpty == true) ...[
              const SizedBox(width: 10),
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 88),
                child: Text(
                  '说话人 $speaker',
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.accent,
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ]),
        );
      }).toList(),
    );
  }
}

class _TranscriptSupplement extends StatelessWidget {
  const _TranscriptSupplement({required this.supplement});
  final Map<String, dynamic> supplement;

  @override
  Widget build(BuildContext context) {
    if (supplement['status'] == 'FAILED') {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppColors.danger.withOpacity(0.08),
          border: Border.all(color: AppColors.danger.withOpacity(0.3)),
          borderRadius: BorderRadius.circular(8),
        ),
        child: const Text('逐字稿已生成，但附加结果生成失败。请从历史记录继续生成新版本。'),
      );
    }
    final text = supplement['text']?.toString() ?? '';
    if (text.trim().isEmpty) return const Text('没有附加结果');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Align(
          alignment: Alignment.centerRight,
          child: IconButton(
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: text));
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('当前内容已复制')),
                );
              }
            },
            tooltip: '复制当前内容',
            icon: const Icon(Icons.copy_all_outlined),
          ),
        ),
        MarkdownOutputView(markdown: text),
      ],
    );
  }
}

String _transcriptTimestamp(Map<String, dynamic> segment) {
  final raw = segment['startMs'];
  if (raw is num) return _formatTranscriptMilliseconds(raw.toInt());
  final parsed = int.tryParse(raw?.toString() ?? '');
  if (parsed != null) return _formatTranscriptMilliseconds(parsed);
  return segment['startLabel']?.toString() ?? '';
}

String _formatTranscriptMilliseconds(int milliseconds) {
  final totalSeconds = milliseconds.clamp(0, 359999999) ~/ 1000;
  final hours = totalSeconds ~/ 3600;
  final minutes = (totalSeconds % 3600) ~/ 60;
  final seconds = totalSeconds % 60;
  String twoDigits(int value) => value.toString().padLeft(2, '0');
  return hours > 0
      ? '${twoDigits(hours)}:${twoDigits(minutes)}:${twoDigits(seconds)}'
      : '${twoDigits(minutes)}:${twoDigits(seconds)}';
}

class _ImageRenderer extends StatelessWidget {
  const _ImageRenderer({required this.content, required this.assets});
  final Map<String, dynamic> content;
  final List<AssetView> assets;
  @override
  Widget build(BuildContext context) {
    final assetId = content['assetId']?.toString();
    final assetIds = content['assetIds'];
    final url = content['url']?.toString();
    final base64Data = content['base64']?.toString();
    final availableIds = assets
        .where((asset) => asset.available)
        .map((asset) => asset.id)
        .toSet();
    Widget image;
    if (assetIds is List && assetIds.isNotEmpty) {
      final visibleIds = assetIds
          .map((id) => id.toString())
          .where((id) => assets.isEmpty || availableIds.contains(id))
          .toList();
      if (visibleIds.isEmpty) return const _DeletedAssetResult();
      return Column(
        children: visibleIds
            .map((id) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: _PreviewableImage(
                    image: _networkImage(
                      BackendApi.instance.assetContentUrl(id.toString()),
                    ),
                  ),
                ))
            .toList(),
      );
    } else if (assetId != null && assetId.isNotEmpty) {
      if (assets.isNotEmpty && !availableIds.contains(assetId)) {
        return const _DeletedAssetResult();
      }
      image = _networkImage(BackendApi.instance.assetContentUrl(assetId));
    } else if (url != null && url.isNotEmpty) {
      image = _networkImage(url);
    } else if (base64Data != null && base64Data.isNotEmpty) {
      image = Image.memory(base64Decode(base64Data), fit: BoxFit.contain);
    } else {
      return const _MediaRenderer(
          icon: Icons.image_not_supported_outlined,
          label: '图片数据不可用',
          content: {});
    }
    return _PreviewableImage(image: image);
  }

  static Widget _networkImage(String url) => Image.network(
        url,
        fit: BoxFit.contain,
        loadingBuilder: (context, child, progress) {
          if (progress == null) return child;
          return const Padding(
            padding: EdgeInsets.symmetric(vertical: 72),
            child: Center(child: CircularProgressIndicator()),
          );
        },
        errorBuilder: (context, error, stackTrace) => const Padding(
          padding: EdgeInsets.symmetric(vertical: 48),
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.broken_image_outlined, size: 36),
                SizedBox(height: 8),
                Text('图片加载失败'),
              ],
            ),
          ),
        ),
      );
}

class _PreviewableImage extends StatelessWidget {
  const _PreviewableImage({required this.image});
  final Widget image;

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: () => showGeneralDialog<void>(
          context: context,
          barrierDismissible: false,
          barrierColor: Colors.black,
          transitionDuration: const Duration(milliseconds: 160),
          pageBuilder: (context, _, __) =>
              _FullscreenImagePreview(image: image),
          transitionBuilder: (context, animation, _, child) => FadeTransition(
            opacity: CurvedAnimation(
              parent: animation,
              curve: Curves.easeOut,
              reverseCurve: Curves.easeIn,
            ),
            child: child,
          ),
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: _TransparencyBackdrop(child: image),
        ),
      );
}

class _FullscreenImagePreview extends StatelessWidget {
  const _FullscreenImagePreview({required this.image});
  final Widget image;

  @override
  Widget build(BuildContext context) => Material(
        color: Colors.black,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () => Navigator.of(context).pop(),
          child: SizedBox.expand(
            child: InteractiveViewer(
              minScale: 1,
              maxScale: 4,
              clipBehavior: Clip.none,
              child: SizedBox.expand(
                child: _TransparencyBackdrop(child: image),
              ),
            ),
          ),
        ),
      );
}

class _TransparencyBackdrop extends StatelessWidget {
  const _TransparencyBackdrop({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => CustomPaint(
        key: const ValueKey('image-transparency-backdrop'),
        painter: const _CheckerboardPainter(),
        child: child,
      );
}

class _CheckerboardPainter extends CustomPainter {
  const _CheckerboardPainter();

  static const _tileSize = 14.0;
  static const _light = Color(0xFFF4F4F4);
  static const _dark = Color(0xFFE2E2E2);

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint();
    for (double y = 0; y < size.height; y += _tileSize) {
      for (double x = 0; x < size.width; x += _tileSize) {
        final column = (x / _tileSize).floor();
        final row = (y / _tileSize).floor();
        paint.color = (column + row).isEven ? _light : _dark;
        canvas.drawRect(
          Rect.fromLTWH(
            x,
            y,
            _tileSize.clamp(0, size.width - x),
            _tileSize.clamp(0, size.height - y),
          ),
          paint,
        );
      }
    }
  }

  @override
  bool shouldRepaint(covariant _CheckerboardPainter oldDelegate) => false;
}

class _GenerationParameters extends StatelessWidget {
  const _GenerationParameters({required this.metadata, required this.asset});

  final Map<String, dynamic> metadata;
  final AssetView? asset;

  @override
  Widget build(BuildContext context) {
    final values = <String, String>{};
    final model = metadata['model']?.toString().trim();
    if (model?.isNotEmpty == true) values['模型'] = _displayIdentifier(model!);
    _addMetadataValue(values, '声音', metadata, 'voiceLabel', 'voice');
    _addMetadataValue(values, '语速', metadata, 'speedLabel', 'speed');
    _addMetadataValue(values, '情绪', metadata, 'emotionLabel', 'emotion');
    if (asset != null) values['文件大小'] = _formatBytes(asset!.sizeBytes);
    if (values.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('生成参数', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 10),
          ...values.entries.map((entry) => Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(
                      width: 72,
                      child: Text(
                        entry.key,
                        style: const TextStyle(color: AppColors.muted),
                      ),
                    ),
                    Expanded(child: Text(entry.value)),
                  ],
                ),
              )),
        ],
      ),
    );
  }
}

void _addMetadataValue(
  Map<String, String> values,
  String label,
  Map<String, dynamic> metadata,
  String displayKey,
  String valueKey,
) {
  final display = metadata[displayKey]?.toString().trim();
  final raw = metadata[valueKey]?.toString().trim();
  final value = display?.isNotEmpty == true
      ? display!
      : _displayBusinessValue(valueKey, raw);
  if (value?.isNotEmpty == true) values[label] = value!;
}

String? _displayBusinessValue(String field, String? value) {
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
  return labels[field]?[value] ?? _displayIdentifier(value);
}

String _displayIdentifier(String value) => value
    .split(RegExp(r'[-_\s]+'))
    .where((part) => part.isNotEmpty)
    .map((part) => RegExp(
              r'^(?:ai|api|gpt|tts\d*|v\d+)$',
              caseSensitive: false,
            ).hasMatch(part)
        ? part.toUpperCase()
        : '${part[0].toUpperCase()}${part.substring(1)}')
    .join(' ');

String _formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) {
    return '${(bytes / 1024).toStringAsFixed(1)} KB';
  }
  return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
}
class _MediaRenderer extends StatelessWidget {
  const _MediaRenderer(
      {required this.icon,
      required this.label,
      required this.content,
      this.asset});
  final IconData icon;
  final String label;
  final Map<String, dynamic> content;
  final AssetView? asset;

  @override
  Widget build(BuildContext context) {
    if (asset?.available == false) return const _DeletedAssetResult();
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 18),
      decoration: const BoxDecoration(
        border: Border.symmetric(horizontal: BorderSide(color: AppColors.line)),
      ),
      child: Row(children: [
        Icon(icon, color: AppColors.accent, size: 26),
        const SizedBox(width: 12),
        Expanded(
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
            if (content['name'] != null) ...[
              const SizedBox(height: 4),
              Text(content['name'].toString(),
                  style: const TextStyle(color: AppColors.muted, fontSize: 12)),
            ],
            if (asset != null) ...[
              const SizedBox(height: 8),
              TextButton.icon(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (context) => AssetPreviewPage(
                      api: BackendApi.instance,
                      asset: asset!,
                    ),
                  ),
                ),
                icon: const Icon(Icons.visibility_outlined, size: 18),
                label: const Text('打开预览'),
              ),
              TextButton.icon(
                onPressed: () => _downloadAsset(context, asset!),
                icon: const Icon(Icons.download_outlined, size: 18),
                label: const Text('下载'),
              ),
            ],
          ]),
        ),
      ]),
    );
  }
}

Future<void> _downloadAsset(BuildContext context, AssetView asset) async {
  try {
    final bytes = await BackendApi.instance.downloadAssetContent(asset.id);
    final saved = await NativeFilePicker.save(
      fileName: asset.name,
      mediaType: asset.mediaType,
      bytes: bytes,
    );
    if (saved && context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('文件已保存')));
    }
  } catch (exception) {
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('$exception')));
    }
  }
}

class _DeletedAssetResult extends StatelessWidget {
  const _DeletedAssetResult();

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 26),
        decoration: const BoxDecoration(
          border: Border.symmetric(
            horizontal: BorderSide(color: AppColors.line),
          ),
        ),
        child: const Column(
          children: [
            Icon(Icons.delete_outline_rounded,
                color: AppColors.muted, size: 34),
            SizedBox(height: 10),
            Text(
              '原成果已删除',
              style: TextStyle(fontWeight: FontWeight.w700),
            ),
            SizedBox(height: 5),
            Text(
              '任务记录、提示词和文件信息仍然保留。继续修改时可以重新上传文件。',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.muted, fontSize: 12),
            ),
          ],
        ),
      );
}

String _formatDate(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}';
