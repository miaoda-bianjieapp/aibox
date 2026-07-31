import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/widgets/spreadsheet_preview_view.dart';

void main() {
  testWidgets('renders cells and switches between workbook sheets',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SpreadsheetPreviewView(
            preview: _preview(),
            initialSheetName: '库存',
            initialRow: 8,
            endRow: 8,
          ),
        ),
      ),
    );

    expect(find.text('SKU-1'), findsOneWidget);
    expect(find.text('120'), findsOneWidget);
    expect(find.text('华东'), findsNothing);

    await tester.tap(find.text('销售'));
    await tester.pumpAndSettle();

    expect(find.text('华东'), findsOneWidget);
    expect(find.text('42'), findsOneWidget);
  });

  testWidgets('switches between table and layout preview', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SpreadsheetPreviewView(
            preview: _preview(),
            layoutPreview: const Center(child: Text('PDF layout')),
          ),
        ),
      ),
    );

    expect(find.text('华东'), findsOneWidget);
    expect(find.text('PDF layout'), findsNothing);

    await tester.tap(find.text('版式'));
    await tester.pumpAndSettle();

    expect(find.text('PDF layout'), findsOneWidget);
  });

  testWidgets('keeps a wide spreadsheet scrollable on a phone viewport',
      (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SpreadsheetPreviewView(preview: _widePreview()),
        ),
      ),
    );

    expect(find.text('列6'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('zooms the spreadsheet with controls and supports pinch scaling',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SpreadsheetPreviewView(preview: _widePreview()),
        ),
      ),
    );

    expect(find.text('100%'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey<String>('spreadsheet-zoom-in')),
    );
    await tester.pump();
    expect(find.text('125%'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey<String>('spreadsheet-zoom-reset')),
    );
    await tester.pump();
    expect(find.text('100%'), findsOneWidget);

    final viewer = tester.widget<InteractiveViewer>(
      find.byKey(const ValueKey<String>('spreadsheet-zoom-surface')),
    );
    expect(viewer.scaleEnabled, isTrue);
    expect(viewer.minScale, 0.5);
    expect(viewer.maxScale, 3);
  });
}

SpreadsheetPreviewData _preview() => const SpreadsheetPreviewData(
      truncated: false,
      sheets: [
        SpreadsheetSheetPreview(
          name: '销售',
          headerRowNumber: 2,
          columns: ['地区', '销售额'],
          rows: [
            SpreadsheetRowPreview(
              rowNumber: 4,
              cells: ['华东', '42'],
            ),
          ],
          truncated: false,
        ),
        SpreadsheetSheetPreview(
          name: '库存',
          headerRowNumber: 1,
          columns: ['商品', '数量'],
          rows: [
            SpreadsheetRowPreview(
              rowNumber: 8,
              cells: ['SKU-1', '120'],
            ),
          ],
          truncated: false,
        ),
      ],
    );

SpreadsheetPreviewData _widePreview() => const SpreadsheetPreviewData(
      truncated: true,
      sheets: [
        SpreadsheetSheetPreview(
          name: '宽表',
          headerRowNumber: 1,
          columns: ['列1', '列2', '列3', '列4', '列5', '列6'],
          rows: [
            SpreadsheetRowPreview(
              rowNumber: 2,
              cells: ['A', 'B', 'C', 'D', 'E', 'F'],
            ),
          ],
          truncated: true,
        ),
      ],
    );
