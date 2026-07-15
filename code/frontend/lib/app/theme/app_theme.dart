import 'package:flutter/material.dart';

abstract final class AppColors {
  static const ink = Color(0xFF14201D);
  static const muted = Color(0xFF71807B);
  static const line = Color(0xFFE4E9E7);
  static const paper = Color(0xFFFFFFFF);
  static const wash = Color(0xFFF3F6F4);
  static const accent = Color(0xFF0D8068);
  static const accentSoft = Color(0xFFDFF3ED);
}

abstract final class AppTheme {
  static ThemeData get light {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: AppColors.accent,
      brightness: Brightness.light,
      surface: AppColors.paper,
    ).copyWith(
      primary: AppColors.accent,
      onPrimary: Colors.white,
      surface: AppColors.paper,
      onSurface: AppColors.ink,
      outline: AppColors.line,
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: AppColors.paper,
      fontFamily: 'Microsoft YaHei',
      splashFactory: InkSparkle.splashFactory,
      dividerColor: AppColors.line,
      textTheme: const TextTheme(
        headlineLarge: TextStyle(
          color: AppColors.ink,
          fontSize: 30,
          height: 1.2,
          fontWeight: FontWeight.w800,
          letterSpacing: 0,
        ),
        headlineMedium: TextStyle(
          color: AppColors.ink,
          fontSize: 25,
          height: 1.25,
          fontWeight: FontWeight.w800,
          letterSpacing: 0,
        ),
        titleLarge: TextStyle(
          color: AppColors.ink,
          fontSize: 18,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
        titleMedium: TextStyle(
          color: AppColors.ink,
          fontSize: 16,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
        bodyLarge: TextStyle(
          color: AppColors.ink,
          fontSize: 15,
          height: 1.55,
          letterSpacing: 0,
        ),
        bodyMedium: TextStyle(
          color: AppColors.muted,
          fontSize: 13,
          height: 1.55,
          letterSpacing: 0,
        ),
        labelLarge: TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
      ),
      inputDecorationTheme: const InputDecorationTheme(
        filled: true,
        fillColor: Color(0xFFFBFCFB),
        hintStyle: TextStyle(color: Color(0xFF94A09C), fontSize: 14),
        contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(8)),
          borderSide: BorderSide(color: AppColors.line),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(8)),
          borderSide: BorderSide(color: AppColors.accent, width: 1.3),
        ),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: AppColors.paper,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(8)),
        ),
      ),
      snackBarTheme: const SnackBarThemeData(
        backgroundColor: AppColors.ink,
        contentTextStyle: TextStyle(color: Colors.white, fontSize: 13),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(8)),
        ),
      ),
    );
  }
}
