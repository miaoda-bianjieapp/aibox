import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/run_output_models.dart';
import 'package:yuanzuo_ai/app/pages/document_qa_page.dart';
import 'package:yuanzuo_ai/app/pages/document_source_page.dart';
import 'package:yuanzuo_ai/app/widgets/streaming_output_view.dart';

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

  testWidgets(
      'temporary document answer settles before rich markdown replaces it',
      (tester) async {
    var phase = StreamingOutputPhase.streaming;
    var settled = false;
    var contentChanges = 0;
    late StateSetter update;
    var snapshot = RunOutputSnapshot(
      runId: 'run-1',
      channel: 'main',
      format: 'markdown',
      content: '**文档答案**',
      status: 'STREAMING',
      lastSequence: 1,
      updatedAt: DateTime(2026, 7, 30),
      updateType: RunOutputUpdateType.append,
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return DocumentQaAnswerView(
                markdown: '',
                streamingSnapshot: snapshot,
                streamingPhase: phase,
                onStreamingSettled: () => settled = true,
                onStreamingContentChanged: () => contentChanges++,
              );
            },
          ),
        ),
      ),
    );

    await tester.pump();
    await _pumpMarkdown(tester, '**文档答案**');
    expect(
      find.byKey(const ValueKey('streaming-output-markdown')),
      findsOneWidget,
    );
    expect(find.text('文档答案'), findsOneWidget);
    expect(contentChanges, greaterThan(0));

    final changesBeforeDelta = contentChanges;
    update(() {
      snapshot = RunOutputSnapshot(
        runId: 'run-1',
        channel: 'main',
        format: 'markdown',
        content: '**文档答案**\n\n追加内容',
        status: 'STREAMING',
        lastSequence: 2,
        updatedAt: DateTime(2026, 7, 30),
        updateType: RunOutputUpdateType.append,
      );
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 35));
    expect(find.text('追加内容'), findsNothing);
    await tester.pump(const Duration(milliseconds: 1));
    expect(find.text('追加内容'), findsOneWidget);
    expect(contentChanges, greaterThan(changesBeforeDelta));

    update(() => phase = StreamingOutputPhase.succeeded);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump();

    expect(settled, isTrue);
    expect(
      find.byKey(const ValueKey('streaming-output-rich-text')),
      findsOneWidget,
    );
    expect(find.text('文档答案'), findsOneWidget);
  });
}

Future<void> _pumpMarkdown(WidgetTester tester, String value) async {
  await tester.pump(
    Duration(milliseconds: value.characters.length * 4 + 32),
  );
  await tester.pump();
}
