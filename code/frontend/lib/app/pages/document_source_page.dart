import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../network/backend_api.dart';
import '../theme/app_theme.dart';
import 'asset_preview_page.dart';

class DocumentSourcePage extends StatelessWidget {
  const DocumentSourcePage({
    super.key,
    required this.api,
    required this.asset,
    required this.marker,
    required this.excerpt,
    required this.locator,
  });

  final BackendApi api;
  final AssetView asset;
  final String marker;
  final String excerpt;
  final Map<String, dynamic> locator;

  @override
  Widget build(BuildContext context) {
    final location = _locationLabel(locator);
    return Scaffold(
      appBar: AppBar(
        title: Text(
          '来源 $marker',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        actions: [
          if (asset.available)
            IconButton(
              onPressed: () => _openOriginal(context),
              tooltip: '打开原文件',
              icon: const Icon(Icons.open_in_new_rounded),
            ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 32),
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(
                  Icons.description_outlined,
                  color: AppColors.accent,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        asset.name,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 4),
                      Text(
                        location,
                        style: const TextStyle(
                          color: AppColors.muted,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            const Divider(height: 1),
            const SizedBox(height: 20),
            if (!asset.available)
              const _SourceWarning(
                icon: Icons.delete_outline_rounded,
                text: '原文件已删除，保留以下历史来源摘录。',
              ),
            SelectableText(
              excerpt,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            if (asset.available) ...[
              const SizedBox(height: 24),
              FilledButton.icon(
                onPressed: () => _openOriginal(context),
                icon: const Icon(Icons.visibility_outlined),
                label: const Text('在原文件中查看'),
              ),
            ],
          ],
        ),
      ),
    );
  }

  void _openOriginal(BuildContext context) {
    final pageNumber = documentSourceInitialPage(locator);
    final startLine = _integer(locator['startLine']);
    final endLine = _integer(locator['endLine']);
    final sheetName = documentSourceInitialSheetName(locator);
    final startRow = documentSourceInitialRow(locator);
    final endRow = documentSourceEndRow(locator);
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => AssetPreviewPage(
          api: api,
          asset: asset,
          initialPage: pageNumber,
          initialLine: startLine,
          endLine: endLine,
          initialSheetName: sheetName,
          initialRow: startRow,
          endRow: endRow,
        ),
      ),
    );
  }
}

int? documentSourceInitialPage(Map<String, dynamic> locator) {
  if (locator['type']?.toString() == 'PPT_SLIDE') {
    return _integer(locator['slideNumber']) ?? _integer(locator['pageNumber']);
  }
  return _integer(locator['pageNumber']);
}

String? documentSourceInitialSheetName(Map<String, dynamic> locator) {
  if (locator['type']?.toString() != 'EXCEL_ROWS') return null;
  final value = locator['sheetName']?.toString().trim();
  return value == null || value.isEmpty ? null : value;
}

int? documentSourceInitialRow(Map<String, dynamic> locator) {
  if (locator['type']?.toString() != 'EXCEL_ROWS') return null;
  return _integer(locator['startRow']);
}

int? documentSourceEndRow(Map<String, dynamic> locator) {
  if (locator['type']?.toString() != 'EXCEL_ROWS') return null;
  return _integer(locator['endRow']);
}

class _SourceWarning extends StatelessWidget {
  const _SourceWarning({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 18),
        child: Row(
          children: [
            Icon(icon, color: AppColors.danger, size: 19),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                text,
                style: const TextStyle(
                  color: AppColors.danger,
                  fontSize: 12,
                ),
              ),
            ),
          ],
        ),
      );
}

String _locationLabel(Map<String, dynamic> locator) {
  final type = locator['type']?.toString();
  return switch (type) {
    'PDF_PAGE' => 'PDF 第 ${_integer(locator['pageNumber']) ?? '?'} 页',
    'WORD_PARAGRAPH' => [
        if (locator['heading']?.toString().trim().isNotEmpty == true)
          locator['heading'].toString(),
        '第 ${_integer(locator['paragraphStart']) ?? '?'} 段',
      ].join(' · '),
    'EXCEL_ROWS' =>
      '${locator['sheetName'] ?? '工作表'} · 第 ${_integer(locator['startRow']) ?? '?'}'
          '-${_integer(locator['endRow']) ?? '?'} 行',
    'PPT_SLIDE' => 'PPT 第 ${_integer(locator['slideNumber']) ?? '?'} 页',
    'TEXT_LINES' => '第 ${_integer(locator['startLine']) ?? '?'}'
        '-${_integer(locator['endLine']) ?? '?'} 行',
    _ => '文档来源',
  };
}

int? _integer(Object? value) =>
    value is num ? value.toInt() : int.tryParse(value?.toString() ?? '');
