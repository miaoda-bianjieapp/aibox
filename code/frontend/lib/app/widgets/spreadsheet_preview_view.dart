import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../theme/app_theme.dart';

enum _SpreadsheetPreviewMode { table, layout }

const double _spreadsheetHeaderHeight = 48;
const double _spreadsheetRowHeight = 64;

class SpreadsheetPreviewView extends StatefulWidget {
  const SpreadsheetPreviewView({
    super.key,
    required this.preview,
    this.layoutPreview,
    this.initialSheetName,
    this.initialRow,
    this.endRow,
    this.layoutUnavailable = false,
  });

  final SpreadsheetPreviewData preview;
  final Widget? layoutPreview;
  final String? initialSheetName;
  final int? initialRow;
  final int? endRow;
  final bool layoutUnavailable;

  @override
  State<SpreadsheetPreviewView> createState() => _SpreadsheetPreviewViewState();
}

class _SpreadsheetPreviewViewState extends State<SpreadsheetPreviewView>
    with SingleTickerProviderStateMixin {
  late TabController _sheetController;
  late int _selectedSheetIndex;
  _SpreadsheetPreviewMode _mode = _SpreadsheetPreviewMode.table;

  @override
  void initState() {
    super.initState();
    _selectedSheetIndex = _initialSheetIndex();
    _sheetController = _newSheetController(_selectedSheetIndex);
  }

  @override
  void didUpdateWidget(covariant SpreadsheetPreviewView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.preview.sheets.length != widget.preview.sheets.length) {
      _sheetController.dispose();
      _selectedSheetIndex = _initialSheetIndex();
      _sheetController = _newSheetController(_selectedSheetIndex);
    }
  }

  @override
  void dispose() {
    _sheetController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final sheets = widget.preview.sheets;
    if (sheets.isEmpty) {
      return const Center(child: Text('表格没有可展示的数据。'));
    }
    final selectedIndex = _selectedSheetIndex.clamp(0, sheets.length - 1);
    final selectedSheet = sheets[selectedIndex];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (widget.layoutPreview != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
            child: Align(
              alignment: Alignment.centerLeft,
              child: SegmentedButton<_SpreadsheetPreviewMode>(
                showSelectedIcon: false,
                segments: const [
                  ButtonSegment(
                    value: _SpreadsheetPreviewMode.table,
                    icon: Icon(Icons.table_chart_outlined),
                    label: Text('表格'),
                  ),
                  ButtonSegment(
                    value: _SpreadsheetPreviewMode.layout,
                    icon: Icon(Icons.picture_as_pdf_outlined),
                    label: Text('版式'),
                  ),
                ],
                selected: {_mode},
                onSelectionChanged: (selection) {
                  setState(() => _mode = selection.single);
                },
              ),
            ),
          ),
        if (_mode == _SpreadsheetPreviewMode.table) ...[
          if (widget.layoutUnavailable)
            const _SpreadsheetNotice(
              text: '版式预览不可用，当前展示单元格数据；图片和复杂样式可能不完整。',
            ),
          if (widget.preview.truncated || selectedSheet.truncated)
            const _SpreadsheetNotice(
              text: '文件较大，当前仅展示部分工作表、行、列或单元格内容。',
            ),
          if (sheets.length > 1)
            TabBar(
              controller: _sheetController,
              isScrollable: true,
              tabAlignment: TabAlignment.start,
              onTap: (index) => setState(() => _selectedSheetIndex = index),
              tabs: sheets.map((sheet) => Tab(text: sheet.name)).toList(),
            ),
          Expanded(
            child: _SpreadsheetTable(
              sheet: selectedSheet,
              initialRow: _matchesInitialSheet(selectedSheet)
                  ? widget.initialRow
                  : null,
              endRow:
                  _matchesInitialSheet(selectedSheet) ? widget.endRow : null,
            ),
          ),
        ] else
          Expanded(child: widget.layoutPreview!),
      ],
    );
  }

  int _initialSheetIndex() {
    final requested = widget.initialSheetName?.trim();
    if (requested == null || requested.isEmpty) return 0;
    final index = widget.preview.sheets.indexWhere(
      (sheet) => sheet.name.trim().toLowerCase() == requested.toLowerCase(),
    );
    return index < 0 ? 0 : index;
  }

  TabController _newSheetController(int initialIndex) => TabController(
        length: math.max(1, widget.preview.sheets.length),
        initialIndex: initialIndex,
        vsync: this,
      );

  bool _matchesInitialSheet(SpreadsheetSheetPreview sheet) {
    final requested = widget.initialSheetName?.trim();
    return requested == null ||
        requested.isEmpty ||
        requested.toLowerCase() == sheet.name.trim().toLowerCase();
  }
}

