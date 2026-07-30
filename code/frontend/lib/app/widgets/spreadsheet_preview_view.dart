import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../models/feature_models.dart';
import '../theme/app_theme.dart';

enum _SpreadsheetPreviewMode { table, layout }

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
              key: ValueKey(selectedSheet.name),
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
    super.key,
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
  final GlobalKey _highlightedRowKey = GlobalKey();
  bool _revealScheduled = false;

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
    _scheduleRowReveal();
    return LayoutBuilder(
      builder: (context, constraints) {
        final tableWidth = math
            .max(
              constraints.maxWidth,
              68 + widget.sheet.columns.length * 156,
            )
            .toDouble();
        return SingleChildScrollView(
          child: SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: SizedBox(
              width: tableWidth,
              child: Table(
                border: TableBorder.all(color: AppColors.line),
                columnWidths: {
                  0: const FixedColumnWidth(68),
                  for (int index = 0;
                      index < widget.sheet.columns.length;
                      index++)
                    index + 1: const FixedColumnWidth(156),
                },
                defaultVerticalAlignment: TableCellVerticalAlignment.middle,
                children: [
                  TableRow(
                    decoration: const BoxDecoration(color: Color(0xFFF1F4F2)),
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
          key: highlighted ? _highlightedRowKey : null,
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

  void _scheduleRowReveal() {
    if (_revealScheduled || widget.initialRow == null) return;
    _revealScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final rowContext = _highlightedRowKey.currentContext;
      if (rowContext == null) return;
      Scrollable.ensureVisible(
        rowContext,
        alignment: 0.2,
        duration: const Duration(milliseconds: 280),
        curve: Curves.easeOutCubic,
      );
    });
  }
}

Widget _headerCell(String value) => Container(
      constraints: const BoxConstraints(minHeight: 46),
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
      constraints: const BoxConstraints(minHeight: 48),
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
