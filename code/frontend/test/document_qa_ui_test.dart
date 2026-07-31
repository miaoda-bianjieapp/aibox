import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/pages/document_qa_page.dart';
import 'package:yuanzuo_ai/app/pages/document_source_page.dart';

void main() {
  test('PPT slide locator maps to the converted PDF page', () {
    expect(
      documentSourceInitialPage(const {
        'type': 'PPT_SLIDE',
        'slideNumber': 7,
      }),
      7,
    );
    expect(
      documentSourceInitialPage(const {
        'type': 'PDF_PAGE',
        'pageNumber': 3,
      }),
      3,
    );
  });

  test('Excel row locator maps to the spreadsheet sheet and rows', () {
    const locator = {
      'type': 'EXCEL_ROWS',
      'sheetName': '销售',
      'startRow': 12,
      'endRow': 15,
    };

    expect(documentSourceInitialSheetName(locator), '销售');
    expect(documentSourceInitialRow(locator), 12);
    expect(documentSourceEndRow(locator), 15);
  });

  testWidgets('document sources are collapsed until the user expands them',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: DocumentSourcesDisclosure(
            sourceCount: 2,
            child: Text('source chips'),
          ),
        ),
      ),
    );

    expect(find.text('来源（2）'), findsOneWidget);
    expect(find.text('source chips'), findsNothing);

    await tester.tap(
      find.byKey(const ValueKey<String>('document-sources-toggle')),
    );
    await tester.pumpAndSettle();
    expect(find.text('source chips'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey<String>('document-sources-toggle')),
    );
    await tester.pumpAndSettle();
    expect(find.text('source chips'), findsNothing);
  });
}
