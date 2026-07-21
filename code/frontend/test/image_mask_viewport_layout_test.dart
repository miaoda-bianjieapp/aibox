import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/widgets/image_mask_viewport_layout.dart';

void main() {
  test('portrait image remains proportional and centered inside the viewport',
      () {
    final layout = ImageMaskViewportLayout.calculate(
      sourceSize: const Size(900, 1600),
      viewportSize: const Size(1000, 1200),
    );

    expect(layout.imageSize, const Size(675, 1200));
    expect(layout.imageSize.aspectRatio, closeTo(900 / 1600, 0.0001));
    expect(layout.imageOffset, const Offset(162.5, 0));
  });

  test('landscape image remains proportional and vertically centered', () {
    final layout = ImageMaskViewportLayout.calculate(
      sourceSize: const Size(1600, 900),
      viewportSize: const Size(1000, 1200),
    );

    expect(layout.imageSize, const Size(1000, 562.5));
    expect(layout.imageOffset, const Offset(0, 318.75));
  });

  test('viewport reserves half a screen of pan space on every edge', () {
    final layout = ImageMaskViewportLayout.calculate(
      sourceSize: const Size(900, 1600),
      viewportSize: const Size(1000, 1200),
    );

    expect(
      layout.boundaryMargin,
      const EdgeInsets.symmetric(horizontal: 500, vertical: 600),
    );
  });
}
