import 'package:flutter/material.dart';

abstract final class ScoutColors {
  static const background = Color(0xFF0B110F);
  static const surface = Color(0xFF151E19);
  static const raised = Color(0xFF202D25);
  static const brand = Color(0xFFC4F554);
  static const ink = Color(0xFFF5F8F1);
  static const muted = Color(0xFFA5B2A7);
  static const line = Color(0xFF334237);
  static const danger = Color(0xFFFF8D88);
}

ThemeData scoutTheme() {
  final scheme = ColorScheme.fromSeed(
    seedColor: ScoutColors.brand,
    brightness: Brightness.dark,
    primary: ScoutColors.brand,
    onPrimary: const Color(0xFF10170B),
    surface: ScoutColors.surface,
    onSurface: ScoutColors.ink,
    error: ScoutColors.danger,
  );
  final base = ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    fontFamily: 'Inter',
  );
  return base.copyWith(
    scaffoldBackgroundColor: ScoutColors.background,
    textTheme: base.textTheme
        .copyWith(
          headlineLarge: const TextStyle(
            fontSize: 36,
            height: 1.2,
            fontWeight: FontWeight.w700,
          ),
          headlineSmall: const TextStyle(
            fontSize: 24,
            height: 1.35,
            fontWeight: FontWeight.w700,
          ),
          titleMedium: const TextStyle(
            fontSize: 18,
            height: 1.35,
            fontWeight: FontWeight.w600,
          ),
          bodyMedium: const TextStyle(fontSize: 14, height: 1.35),
          bodySmall: const TextStyle(
            fontSize: 12,
            height: 1.35,
            color: ScoutColors.muted,
          ),
        )
        .apply(
          fontFamily: 'Inter',
          bodyColor: ScoutColors.ink,
          displayColor: ScoutColors.ink,
        ),
    appBarTheme: const AppBarTheme(
      backgroundColor: ScoutColors.background,
      centerTitle: false,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(48, 52),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        textStyle: const TextStyle(
          fontFamily: 'Inter',
          fontSize: 14,
          fontWeight: FontWeight.w600,
        ),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: ScoutColors.surface,
      hintStyle: const TextStyle(color: ScoutColors.muted, fontSize: 14),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide.none,
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 17),
    ),
    navigationBarTheme: const NavigationBarThemeData(
      backgroundColor: ScoutColors.surface,
      indicatorColor: ScoutColors.raised,
      height: 80,
      labelTextStyle: WidgetStatePropertyAll(
        TextStyle(fontFamily: 'Inter', fontSize: 10),
      ),
    ),
    chipTheme: base.chipTheme.copyWith(
      backgroundColor: ScoutColors.raised,
      selectedColor: ScoutColors.brand,
      side: BorderSide.none,
      labelStyle: const TextStyle(fontSize: 12, color: ScoutColors.ink),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    ),
  );
}
