import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/data_analysis_models.dart';
import '../models/feature_models.dart';
import '../network/backend_api.dart';
import '../network/native_file_picker.dart';
import '../theme/app_theme.dart';
import '../widgets/markdown_output_view.dart';

class DataAnalysisResultPage extends StatelessWidget {
  const DataAnalysisResultPage({
    super.key,
    required this.artifact,
    this.onContinue,
  });

  final ArtifactView artifact;
  final VoidCallback? onContinue;

  @override
  Widget build(BuildContext context) {
    final result = DataAnalysisResult.fromArtifact(artifact);
    final availableCharts =
        result.charts.where((chart) => chart.asset?.available == true).toList();
    return Scaffold(
      appBar: AppBar(
        title: const Text('数据分析结果'),
        actions: [
          if (availableCharts.isNotEmpty)
            IconButton(
              onPressed: () => _downloadCharts(context, availableCharts),
              tooltip: '保存全部图表',
              icon: const Icon(Icons.image_outlined),
            ),
          if (result.reportAsset?.available == true)
            IconButton(
              onPressed: () => _downloadAsset(context, result.reportAsset!),
              tooltip: '保存分析报告',
              icon: const Icon(Icons.download_outlined),
            ),
          if (onContinue != null)
            IconButton(
              onPressed: onContinue,
              tooltip: '基于此版本继续分析',
              icon: const Icon(Icons.edit_note_rounded),
            ),
          if (result.copyText.isNotEmpty)
            IconButton(
              onPressed: () => _copy(context, result.copyText),
              tooltip: '复制分析文字',
              icon: const Icon(Icons.copy_all_outlined),
            ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 36),
          children: [
            Text(artifact.title, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 5),
            Text(
              'v${artifact.versionNumber} · ${_formatDate(artifact.createdAt)}',
              style: const TextStyle(color: AppColors.muted, fontSize: 11),
            ),
            if (result.warnings.isNotEmpty) ...[
              const SizedBox(height: 18),
              _WarningBlock(warnings: result.warnings),
            ],
            const SizedBox(height: 26),
            const _SectionTitle('分析概览'),
            const SizedBox(height: 10),
            if (result.summaryMarkdown.isEmpty)
              const Text('没有可展示的分析摘要。')
            else
              MarkdownOutputView(markdown: result.summaryMarkdown),
            const SizedBox(height: 30),
            const _SectionTitle('核心结论'),
            const SizedBox(height: 4),
            if (result.conclusions.isEmpty)
              const _EmptyLine('未生成核心结论。')
            else
              ...result.conclusions.map(_ConclusionView.new),
            const SizedBox(height: 28),
            Row(
              children: [
                const Expanded(child: _SectionTitle('异常清单')),
                Text(
                  '${result.anomalies.length} 项',
                  style: const TextStyle(color: AppColors.muted, fontSize: 12),
                ),
              ],
            ),
            const SizedBox(height: 10),
            if (result.anomalies.isEmpty)
              const _EmptyLine('未发现符合当前规则的异常。')
            else
              ...result.anomalies.map(_AnomalyView.new),
            const SizedBox(height: 28),
            Row(
              children: [
                const Expanded(child: _SectionTitle('分析图表')),
                Text(
                  '${result.charts.length} 张',
                  style: const TextStyle(color: AppColors.muted, fontSize: 12),
                ),
              ],
            ),
            const SizedBox(height: 10),
            if (result.charts.isEmpty)
              const _EmptyLine('没有可展示的图表。')
            else
              ...result.charts.map(
                (chart) => _ChartView(
                  chart: chart,
                  onDownload: chart.asset?.available == true
                      ? () => _downloadAsset(context, chart.asset!)
                      : null,
                ),
              ),
            const SizedBox(height: 28),
            const _SectionTitle('XLSX 分析报告'),
            const SizedBox(height: 10),
            _ReportRow(
              name: result.reportName,
              available: result.reportAsset?.available == true,
              onDownload: result.reportAsset?.available == true
                  ? () => _downloadAsset(context, result.reportAsset!)
                  : null,
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _copy(BuildContext context, String text) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('分析文字已复制')),
    );
  }

  Future<void> _downloadCharts(
    BuildContext context,
    List<DataAnalysisChart> charts,
  ) async {
    for (final chart in charts) {
      final asset = chart.asset;
      if (asset == null) continue;
      final saved = await _saveAsset(asset);
      if (!saved) return;
    }
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('图表已保存')),
    );
  }

  Future<void> _downloadAsset(
    BuildContext context,
    AssetView asset,
  ) async {
    try {
      final saved = await _saveAsset(asset);
      if (saved && context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('文件已保存')),
        );
      }
    } catch (exception) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('$exception')),
      );
    }
  }

  Future<bool> _saveAsset(AssetView asset) async {
    final bytes = await BackendApi.instance.downloadAssetContent(asset.id);
    return NativeFilePicker.save(
      fileName: asset.name,
      mediaType: asset.mediaType,
      bytes: bytes,
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Text(
        text,
        style: Theme.of(context).textTheme.titleMedium,
      );
}

class _ConclusionView extends StatelessWidget {
  const _ConclusionView(this.conclusion);

  final DataAnalysisConclusion conclusion;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 15),
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: AppColors.line)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              conclusion.title,
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            Text(conclusion.detail),
            if (conclusion.evidence.isNotEmpty) ...[
              const SizedBox(height: 8),
              ...conclusion.evidence.map(
                (value) => Padding(
                  padding: const EdgeInsets.only(bottom: 3),
                  child: Text(
                    '· $value',
                    style: const TextStyle(
                      color: AppColors.muted,
                      fontSize: 12,
                    ),
                  ),
                ),
              ),
            ],
          ],
        ),
      );
}

