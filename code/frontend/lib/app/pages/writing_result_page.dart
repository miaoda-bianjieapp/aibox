import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/feature_models.dart';
import '../network/backend_api.dart';
import '../theme/app_theme.dart';

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
    return Scaffold(
      appBar: AppBar(
        title: const Text('任务成果'),
        actions: [
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
}

class _ArtifactBody extends StatelessWidget {
  const _ArtifactBody({required this.artifact, required this.rendererKey});
  final ArtifactView artifact;
  final String? rendererKey;

  @override
  Widget build(BuildContext context) {
    if (artifact.kind == 'rich_text' || artifact.mimeType.startsWith('text/')) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: _markdownBlocks(
            context, artifact.content['text']?.toString() ?? ''),
      );
    }
    if (artifact.kind == 'transcript' || rendererKey == 'transcript') {
      return _TranscriptRenderer(content: artifact.content);
    }
    if (artifact.kind == 'image' || artifact.mimeType.startsWith('image/')) {
      return _ImageRenderer(content: artifact.content);
    }
    if (artifact.kind == 'audio' || artifact.mimeType.startsWith('audio/')) {
      return _MediaRenderer(
          icon: Icons.graphic_eq_rounded,
          label: '音频成果',
          content: artifact.content);
    }
    if (artifact.kind == 'video' || artifact.mimeType.startsWith('video/')) {
      return _MediaRenderer(
          icon: Icons.play_circle_outline_rounded,
          label: '视频成果',
          content: artifact.content);
    }
    if (artifact.kind == 'file' || artifact.content['assetId'] != null) {
      return _MediaRenderer(
          icon: Icons.insert_drive_file_outlined,
          label: '文件成果',
          content: artifact.content);
    }
    return SelectableText(
      const JsonEncoder.withIndent('  ').convert(artifact.content),
      style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
    );
  }
}

class _TranscriptRenderer extends StatelessWidget {
  const _TranscriptRenderer({required this.content});
  final Map<String, dynamic> content;
  @override
  Widget build(BuildContext context) {
    final segments = content['segments'];
    if (segments is! List || segments.isEmpty) {
      return SelectableText(content['text']?.toString() ?? '没有转写内容');
    }
    return Column(
      children: segments.whereType<Map>().map((segment) {
        final value = Map<String, dynamic>.from(segment);
        return Padding(
          padding: const EdgeInsets.only(bottom: 14),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            SizedBox(
              width: 56,
              child: Text(value['startLabel']?.toString() ?? '',
                  style: const TextStyle(color: AppColors.muted, fontSize: 11)),
            ),
            Expanded(child: SelectableText(value['text']?.toString() ?? '')),
          ]),
        );
      }).toList(),
    );
  }
}

class _ImageRenderer extends StatelessWidget {
  const _ImageRenderer({required this.content});
  final Map<String, dynamic> content;
  @override
  Widget build(BuildContext context) {
    final assetId = content['assetId']?.toString();
    final assetIds = content['assetIds'];
    final url = content['url']?.toString();
    final base64Data = content['base64']?.toString();
    Widget image;
    if (assetIds is List && assetIds.isNotEmpty) {
      return Column(
        children: assetIds
            .map((id) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.network(
                        BackendApi.instance.assetContentUrl(id.toString()),
                        fit: BoxFit.contain),
                  ),
                ))
            .toList(),
      );
    } else if (assetId != null && assetId.isNotEmpty) {
      image = Image.network(BackendApi.instance.assetContentUrl(assetId),
          fit: BoxFit.contain);
    } else if (url != null && url.isNotEmpty) {
      image = Image.network(url, fit: BoxFit.contain);
    } else if (base64Data != null && base64Data.isNotEmpty) {
      image = Image.memory(base64Decode(base64Data), fit: BoxFit.contain);
    } else {
      return const _MediaRenderer(
          icon: Icons.image_not_supported_outlined,
          label: '图片数据不可用',
          content: {});
    }
    return ClipRRect(borderRadius: BorderRadius.circular(8), child: image);
  }
}

class _MediaRenderer extends StatelessWidget {
  const _MediaRenderer(
      {required this.icon, required this.label, required this.content});
  final IconData icon;
  final String label;
  final Map<String, dynamic> content;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 18),
        decoration: const BoxDecoration(
          border:
              Border.symmetric(horizontal: BorderSide(color: AppColors.line)),
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
                    style:
                        const TextStyle(color: AppColors.muted, fontSize: 12)),
              ],
            ]),
          ),
        ]),
      );
}

List<Widget> _markdownBlocks(BuildContext context, String markdown) {
  return markdown.split('\n').map((line) {
    if (line.startsWith('# ')) {
      return Padding(
        padding: const EdgeInsets.only(bottom: 16),
        child: Text(line.substring(2),
            style: Theme.of(context).textTheme.headlineMedium),
      );
    }
    if (line.startsWith('## ')) {
      return Padding(
        padding: const EdgeInsets.only(top: 14, bottom: 8),
        child: Text(line.substring(3),
            style: Theme.of(context).textTheme.titleMedium),
      );
    }
    if (line.trim().isEmpty) return const SizedBox(height: 8);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: SelectableText(line, style: Theme.of(context).textTheme.bodyLarge),
    );
  }).toList();
}

String _formatDate(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}';
