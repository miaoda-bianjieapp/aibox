import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/network/native_file_picker.dart';
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
    expect(
      find.byKey(const ValueKey<String>('asset-download-selected')),
      findsNothing,
    );

    await tester.tap(find.byTooltip('退出选择'));
    await tester.pump();
    await tester.tap(find.text('我的资产'));
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byKey(const ValueKey<String>('asset-row-asset-a')),
    );
    await tester.pump();

    expect(
      find.byKey(const ValueKey<String>('asset-download-selected')),
      findsOneWidget,
    );
    expect(find.text('下载（1）'), findsOneWidget);
    expect(find.text('删除（1）'), findsNothing);
    expect(
      find.byKey(const ValueKey<String>('asset-selection-more')),
      findsOneWidget,
    );
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
    final temporaryDirectory = await tester.runAsync(
      () => Directory.systemTemp.createTemp('asset-library-test-'),
    );
    addTearDown(() async {
      if (await temporaryDirectory!.exists()) {
        await temporaryDirectory.delete(recursive: true);
      }
    });
    await tester.runAsync(() async {
      for (final asset in assets) {
        await File('${temporaryDirectory!.path}/${asset.id}')
            .writeAsBytes(List<int>.filled(asset.sizeBytes, 0));
      }
    });

    await tester.pumpWidget(_app(_page(
      assets,
      directoryPicker: () async => 'content://download-folder',
      temporaryDownloader: (asset, isCancelled) async {
        return File('${temporaryDirectory!.path}/${asset.id}');
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
        return SavedLocalFile(
          name: asset.name,
          uri: 'content://download-folder/${asset.id}',
          sizeBytes: asset.sizeBytes,
        );
      },
      fileSaver: ({required source, required asset}) async => null,
      temporaryFileCleaner: (_) async {},
      temporaryFileSizeReader: (_) async => assetDownloadTestFileSize,
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
    await tester.tap(
      find.byKey(const ValueKey<String>('asset-download-selected')),
    );
    await tester.runAsync(() async {
      await failedSaveAttempted.future.timeout(const Duration(seconds: 2));
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pumpAndSettle();

    expect(find.text('已选择 1 项'), findsOneWidget);
    expect(_selectionIcon(tester, 'asset-a'),
        Icons.radio_button_unchecked_rounded);
    expect(_selectionIcon(tester, 'asset-b'), Icons.check_circle_rounded);
    expect(find.text('下载未全部完成'), findsOneWidget);
    expect(find.text('已取消系统保存'), findsOneWidget);
  });

  testWidgets('falls back to single-file save when directory writing fails',
      (tester) async {
    final asset = _asset('asset-a');
    final fallbackCompleted = Completer<void>();
    final temporaryDirectory = await tester.runAsync(
      () => Directory.systemTemp.createTemp('asset-fallback-test-'),
    );
    addTearDown(() async {
      if (await temporaryDirectory!.exists()) {
        await temporaryDirectory.delete(recursive: true);
      }
    });
    final temporaryFile = File('${temporaryDirectory!.path}/${asset.id}');
    await tester.runAsync(
      () => temporaryFile.writeAsBytes(List<int>.filled(asset.sizeBytes, 0)),
    );

    await tester.pumpWidget(_app(_page(
      [asset],
      directoryPicker: () async => 'content://download-folder',
      temporaryDownloader: (_, __) async => temporaryFile,
      directorySaver: ({
        required directoryUri,
        required source,
        required asset,
      }) async {
        throw const FileSystemException('directory provider rejected write');
      },
      fileSaver: ({required source, required asset}) async {
        fallbackCompleted.complete();
        return SavedLocalFile(
          name: asset.name,
          uri: 'content://picked/${asset.id}',
          sizeBytes: asset.sizeBytes,
        );
      },
      temporaryFileCleaner: (_) async {},
      temporaryFileSizeReader: (_) async => asset.sizeBytes,
    )));
    await tester.pumpAndSettle();
    await tester.tap(find.text('我的资产'));
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byKey(const ValueKey<String>('asset-row-asset-a')),
    );
    await tester.pump();
    await tester.tap(
      find.byKey(const ValueKey<String>('asset-download-selected')),
    );
    await tester.runAsync(() async {
      await fallbackCompleted.future.timeout(const Duration(seconds: 2));
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pumpAndSettle();

    expect(find.text('附件库'), findsOneWidget);
    expect(
      find.text('已保存 1 个资产，设备目录写入不兼容，已改用系统保存'),
      findsOneWidget,
    );
  });

  testWidgets('keeps one immutable delete snapshot while impact is pending',
      (tester) async {
    final assets = [_asset('asset-a'), _asset('asset-b')];
    final impactRequested = Completer<void>();
    final impactResponse = Completer<AssetDeleteImpact>();
    Set<String>? impactIds;
    Set<String>? deletedIds;

    await tester.pumpWidget(_app(_page(
      assets,
      deleteImpactLoader: (assetIds) {
        impactIds = assetIds;
        impactRequested.complete();
        return impactResponse.future;
      },
      assetDeleter: (assetIds) async {
        deletedIds = assetIds;
      },
    )));
    await tester.pumpAndSettle();
    await tester.longPress(
      find.byKey(const ValueKey<String>('asset-row-asset-a')),
    );
    await tester.pump();
    await tester.tap(find.text('删除（1）'));
    await tester.runAsync(
      () => impactRequested.future.timeout(const Duration(seconds: 2)),
    );
    await tester.pump();

    expect(
      tester
          .widget<IconButton>(
            find.widgetWithIcon(IconButton, Icons.close_rounded),
          )
          .onPressed,
      isNull,
    );
    await tester.tap(
      find.byKey(const ValueKey<String>('asset-row-asset-b')),
    );
    await tester.pump();
    expect(find.text('已选择 1 项'), findsOneWidget);
    expect(_selectionIcon(tester, 'asset-a'), Icons.check_circle_rounded);
    expect(_selectionIcon(tester, 'asset-b'),
        Icons.radio_button_unchecked_rounded);

    impactResponse.complete(const AssetDeleteImpact(
      assetCount: 1,
      totalBytes: 1024,
      affectedTaskCount: 0,
      affectedRunCount: 0,
    ));
    await tester.pump();
    expect(find.text('• asset-a.pdf'), findsOneWidget);
    expect(find.text('• asset-b.pdf'), findsNothing);
    for (var second = 0; second < 3; second++) {
      await tester.pump(const Duration(seconds: 1));
    }
    await tester.tap(find.text('永久删除'));
    await tester.pumpAndSettle();

    expect(impactIds, <String>{'asset-a'});
    expect(deletedIds, <String>{'asset-a'});
    expect(identical(impactIds, deletedIds), isTrue);
  });
}

Widget _page(
  List<AssetView> assets, {
  AssetDirectoryPicker? directoryPicker,
  AssetTemporaryDownloader? temporaryDownloader,
  AssetDirectorySaver? directorySaver,
  AssetFileSaver? fileSaver,
  AssetTemporaryFileCleaner? temporaryFileCleaner,
  AssetTemporaryFileSizeReader? temporaryFileSizeReader,
  AssetDeleteImpactLoader? deleteImpactLoader,
  AssetBatchDeleter? assetDeleter,
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
    fileSaver: fileSaver,
    temporaryFileCleaner: temporaryFileCleaner,
    temporaryFileSizeReader: temporaryFileSizeReader,
    deleteImpactLoader: deleteImpactLoader,
    assetDeleter: assetDeleter,
  );
}

const int assetDownloadTestFileSize = 1024;

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
