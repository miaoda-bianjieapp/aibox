import 'package:flutter/widgets.dart';

class ImageMaskViewportLayout {
  const ImageMaskViewportLayout({
    required this.viewportSize,
    required this.imageSize,
    required this.imageOffset,
    required this.boundaryMargin,
  });

  factory ImageMaskViewportLayout.calculate({
    required Size sourceSize,
    required Size viewportSize,
  }) {
    final imageSize = applyBoxFit(
      BoxFit.contain,
      sourceSize,
      viewportSize,
    ).destination;
    return ImageMaskViewportLayout(
      viewportSize: viewportSize,
      imageSize: imageSize,
      imageOffset: Offset(
        (viewportSize.width - imageSize.width) / 2,
        (viewportSize.height - imageSize.height) / 2,
      ),
      boundaryMargin: EdgeInsets.symmetric(
        horizontal: viewportSize.width / 2,
        vertical: viewportSize.height / 2,
      ),
    );
  }

  final Size viewportSize;
  final Size imageSize;
  final Offset imageOffset;
  final EdgeInsets boundaryMargin;
}
