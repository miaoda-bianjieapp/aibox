import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/feature_models.dart';
import '../network/native_file_picker.dart';
import '../state/app_data_controller.dart';
import '../theme/app_theme.dart';
import 'document_source_page.dart';

class DocumentCompareResultPage extends StatefulWidget {
  const DocumentCompareResultPage({
    super.key,
    required this.data,
    required this.artifact,
    this.onContinue,
    this.loadTask,
  });

  final AppDataController data;
  final ArtifactView artifact;
  final VoidCallback? onContinue;
  final Future<TaskDetail> Function(String taskId)? loadTask;

  @override
  State<DocumentCompareResultPage> createState() =>
      _DocumentCompareResultPageState();
}

class _DocumentCompareResultPageState extends State<DocumentCompareResultPage> {
  TaskDetail? _taskDetail;
  String? _error;
  bool _exporting = false;

  Map<String, dynamic> get _content => widget.artifact.content;
  List<Map<String, dynamic>> get _pairs =>
      _mapList(_content['pairwiseComparisons']);
  Map<String, dynamic> get _conclusion =>
      _map(_content['crossDocumentConclusion']);
  Map<String, dynamic> get _comparability => _map(_content['comparability']);
  List<Map<String, dynamic>> get _risks => _mapList(_content['risks']);
  List<String> get _warnings => _stringList(_content['warnings']);
  Map<String, _ComparisonCitation> get _citations => {
        for (final value in _mapList(_content['citations']))
          if (_ComparisonCitation.fromMap(value).marker.isNotEmpty)
            _ComparisonCitation.fromMap(value).marker:
                _ComparisonCitation.fromMap(value),
      };

  @override
  void initState() {
    super.initState();
    _loadTask();
  }

  Future<void> _loadTask() async {
    try {
      final detail = await (widget.loadTask ?? widget.data.api.getTask)(
        widget.artifact.taskId,
      );
      if (!mounted) return;
      setState(() => _taskDetail = detail);
    } catch (_) {
      // The report remains readable even when source metadata cannot be loaded.
    }
  }

