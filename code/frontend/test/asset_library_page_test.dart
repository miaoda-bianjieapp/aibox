import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/pages/asset_library_page.dart';
import 'package:yuanzuo_ai/app/state/app_data_controller.dart';
import 'package:yuanzuo_ai/app/theme/app_theme.dart';

void main() {
  test('inverts against the selection snapshot', () {
    final selected = <String>{'asset-a', 'asset-c'};

    final inverted = invertAssetSelection(
      <String>{'asset-a', 'asset-b', 'asset-c'},
      selected,
    );

    expect(inverted, <String>{'asset-b'});
    expect(selected, <String>{'asset-a', 'asset-c'});
  });

  test('validates the download count and total size independently', () {
    final tooMany = List<AssetView>.generate(
      assetDownloadMaxCount + 1,
      (index) => _asset('asset-$index'),
    );
    expect(
      validateAssetDownloadSelection(tooMany),
      '单次最多下载 30 个资产',
    );

    final tooLarge = [
      _asset('large-a', sizeBytes: 3 * 1024 * 1024 * 1024),
      _asset('large-b', sizeBytes: 3 * 1024 * 1024 * 1024),
    ];
    expect(
      validateAssetDownloadSelection(tooLarge),
      '所选资产总大小不能超过 5GB',
    );
  });

  testWidgets('shows the download action only for selected model assets',
      (tester) async {
    final assets = [_asset('asset-a'), _asset('asset-b')];

    await tester.pumpWidget(_app(_page(assets)));
    await tester.pumpAndSettle();

    await tester.longPress(
      find.byKey(const ValueKey<String>('asset-row-asset-a')),
    );
    await tester.pump();
    expect(find.byTooltip('下载所选资产'), findsNothing);

    await tester.tap(find.byTooltip('退出选择'));
    await tester.pump();
    await tester.tap(find.text('我的资产'));
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byKey(const ValueKey<String>('asset-row-asset-a')),
    );
    await tester.pump();

    expect(find.byTooltip('下载所选资产'), findsOneWidget);
  });

  testWidgets('invert selects the complement instead of reselecting all',
      (tester) async {
    final assets = [_asset('asset-a'), _asset('asset-b'), _asset('asset-c')];

    await tester.pumpWidget(_app(_page(assets)));
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byKey(const ValueKey<String>('asset-row-asset-a')),
    );
    await tester.pump();
    await tester.tap(find.text('反选'));
    await tester.pumpAndSettle();

    expect(find.text('已选择 2 项'), findsOneWidget);
    expect(_selectionIcon(tester, 'asset-a'),
        Icons.radio_button_unchecked_rounded);
    expect(_selectionIcon(tester, 'asset-b'), Icons.check_circle_rounded);
    expect(_selectionIcon(tester, 'asset-c'), Icons.check_circle_rounded);
  });

  testWidgets('keeps only failed downloads selected', (tester) async {
    final assets = [_asset('asset-a'), _asset('asset-b')];
    final failedSaveAttempted = Completer<void>();

    await tester.pumpWidget(_app(_page(
      assets,
      directoryPicker: () async => 'content://download-folder',
      temporaryDownloader: (asset, isCancelled) async {
        return File(
          '${Directory.systemTemp.path}/'
          'asset-download-test-${asset.id}/${asset.name}',
        );
      },
      directorySaver: ({
        required directoryUri,
        required source,
        required asset,
      }) async {
        if (asset.id == 'asset-b') {
          failedSaveAttempted.complete();
          throw const FileSystemException('disk error');
        }
        return asset.name;
      },
      temporaryFileCleaner: (_) async {},
    )));
    await tester.pumpAndSettle();
    await tester.tap(find.text('我的资产'));
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byKey(const ValueKey<String>('asset-row-asset-a')),
    );
    await tester.pump();
    await tester.tap(
      find.byKey(const ValueKey<String>('asset-selection-asset-b')),
    );
    await tester.pump();
    expect(find.text('已选择 2 项'), findsOneWidget);
    await tester.tap(find.byTooltip('下载所选资产'));
    await tester.runAsync(() async {
      await failedSaveAttempted.future.timeout(const Duration(seconds: 2));
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pumpAndSettle();

    expect(find.text('已选择 1 项'), findsOneWidget);
    expect(_selectionIcon(tester, 'asset-a'),
        Icons.radio_button_unchecked_rounded);
    expect(_selectionIcon(tester, 'asset-b'), Icons.check_circle_rounded);
    expect(find.text('已保存 1 个，1 个失败，失败项已保留'), findsOneWidget);
  });
}

Widget _page(
  List<AssetView> assets, {
  AssetDirectoryPicker? directoryPicker,
  AssetTemporaryDownloader? temporaryDownloader,
  AssetDirectorySaver? directorySaver,
  AssetTemporaryFileCleaner? temporaryFileCleaner,
}) {
  return AssetLibraryPage(
    data: AppDataController(),
    assetLoader: ({
      required libraryType,
      required category,
      required query,
      cursor,
      pageSize = 20,
    }) async =>
        AssetPage(items: assets, nextCursor: null),
    directoryPicker: directoryPicker,
    temporaryDownloader: temporaryDownloader,
    directorySaver: directorySaver,
    temporaryFileCleaner: temporaryFileCleaner,
  );
}

Widget _app(Widget child) {
  return MaterialApp(
    theme: AppTheme.light,
    home: child,
  );
}

AssetView _asset(
  String id, {
  int sizeBytes = 1024,
}) {
  return AssetView(
    id: id,
    name: '$id.pdf',
    mediaType: 'application/pdf',
    sizeBytes: sizeBytes,
    createdAt: DateTime(2026, 7, 29, 10),
    origin: 'MODEL_OUTPUT',
    category: 'DOCUMENT',
  );
}

IconData? _selectionIcon(WidgetTester tester, String assetId) {
  return tester
      .widget<Icon>(
        find.byKey(ValueKey<String>('asset-selection-$assetId')),
      )
      .icon;
}
