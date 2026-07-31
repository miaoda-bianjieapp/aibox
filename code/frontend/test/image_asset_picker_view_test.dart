import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/widgets/image_asset_picker_view.dart';

void main() {
  testWidgets('uses the system album as the primary empty-state action',
      (tester) async {
    var galleryRequests = 0;
    var libraryRequests = 0;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ImageAssetPickerView(
            assets: const [],
            maxItems: 3,
            uploading: false,
            enabled: true,
            contentUrlFor: (_) => 'https://example.invalid/image',
            onPickImages: () => galleryRequests++,
            onChooseLibrary: () => libraryRequests++,
            onRemove: (_) {},
          ),
        ),
      ),
    );

    expect(find.text('从相册选择图片'), findsOneWidget);
    expect(find.text('最多 3 张'), findsOneWidget);
    expect(find.text('我的文件'), findsOneWidget);
    expect(find.byIcon(Icons.photo_library_outlined), findsNWidgets(2));

    await tester.tap(find.text('从相册选择图片'));
    await tester.tap(find.text('我的文件'));

    expect(galleryRequests, 1);
    expect(libraryRequests, 1);
  });

  testWidgets('shows a large preview and replace action for one image',
      (tester) async {
    var galleryRequests = 0;
    String? removedId;

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 360,
            child: ImageAssetPickerView(
              assets: [_asset('asset-1')],
              maxItems: 1,
              uploading: false,
              enabled: true,
              contentUrlFor: (_) => 'https://example.invalid/image',
              onPickImages: () => galleryRequests++,
              onChooseLibrary: () {},
              onRemove: (asset) => removedId = asset.id,
            ),
          ),
        ),
      ),
    );

    expect(
      find.byKey(const ValueKey<String>('image-picker-single-preview')),
      findsOneWidget,
    );
    expect(find.text('更换图片'), findsOneWidget);

    await tester.tap(find.text('更换图片'));
    await tester.tap(
      find.byKey(const ValueKey<String>('image-picker-remove-asset-1')),
    );

    expect(galleryRequests, 1);
    expect(removedId, 'asset-1');
  });

  testWidgets('shows responsive thumbnails and remaining capacity',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 360,
            child: ImageAssetPickerView(
              assets: [_asset('asset-1'), _asset('asset-2')],
              maxItems: 3,
              uploading: false,
              enabled: true,
              contentUrlFor: (_) => 'https://example.invalid/image',
              onPickImages: () {},
              onChooseLibrary: () {},
              onRemove: (_) {},
            ),
          ),
        ),
      ),
    );

    expect(find.text('已选择 2/3'), findsOneWidget);
    expect(find.text('继续从相册添加'), findsOneWidget);
    expect(
      find.byKey(const ValueKey<String>('image-picker-grid')),
      findsOneWidget,
    );
  });

  testWidgets('adapts the thumbnail grid without overflowing narrow sheets',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 176,
            child: ImageAssetPickerView(
              assets: [_asset('asset-1'), _asset('asset-2')],
              maxItems: 3,
              uploading: false,
              enabled: true,
              contentUrlFor: (_) => 'https://example.invalid/image',
              onPickImages: () {},
              onChooseLibrary: () {},
              onRemove: (_) {},
            ),
          ),
        ),
      ),
    );

    expect(tester.takeException(), isNull);
  });
}

AssetView _asset(String id) => AssetView(
      id: id,
      name: '$id.png',
      mediaType: 'image/png',
      sizeBytes: 1024,
      createdAt: DateTime(2026, 7, 30),
      category: 'IMAGE',
    );