  @override
  Widget build(BuildContext context) {
    final summary = _conclusion['summary']?.toString().trim() ?? '';
    final findings = _mapList(_conclusion['findings']);
    final exports = _availableExports();
    final hasComparability = _comparability.isNotEmpty;
    final overallStatus =
        hasComparability ? _comparabilityStatus(_comparability) : '';
    final terminalComparison =
        hasComparability && _isTerminalComparability(overallStatus);
    return Scaffold(
      appBar: AppBar(
        title: const Text('多文档对比'),
        actions: [
          if (_content['reportMarkdown']?.toString().isNotEmpty == true)
            IconButton(
              onPressed: _copyReport,
              tooltip: '复制报告',
              icon: const Icon(Icons.copy_all_outlined),
            ),
          if (exports.isNotEmpty)
            PopupMenuButton<String>(
              enabled: !_exporting,
              tooltip: '导出',
              icon: _exporting
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.download_outlined),
              onSelected: _export,
              itemBuilder: (context) => exports
                  .map((item) => PopupMenuItem<String>(
                        value: item.type,
                        child: Row(
                          children: [
                            Icon(item.icon, size: 20),
                            const SizedBox(width: 10),
                            Expanded(child: Text(item.label)),
                          ],
                        ),
                      ))
                  .toList(),
            ),
          if (widget.onContinue != null)
            IconButton(
              onPressed: widget.onContinue,
              tooltip: '调整文档和要求',
              icon: const Icon(Icons.edit_note_rounded),
            ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(18, 16, 18, 36),
          children: [
            Text(
              widget.artifact.title,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 5),
            Text(
              'v${widget.artifact.versionNumber} · ${_modeLabel(_content['detectedMode']?.toString())} · ${_formatDate(widget.artifact.createdAt)}',
              style: const TextStyle(color: AppColors.muted, fontSize: 11),
            ),
            if (_error != null) ...[
              const SizedBox(height: 14),
              _Notice(
                text: _error!,
                color: AppColors.danger,
                icon: Icons.error_outline_rounded,
              ),
            ],
            if (_warnings.isNotEmpty) ...[
              const SizedBox(height: 14),
              _Notice(
                text: _warnings.join('\n'),
                color: const Color(0xFF8A5A00),
                icon: Icons.info_outline_rounded,
              ),
            ],
            if (hasComparability) ...[
              const SizedBox(height: 14),
              _buildComparabilityBanner(_comparability),
            ],
            const SizedBox(height: 24),
            const _SectionTitle(
              icon: Icons.summarize_outlined,
              title: '对比结论',
            ),
            const SizedBox(height: 10),
            SelectableText(
              summary.isEmpty ? '没有可展示的对比结论。' : summary,
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                    height: 1.65,
                  ),
            ),
            if (_pairs.isNotEmpty) ...[
              const SizedBox(height: 30),
              const _SectionTitle(
                icon: Icons.compare_arrows_rounded,
                title: '基准文档逐份差异',
              ),
              const SizedBox(height: 8),
              ..._pairs.map(_buildPair),
            ],
            if (!terminalComparison) ...[
              const SizedBox(height: 30),
              const _SectionTitle(
                icon: Icons.hub_outlined,
                title: '多文档综合结论',
              ),
              const SizedBox(height: 8),
              if (findings.isEmpty)
                const _EmptyResult(text: '没有识别到有充分证据支持的综合差异。')
              else
                ...findings.map(_buildFinding),
              const SizedBox(height: 30),
              const _SectionTitle(
                icon: Icons.gpp_maybe_outlined,
                title: '风险清单',
              ),
              const SizedBox(height: 8),
              if (_risks.isEmpty)
                const _EmptyResult(text: '没有识别到有充分证据支持的明确风险。')
              else
                ..._risks.map(_buildRisk),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildComparabilityBanner(Map<String, dynamic> comparability) {
    final status = _comparabilityStatus(comparability);
    final reason = comparability['reason']?.toString().trim() ?? '';
    final sharedTopics = _stringList(comparability['sharedTopics']);
    final color = _comparabilityColor(status);
    final icon = switch (status) {
      'IDENTICAL' => Icons.done_all_rounded,
      'PARTIALLY_COMPARABLE' => Icons.call_split_rounded,
      'NOT_COMPARABLE' => Icons.link_off_rounded,
      _ => Icons.compare_arrows_rounded,
    };
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: color.withOpacity(0.08),
        border: Border.all(color: color.withOpacity(0.35)),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 20, color: color),
              const SizedBox(width: 8),
              Text(
                _comparabilityLabel(status),
                style: TextStyle(
                  color: color,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          if (reason.isNotEmpty) ...[
            const SizedBox(height: 8),
            SelectableText(
              reason,
              style: const TextStyle(height: 1.5),
            ),
          ],
          if (sharedTopics.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              '共同主题：${sharedTopics.join('、')}',
              style: const TextStyle(
                color: AppColors.muted,
                fontSize: 12,
                height: 1.45,
              ),
            ),
          ],
          _citationChips(_stringList(comparability['citationMarkers'])),
        ],
      ),
    );
  }

  Widget _buildPair(Map<String, dynamic> pair) {
    final differences = _mapList(pair['differences']);
    final comparability = _map(pair['comparability']);
    final hasComparability = comparability.isNotEmpty;
    final status = hasComparability ? _comparabilityStatus(comparability) : '';
    final reason = comparability['reason']?.toString().trim() ?? '';
    final sharedTopics = _stringList(comparability['sharedTopics']);
    final terminalComparison =
        hasComparability && _isTerminalComparability(status);
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(8),
      ),
      child: ExpansionTile(
        tilePadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 2),
        childrenPadding: const EdgeInsets.fromLTRB(14, 0, 14, 14),
        title: Text(
          pair['comparisonFileName']?.toString() ?? '对比文档',
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
        subtitle: Text(
          hasComparability
              ? '${_comparabilityLabel(status)} · ${pair['summary']?.toString() ?? ''}'
              : pair['summary']?.toString() ?? '',
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        children: [
          if (hasComparability)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: _comparabilityColor(status).withOpacity(0.08),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _comparabilityLabel(status),
                    style: TextStyle(
                      color: _comparabilityColor(status),
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  if (reason.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text(reason, style: const TextStyle(height: 1.45)),
                  ],
                  if (sharedTopics.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text(
                      '共同主题：${sharedTopics.join('、')}',
                      style: const TextStyle(
                        color: AppColors.muted,
                        fontSize: 12,
                      ),
                    ),
                  ],
                  _citationChips(
                    _stringList(comparability['citationMarkers']),
                  ),
                ],
              ),
            ),
          if (!terminalComparison) ...[
            if (differences.isEmpty)
              const Padding(
                padding: EdgeInsets.only(top: 10),
                child: _EmptyResult(text: '该文档没有识别到明确差异。'),
              )
            else
              ...differences.map(_buildDifference),
          ],
        ],
      ),
    );
  }

  Widget _buildDifference(Map<String, dynamic> difference) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.wash,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(
                difference['topic']?.toString() ?? '未命名差异',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
              _ChangeBadge(
                type: difference['changeType']?.toString() ?? 'uncertain',
              ),
            ],
          ),
          const SizedBox(height: 12),
          _LabeledText(
            label: '基准内容',
            text: difference['baselineContent']?.toString() ?? '',
          ),
          const SizedBox(height: 9),
          _LabeledText(
            label: '对比内容',
            text: difference['comparisonContent']?.toString() ?? '',
          ),
          const SizedBox(height: 9),
          _LabeledText(
            label: '影响',
            text: difference['impact']?.toString() ?? '',
          ),
          _citationChips(_stringList(difference['citationMarkers'])),
        ],
      ),
    );
  }

  Widget _buildFinding(Map<String, dynamic> finding) {
    final statements = _mapList(finding['documentStatements']);
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.line),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            finding['topic']?.toString() ?? '综合差异',
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          if (statements.isNotEmpty) ...[
            const SizedBox(height: 11),
            ...statements.map((statement) => Padding(
                  padding: const EdgeInsets.only(bottom: 7),
                  child: _LabeledText(
                    label: statement['fileName']?.toString() ?? '文档',
                    text: statement['content']?.toString() ?? '',
                  ),
                )),
          ],
          const SizedBox(height: 5),
          _LabeledText(
            label: '共同点',
            text: finding['commonality']?.toString() ?? '',
          ),
          const SizedBox(height: 8),
          _LabeledText(
            label: '主要差异',
            text: finding['difference']?.toString() ?? '',
          ),
          const SizedBox(height: 8),
          _LabeledText(
            label: '影响',
            text: finding['impact']?.toString() ?? '',
          ),
          _citationChips(_stringList(finding['citationMarkers'])),
        ],
      ),
    );
  }

  Widget _buildRisk(Map<String, dynamic> risk) {
    final severity = risk['severity']?.toString() ?? 'LOW';
    final color = switch (severity) {
      'HIGH' => AppColors.danger,
      'MEDIUM' => const Color(0xFF9A6200),
      _ => const Color(0xFF29705A),
    };
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        border: Border(left: BorderSide(color: color, width: 4)),
        color: AppColors.wash,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
                decoration: BoxDecoration(
                  color: color.withOpacity(0.12),
                  borderRadius: BorderRadius.circular(5),
                ),
                child: Text(
                  _severityLabel(severity),
                  style: TextStyle(
                    color: color,
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              const SizedBox(width: 9),
              Expanded(
                child: Text(
                  risk['title']?.toString() ?? '风险',
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
              ),
            ],
          ),
          const SizedBox(height: 11),
          _LabeledText(
            label: '依据',
            text: risk['basis']?.toString() ?? '',
          ),
          const SizedBox(height: 8),
          _LabeledText(
            label: '建议',
            text: risk['recommendation']?.toString() ?? '',
          ),
          _citationChips(_stringList(risk['citationMarkers'])),
        ],
      ),
    );
  }

  Widget _citationChips(List<String> markers) {
    final available = markers
        .map((marker) => _citations[marker])
        .whereType<_ComparisonCitation>();
    if (available.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 11),
      child: Wrap(
        spacing: 7,
        runSpacing: 7,
        children: available
            .map((citation) => ActionChip(
                  avatar: const Icon(Icons.description_outlined, size: 16),
                  label: Text(
                    '[${citation.marker}] ${citation.fileName}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  onPressed: () => _openCitation(citation),
                ))
            .toList(),
      ),
    );
  }

  Future<void> _openCitation(_ComparisonCitation citation) async {
    final asset = _assetForCitation(citation.assetId);
    if (asset == null) {
      setState(() => _error = '无法读取该来源文件，文件可能已经删除。');
      return;
    }
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => DocumentSourcePage(
          api: widget.data.api,
          asset: asset,
          marker: citation.marker,
          excerpt: citation.excerpt,
          locator: citation.locator,
        ),
      ),
    );
  }

  AssetView? _assetForCitation(String assetId) {
    for (final relation in _taskDetail?.taskAssets ?? const <TaskAssetView>[]) {
      if (relation.asset.id == assetId) return relation.asset;
    }
    for (final run in _taskDetail?.runs ?? const <RunView>[]) {
      for (final asset in run.inputAssets) {
        if (asset.id == assetId) return asset;
      }
    }
    for (final asset in widget.data.assets) {
      if (asset.id == assetId) return asset;
    }
    return null;
  }

  List<_ExportItem> _availableExports() {
    final configured = _mapList(_content['exportOptions'])
        .map((option) {
          final type = option['type']?.toString().trim() ?? '';
          final label = option['label']?.toString().trim() ?? '';
          if (type.isEmpty || label.isEmpty) return null;
          return _ExportItem(
            type: type,
            label: label,
            icon: _exportIcon(type),
          );
        })
        .whereType<_ExportItem>()
        .toList();
    if (configured.isNotEmpty) return configured;

    final result = <_ExportItem>[];
    final excelId = _content['excelAssetId']?.toString();
    final annotatedId = _content['annotatedBaselineAssetId']?.toString();
    if (excelId != null && excelId.isNotEmpty) {
      result.add(_ExportItem(
        type: 'excel',
        label: '导出 Excel 报告',
        icon: Icons.table_view_outlined,
      ));
    }
    if (annotatedId != null && annotatedId.isNotEmpty) {
      result.add(_ExportItem(
        type: 'annotatedBaseline',
        label: '导出基准文档标注版',
        icon: Icons.rate_review_outlined,
      ));
    }
    return result;
  }

  Future<void> _copyReport() async {
    await Clipboard.setData(
      ClipboardData(text: _content['reportMarkdown']?.toString() ?? ''),
    );
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('对比报告已复制')),
    );
  }

  Future<void> _export(String exportType) async {
    final item = _availableExports()
        .where((value) => value.type == exportType)
        .firstOrNull;
    if (item == null) return;
    setState(() {
      _exporting = true;
      _error = null;
    });
    try {
      final asset = await widget.data.api.exportArtifact(
        widget.artifact.id,
        item.type,
      );
      final bytes = await widget.data.api.downloadAssetContent(asset.id);
      await NativeFilePicker.save(
        fileName: asset.name,
        mediaType: asset.mediaType,
        bytes: bytes,
      );
    } catch (exception) {
      if (mounted) setState(() => _error = '$exception');
    } finally {
      if (mounted) setState(() => _exporting = false);
    }
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.icon, required this.title});

  final IconData icon;
  final String title;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Icon(icon, size: 20, color: AppColors.accent),
          const SizedBox(width: 8),
          Text(
            title,
            style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
          ),
        ],
      );
}