class _AnomalyView extends StatelessWidget {
  const _AnomalyView(this.anomaly);

  final DataAnalysisAnomaly anomaly;

  @override
  Widget build(BuildContext context) {
    final color = switch (anomaly.severity) {
      'CRITICAL' => AppColors.danger,
      'WARNING' => const Color(0xFFB26A00),
      _ => AppColors.accent,
    };
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: color.withOpacity(0.06),
        border: Border(left: BorderSide(color: color, width: 3)),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  anomaly.description,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
              ),
              const SizedBox(width: 8),
              Text(
                anomaly.severity,
                style: TextStyle(
                  color: color,
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          if (anomaly.locationLabel.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              anomaly.locationLabel,
              style: const TextStyle(color: AppColors.muted, fontSize: 11),
            ),
          ],
          const SizedBox(height: 7),
          Text(anomaly.evidence),
          if (anomaly.interpretation.isNotEmpty) ...[
            const SizedBox(height: 7),
            Text(anomaly.interpretation),
          ],
          if (anomaly.suggestion.isNotEmpty) ...[
            const SizedBox(height: 7),
            Text(
              '建议：${anomaly.suggestion}',
              style: const TextStyle(color: AppColors.muted, fontSize: 12),
            ),
          ],
        ],
      ),
    );
  }
}

class _ChartView extends StatelessWidget {
  const _ChartView({
    required this.chart,
    required this.onDownload,
  });

  final DataAnalysisChart chart;
  final VoidCallback? onDownload;

  @override
  Widget build(BuildContext context) {
    final asset = chart.asset;
    final image = asset?.available == true
        ? Image.network(
            BackendApi.instance.assetContentUrl(asset!.id),
            fit: BoxFit.contain,
            errorBuilder: (_, __, ___) => const _ChartUnavailable(),
          )
        : const _ChartUnavailable();
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 16),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AppColors.line)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  chart.title,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
              ),
              if (onDownload != null)
                IconButton(
                  onPressed: onDownload,
                  tooltip: '保存图表',
                  icon: const Icon(Icons.download_outlined, size: 20),
                ),
            ],
          ),
          Text(
            [
              chart.sheetName,
              chart.aggregation,
            ].where((value) => value.isNotEmpty).join(' · '),
            style: const TextStyle(color: AppColors.muted, fontSize: 11),
          ),
          const SizedBox(height: 10),
          GestureDetector(
            onTap: asset?.available == true
                ? () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (context) => _FullscreenChart(image: image),
                      ),
                    )
                : null,
            child: AspectRatio(
              aspectRatio: 16 / 9,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: ColoredBox(
                  color: Colors.white,
                  child: image,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _FullscreenChart extends StatelessWidget {
  const _FullscreenChart({required this.image});

  final Widget image;

  @override
  Widget build(BuildContext context) => Material(
        color: Colors.black,
        child: SafeArea(
          child: Stack(
            children: [
              Positioned.fill(
                child: InteractiveViewer(
                  minScale: 1,
                  maxScale: 5,
                  child: Center(child: image),
                ),
              ),
              Positioned(
                top: 8,
                right: 8,
                child: IconButton(
                  onPressed: () => Navigator.of(context).pop(),
                  tooltip: '关闭',
                  color: Colors.white,
                  icon: const Icon(Icons.close_rounded),
                ),
              ),
            ],
          ),
        ),
      );
}

class _ChartUnavailable extends StatelessWidget {
  const _ChartUnavailable();

  @override
  Widget build(BuildContext context) => Container(
        alignment: Alignment.center,
        color: AppColors.wash,
        child: const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.broken_image_outlined, color: AppColors.muted),
            SizedBox(height: 7),
            Text(
              '图表文件不可用',
              style: TextStyle(color: AppColors.muted, fontSize: 12),
            ),
          ],
        ),
      );
}

class _ReportRow extends StatelessWidget {
  const _ReportRow({
    required this.name,
    required this.available,
    required this.onDownload,
  });

  final String name;
  final bool available;
  final VoidCallback? onDownload;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 14),
        decoration: const BoxDecoration(
          border:
              Border.symmetric(horizontal: BorderSide(color: AppColors.line)),
        ),
        child: Row(
          children: [
            Icon(
              available
                  ? Icons.table_chart_outlined
                  : Icons.delete_outline_rounded,
              color: available ? AppColors.accent : AppColors.muted,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                name,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            IconButton(
              onPressed: onDownload,
              tooltip: available ? '保存分析报告' : '报告文件已删除',
              icon: const Icon(Icons.download_outlined),
            ),
          ],
        ),
      );
}

class _WarningBlock extends StatelessWidget {
  const _WarningBlock({required this.warnings});

  final List<String> warnings;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(13),
        decoration: BoxDecoration(
          color: const Color(0xFFFFF7E8),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '分析提示',
              style: TextStyle(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 5),
            ...warnings.map(
              (warning) => Padding(
                padding: const EdgeInsets.only(bottom: 3),
                child: Text('· $warning'),
              ),
            ),
          ],
        ),
      );
}

class _EmptyLine extends StatelessWidget {
  const _EmptyLine(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 14),
        child: Text(
          text,
          style: const TextStyle(color: AppColors.muted),
        ),
      );
}

String _formatDate(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')} '
    '${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}';
