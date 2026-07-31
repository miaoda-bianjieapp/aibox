import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

const outputBodyFontSize = 16.0;
const outputBodyLineHeight = 1.6;
const outputBodyLineExtent = outputBodyFontSize * outputBodyLineHeight;

TextStyle outputBodyTextStyle({
  Color color = AppColors.ink,
  FontWeight fontWeight = FontWeight.w400,
}) {
  return TextStyle(
    color: color,
    fontFamily: _systemFontFamily(),
    fontSize: outputBodyFontSize,
    height: outputBodyLineHeight,
    fontWeight: fontWeight,
    letterSpacing: 0,
  );
}

StrutStyle outputBodyStrutStyle() {
  return StrutStyle(
    fontFamily: _systemFontFamily(),
    fontSize: outputBodyFontSize,
    height: outputBodyLineHeight,
    forceStrutHeight: true,
  );
}

String? _systemFontFamily() {
  return switch (defaultTargetPlatform) {
    TargetPlatform.android => 'Roboto',
    TargetPlatform.iOS || TargetPlatform.macOS => '.SF Pro Text',
    TargetPlatform.windows => 'Segoe UI',
    _ => null,
  };
}