class _LabeledText extends StatelessWidget {
  const _LabeledText({required this.label, required this.text});

  final String label;
  final String text;

  @override
  Widget build(BuildContext context) => Text.rich(
        TextSpan(
          children: [
            TextSpan(
              text: '$label：',
              style: const TextStyle(fontWeight: FontWeight.w700),
            ),
            TextSpan(text: text.isEmpty ? '未发现对应内容' : text),
          ],
        ),
        style: Theme.of(context).textTheme.bodyMedium?.copyWith(height: 1.55),
      );
}

class _ChangeBadge extends StatelessWidget {
  const _ChangeBadge({required this.type});

  final String type;

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (type) {
      'added' => ('新增', const Color(0xFF29705A)),
      'deleted' => ('删除', AppColors.danger),
      'modified' => ('修改', const Color(0xFF8A5A00)),
      'same' => ('一致', AppColors.muted),
      _ => ('待确认', AppColors.muted),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(5),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({
    required this.text,
    required this.color,
    required this.icon,
  });

  final String text;
  final Color color;
  final IconData icon;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: color.withOpacity(0.08),
          border: Border.all(color: color.withOpacity(0.35)),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 18, color: color),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                text,
                style: TextStyle(color: color, fontSize: 12, height: 1.45),
              ),
            ),
          ],
        ),
      );
}