class _SpreadsheetTable extends StatefulWidget {
  const _SpreadsheetTable({
    required this.sheet,
    required this.initialRow,
    required this.endRow,
  });

  final SpreadsheetSheetPreview sheet;
  final int? initialRow;
  final int? endRow;

  @override
  State<_SpreadsheetTable> createState() => _SpreadsheetTableState();
}

class _SpreadsheetTableState extends State<_SpreadsheetTable> {
  static const double _minimumScale = 0.5;
  static const double _maximumScale = 3;
  static const double _scaleStep = 0.25;

  final TransformationController _transformationController =
      TransformationController();
  bool _revealScheduled = false;
  double _scale = 1;

  @override
  void dispose() {
    _transformationController.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant _SpreadsheetTable oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.sheet.name != widget.sheet.name ||
        oldWidget.initialRow != widget.initialRow ||
        oldWidget.endRow != widget.endRow) {
      _revealScheduled = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _buildZoomControls(),
        Expanded(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final tableWidth = math
                  .max(
                    constraints.maxWidth,
                    68 + widget.sheet.columns.length * 156,
                  )
                  .toDouble();
              final tableHeight = _spreadsheetHeaderHeight +
                  widget.sheet.rows.length * _spreadsheetRowHeight;
              _scheduleRowReveal(constraints.biggest, tableHeight);
              return ColoredBox(
                color: Colors.white,
                child: InteractiveViewer(
                  key: const ValueKey<String>('spreadsheet-zoom-surface'),
                  transformationController: _transformationController,
                  constrained: false,
                  alignment: Alignment.topLeft,
                  boundaryMargin: const EdgeInsets.all(96),
                  minScale: _minimumScale,
                  maxScale: _maximumScale,
                  scaleEnabled: true,
                  panEnabled: true,
                  onInteractionUpdate: (_) => _syncScale(),
                  onInteractionEnd: (_) => _syncScale(),
                  child: SizedBox(
                    width: tableWidth,
                    height: tableHeight,
                    child: Table(
                      border: TableBorder.all(color: AppColors.line),
                      columnWidths: {
                        0: const FixedColumnWidth(68),
                        for (int index = 0;
                            index < widget.sheet.columns.length;
                            index++)
                          index + 1: const FixedColumnWidth(156),
                      },
                      defaultVerticalAlignment:
                          TableCellVerticalAlignment.middle,
                      children: [
                        TableRow(
                          decoration:
                              const BoxDecoration(color: Color(0xFFF1F4F2)),
                          children: [
                            _headerCell('#'),
                            ...widget.sheet.columns.map(_headerCell),
                          ],
                        ),
                        ...widget.sheet.rows.map(_dataRow),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _buildZoomControls() {
    final percent = '${(_scale * 100).round()}%';
    return SizedBox(
      height: 48,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            IconButton(
              key: const ValueKey<String>('spreadsheet-zoom-out'),
              onPressed: _scale <= _minimumScale
                  ? null
                  : () => _setScale(_scale - _scaleStep),
              tooltip: '缩小表格',
              icon: const Icon(Icons.remove_rounded),
            ),
            SizedBox(
              width: 52,
              child: Text(
                percent,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: AppColors.muted,
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            IconButton(
              key: const ValueKey<String>('spreadsheet-zoom-in'),
              onPressed: _scale >= _maximumScale
                  ? null
                  : () => _setScale(_scale + _scaleStep),
              tooltip: '放大表格',
              icon: const Icon(Icons.add_rounded),
            ),
            IconButton(
              key: const ValueKey<String>('spreadsheet-zoom-reset'),
              onPressed: _scale == 1 ? null : _resetZoom,
              tooltip: '恢复 100%',
              icon: const Icon(Icons.center_focus_strong_outlined),
            ),
          ],
        ),
      ),
    );
  }

  TableRow _dataRow(SpreadsheetRowPreview row) {
    final highlighted = _isHighlighted(row.rowNumber);
    return TableRow(
      decoration: BoxDecoration(
        color: highlighted ? AppColors.accentSoft : Colors.white,
      ),
      children: [
        _dataCell(
          '${row.rowNumber}',
          muted: true,
        ),
        for (int index = 0; index < widget.sheet.columns.length; index++)
          _dataCell(index < row.cells.length ? row.cells[index] : ''),
      ],
    );
  }

  bool _isHighlighted(int rowNumber) {
    final start = widget.initialRow;
    if (start == null) return false;
    final end = widget.endRow ?? start;
    return rowNumber >= start && rowNumber <= end;
  }

  void _scheduleRowReveal(Size viewport, double tableHeight) {
    if (_revealScheduled || widget.initialRow == null) return;
    _revealScheduled = true;
    final rowIndex = widget.sheet.rows.indexWhere(_rowIsHighlighted);
    if (rowIndex < 0) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final rowTop =
          _spreadsheetHeaderHeight + rowIndex * _spreadsheetRowHeight;
      final minimumY =
          math.min(0.0, viewport.height - tableHeight * _scale).toDouble();
      final translatedY =
          (-rowTop * _scale + viewport.height * 0.2).clamp(minimumY, 0.0);
      final matrix = Matrix4.diagonal3Values(_scale, _scale, 1)
        ..setTranslationRaw(0, translatedY.toDouble(), 0);
      _transformationController.value = matrix;
    });
  }

  bool _rowIsHighlighted(SpreadsheetRowPreview row) =>
      _isHighlighted(row.rowNumber);

  void _setScale(double value) {
    final nextScale = value.clamp(_minimumScale, _maximumScale).toDouble();
    final currentScale =
        _transformationController.value.getMaxScaleOnAxis().clamp(
              _minimumScale,
              _maximumScale,
            );
    final ratio = currentScale == 0 ? 1 : nextScale / currentScale;
    final translation = _transformationController.value.getTranslation();
    final matrix = Matrix4.diagonal3Values(nextScale, nextScale, 1)
      ..setTranslationRaw(
        translation.x * ratio,
        translation.y * ratio,
        0,
      );
    _transformationController.value = matrix;
    setState(() => _scale = nextScale);
  }

  void _resetZoom() {
    _transformationController.value = Matrix4.identity();
    setState(() => _scale = 1);
  }

  void _syncScale() {
    final nextScale = _transformationController.value
        .getMaxScaleOnAxis()
        .clamp(_minimumScale, _maximumScale)
        .toDouble();
    if ((nextScale - _scale).abs() < 0.005 || !mounted) return;
    setState(() => _scale = nextScale);
  }
}

Widget _headerCell(String value) => Container(
      height: _spreadsheetHeaderHeight,
      alignment: Alignment.centerLeft,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      child: Text(
        value,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
      ),
    );

Widget _dataCell(
  String value, {
  Key? key,
  bool muted = false,
}) =>
    Container(
      key: key,
      height: _spreadsheetRowHeight,
      alignment: Alignment.centerLeft,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      child: Tooltip(
        message: value,
        child: Text(
          value,
          maxLines: 3,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 12,
            color: muted ? AppColors.muted : null,
          ),
        ),
      ),
    );

class _SpreadsheetNotice extends StatelessWidget {
  const _SpreadsheetNotice({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.fromLTRB(12, 6, 12, 6),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: const Color(0xFFFFF8E8),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Text(text, style: const TextStyle(fontSize: 12)),
      );
}
