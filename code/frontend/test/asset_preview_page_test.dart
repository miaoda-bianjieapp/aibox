import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/network/backend_api.dart';
import 'package:yuanzuo_ai/app/pages/asset_preview_page.dart';

void main() {
  testWidgets('opens the original asset from the preview app bar',
      (tester) async {
    final directory = (await tester.runAsync(
      () => Directory.systemTemp.createTemp('asset-preview-test-'),
    ))!;
    final file = File('${directory.path}/report.docx');
    await tester.runAsync(() => file.writeAsBytes([1, 2, 3]));
    addTearDown(() async {
      if (await directory.exists()) {
        await directory.delete(recursive: true);
      }
    });
    AssetView? openedAsset;
    File? openedFile;

    await tester.pumpWidget(
      MaterialApp(
        home: AssetPreviewPage(
          api: BackendApi.instance,
          asset: _asset,
          previewLoader: (_) async => _textPreview,
          externalFileDownloader: (_) async => file,
          externalFileOpener: (file, asset) async {
            openedFile = file;
            openedAsset = asset;
          },
          externalFileCleaner: (_) async {},
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byTooltip('使用其他应用打开'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey<String>('asset-preview-open-external')),
    );
    await tester.pumpAndSettle();

    expect(openedFile?.path, file.path);
    expect(openedAsset?.id, _asset.id);
  });
}

final _asset = AssetView(
  id: 'asset-1',
  name: 'report.docx',
  mediaType:
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  sizeBytes: 3,
  createdAt: DateTime(2026, 7, 30),
);

const _textPreview = AssetPreviewDescriptor(
  kind: 'TEXT',
  mediaType: 'text/plain',
  contentUrl: null,
  text: 'preview',
  truncated: false,
  fallback: false,
  spreadsheet: null,
);