class _EmptyResult extends StatelessWidget {
  const _EmptyResult({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppColors.wash,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Text(
          text,
          style: const TextStyle(color: AppColors.muted),
        ),
      );
}

class _ComparisonCitation {
  const _ComparisonCitation({
    required this.marker,
    required this.assetId,
    required this.fileName,
    required this.excerpt,
    required this.locator,
  });

  factory _ComparisonCitation.fromMap(Map<String, dynamic> map) =>
      _ComparisonCitation(
        marker: map['marker']?.toString() ?? '',
        assetId: map['assetId']?.toString() ?? '',
        fileName: map['fileName']?.toString() ?? '',
        excerpt: map['excerpt']?.toString() ?? '',
        locator: _map(map['locator']),
      );

  final String marker;
  final String assetId;
  final String fileName;
  final String excerpt;
  final Map<String, dynamic> locator;
}

class _ExportItem {
  const _ExportItem({
    required this.type,
    required this.label,
    required this.icon,
  });

  final String type;
  final String label;
  final IconData icon;
}

Map<String, dynamic> _map(Object? value) =>
    value is Map ? Map<String, dynamic>.from(value) : <String, dynamic>{};

List<Map<String, dynamic>> _mapList(Object? value) => value is List
    ? value
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .toList()
    : <Map<String, dynamic>>[];

List<String> _stringList(Object? value) =>
    value is List ? value.map((item) => item.toString()).toList() : const [];

String _severityLabel(String value) => switch (value) {
      'HIGH' => '高风险',
      'MEDIUM' => '中风险',
      _ => '低风险',
    };

String _comparabilityStatus(Map<String, dynamic> value) {
  final status = value['status']?.toString().toUpperCase();
  return switch (status) {
    'IDENTICAL' => 'IDENTICAL',
    'PARTIALLY_COMPARABLE' => 'PARTIALLY_COMPARABLE',
    'NOT_COMPARABLE' => 'NOT_COMPARABLE',
    _ => 'COMPARABLE',
  };
}

bool _isTerminalComparability(String value) =>
    value == 'IDENTICAL' || value == 'NOT_COMPARABLE';

String _comparabilityLabel(String value) => switch (value) {
      'IDENTICAL' => '完全相同',
      'PARTIALLY_COMPARABLE' => '部分可比',
      'NOT_COMPARABLE' => '不可比',
      _ => '可比',
    };

Color _comparabilityColor(String value) => switch (value) {
      'IDENTICAL' => const Color(0xFF29705A),
      'PARTIALLY_COMPARABLE' => const Color(0xFF8A5A00),
      'NOT_COMPARABLE' => AppColors.danger,
      _ => AppColors.accent,
    };

IconData _exportIcon(String value) => switch (value) {
      'annotatedBaseline' => Icons.rate_review_outlined,
      _ => Icons.table_view_outlined,
    };

String _modeLabel(String? value) => switch (value) {
      'contract' => '合同对比',
      'policy' => '制度对比',
      'version' => '版本对比',
      _ => '通用对比',
    };

String _formatDate(DateTime value) =>
    '${value.year}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';
