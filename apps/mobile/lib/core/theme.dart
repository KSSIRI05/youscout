import 'package:flutter/material.dart';

/// Direction « Terrain de détection » : la craie, la pelouse et le plot d'entraînement.
///
/// Le plot orange est réservé aux notes et au bouton qui permet d'en donner une.
/// Les autres actions utilisent l'encre ; la pelouse marque une sélection.
@immutable
class ScoutPalette extends ThemeExtension<ScoutPalette> {
  const ScoutPalette({
    required this.craie,
    required this.surface,
    required this.raised,
    required this.encre,
    required this.pelouse,
    required this.pelouseClaire,
    required this.surPelouse,
    required this.plot,
    required this.surPlot,
    required this.gradin,
    required this.ligne,
    required this.alerte,
    required this.valide,
  });

  /// Fond de page, craie fraîche.
  final Color craie;

  /// Feuille posée sur le terrain : champs, feuilles du bas, bulles.
  final Color surface;

  /// Surface légèrement en relief : bulles envoyées, zones désactivées.
  final Color raised;

  /// Texte principal et boutons d'action.
  final Color encre;

  /// Pelouse et sa bande tondue dans l'autre sens.
  final Color pelouse, pelouseClaire;

  /// Texte et lignes de craie posés sur la pelouse.
  final Color surPelouse;

  /// Plot d'entraînement : notes et bouton « Noter » uniquement.
  final Color plot, surPlot;

  /// Texte secondaire, comme le béton des tribunes.
  final Color gradin;

  /// Ligne de craie sur fond clair : séparateurs, contours.
  final Color ligne;

  /// Erreur et action destructive ; toujours accompagnée d'un texte.
  final Color alerte;

  /// Confirmation (enregistré, publié) et état en direct.
  final Color valide;

  static const light = ScoutPalette(
    craie: Color(0xFFF3F5F0),
    surface: Color(0xFFFFFFFF),
    raised: Color(0xFFE6ECE7),
    encre: Color(0xFF12241B),
    pelouse: Color(0xFF1D6A46),
    pelouseClaire: Color(0xFF22774F),
    surPelouse: Color(0xFFF3F5F0),
    plot: Color(0xFFF2600C),
    surPlot: Color(0xFF12241B),
    gradin: Color(0xFF5C6D63),
    ligne: Color(0xFFD3DCD5),
    alerte: Color(0xFFB3261E),
    valide: Color(0xFF1D6A46),
  );

  /// Le même terrain sous les projecteurs.
  static const dark = ScoutPalette(
    craie: Color(0xFF0E1C15),
    surface: Color(0xFF15271E),
    raised: Color(0xFF1E3529),
    encre: Color(0xFFE8EEE9),
    pelouse: Color(0xFF1A5A3C),
    pelouseClaire: Color(0xFF1E6544),
    surPelouse: Color(0xFFEEF2EE),
    plot: Color(0xFFFF7A2E),
    surPlot: Color(0xFF12241B),
    gradin: Color(0xFF9DB0A5),
    ligne: Color(0xFF2B4437),
    alerte: Color(0xFFFFB4AB),
    valide: Color(0xFF8FD8AE),
  );

  /// Lit la palette du thème ; un widget monté sans le thème YouScout (tests) garde des couleurs cohérentes.
  static ScoutPalette of(BuildContext context) {
    final theme = Theme.of(context);
    return theme.extension<ScoutPalette>() ??
        (theme.brightness == Brightness.dark ? dark : light);
  }

  @override
  ScoutPalette copyWith({
    Color? craie,
    Color? surface,
    Color? raised,
    Color? encre,
    Color? pelouse,
    Color? pelouseClaire,
    Color? surPelouse,
    Color? plot,
    Color? surPlot,
    Color? gradin,
    Color? ligne,
    Color? alerte,
    Color? valide,
  }) => ScoutPalette(
    craie: craie ?? this.craie,
    surface: surface ?? this.surface,
    raised: raised ?? this.raised,
    encre: encre ?? this.encre,
    pelouse: pelouse ?? this.pelouse,
    pelouseClaire: pelouseClaire ?? this.pelouseClaire,
    surPelouse: surPelouse ?? this.surPelouse,
    plot: plot ?? this.plot,
    surPlot: surPlot ?? this.surPlot,
    gradin: gradin ?? this.gradin,
    ligne: ligne ?? this.ligne,
    alerte: alerte ?? this.alerte,
    valide: valide ?? this.valide,
  );

  @override
  ScoutPalette lerp(ScoutPalette? other, double t) {
    if (other == null) return this;
    Color mix(Color a, Color b) => Color.lerp(a, b, t)!;
    return ScoutPalette(
      craie: mix(craie, other.craie),
      surface: mix(surface, other.surface),
      raised: mix(raised, other.raised),
      encre: mix(encre, other.encre),
      pelouse: mix(pelouse, other.pelouse),
      pelouseClaire: mix(pelouseClaire, other.pelouseClaire),
      surPelouse: mix(surPelouse, other.surPelouse),
      plot: mix(plot, other.plot),
      surPlot: mix(surPlot, other.surPlot),
      gradin: mix(gradin, other.gradin),
      ligne: mix(ligne, other.ligne),
      alerte: mix(alerte, other.alerte),
      valide: mix(valide, other.valide),
    );
  }
}

extension ScoutContext on BuildContext {
  ScoutPalette get scout => ScoutPalette.of(this);
}

/// Deux familles : le lettrage de maillot pour les noms, les titres et les notes ; Readex Pro pour tout le reste.
abstract final class ScoutFonts {
  static const display = 'BigShoulders';
  static const text = 'ReadexPro';
}

/// Style d'un nom de joueur ou d'un titre d'écran, en lettrage de maillot.
TextStyle scoutDisplay(double size, Color color, {bool heavy = true}) =>
    TextStyle(
      fontFamily: ScoutFonts.display,
      fontSize: size,
      height: 0.95,
      fontWeight: heavy ? FontWeight.w800 : FontWeight.w600,
      color: color,
    );

ThemeData scoutTheme([Brightness brightness = Brightness.light]) {
  final p = brightness == Brightness.dark
      ? ScoutPalette.dark
      : ScoutPalette.light;
  final scheme = ColorScheme(
    brightness: brightness,
    primary: p.encre,
    onPrimary: p.craie,
    primaryContainer: p.raised,
    onPrimaryContainer: p.encre,
    secondary: p.pelouse,
    onSecondary: p.surPelouse,
    secondaryContainer: p.pelouse,
    onSecondaryContainer: p.surPelouse,
    tertiary: p.plot,
    onTertiary: p.surPlot,
    error: p.alerte,
    onError: brightness == Brightness.dark ? p.surPlot : p.surface,
    surface: p.surface,
    onSurface: p.encre,
    onSurfaceVariant: p.gradin,
    surfaceContainerLowest: p.surface,
    surfaceContainerLow: p.surface,
    surfaceContainer: p.surface,
    surfaceContainerHigh: p.surface,
    surfaceContainerHighest: p.raised,
    outline: p.ligne,
    outlineVariant: p.ligne,
    shadow: Colors.transparent,
    inverseSurface: p.encre,
    onInverseSurface: p.craie,
  );
  const pill = StadiumBorder();
  const text = TextStyle(fontFamily: ScoutFonts.text);
  final base = ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    brightness: brightness,
    fontFamily: ScoutFonts.text,
  );
  final selectedOrNot = WidgetStateProperty.resolveWith<Color?>(
    (states) => states.contains(WidgetState.selected) ? p.pelouse : p.surface,
  );
  return base.copyWith(
    extensions: [p],
    scaffoldBackgroundColor: p.craie,
    canvasColor: p.craie,
    dividerColor: p.ligne,
    textTheme: base.textTheme
        .copyWith(
          displaySmall: scoutDisplay(44, p.encre),
          headlineLarge: scoutDisplay(42, p.encre),
          headlineMedium: scoutDisplay(36, p.encre),
          headlineSmall: scoutDisplay(32, p.encre),
          titleLarge: scoutDisplay(26, p.encre, heavy: false),
          titleMedium: text.copyWith(
            fontSize: 17,
            height: 1.35,
            fontWeight: FontWeight.w500,
          ),
          titleSmall: text.copyWith(
            fontSize: 15,
            height: 1.35,
            fontWeight: FontWeight.w600,
          ),
          bodyLarge: text.copyWith(fontSize: 16, height: 1.5),
          bodyMedium: text.copyWith(fontSize: 15, height: 1.5),
          bodySmall: text.copyWith(fontSize: 13, height: 1.4, color: p.gradin),
          labelLarge: text.copyWith(fontSize: 15, fontWeight: FontWeight.w600),
          labelMedium: text.copyWith(fontSize: 13, fontWeight: FontWeight.w500),
          labelSmall: text.copyWith(fontSize: 12, fontWeight: FontWeight.w500),
        )
        .apply(bodyColor: p.encre, displayColor: p.encre),
    appBarTheme: AppBarTheme(
      backgroundColor: p.craie,
      foregroundColor: p.encre,
      elevation: 0,
      scrolledUnderElevation: 0,
      surfaceTintColor: Colors.transparent,
      centerTitle: false,
      titleTextStyle: scoutDisplay(30, p.encre),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(48, 52),
        backgroundColor: p.encre,
        foregroundColor: p.craie,
        disabledBackgroundColor: p.raised,
        disabledForegroundColor: p.gradin,
        shape: pill,
        textStyle: text.copyWith(fontSize: 15, fontWeight: FontWeight.w600),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(48, 48),
        foregroundColor: p.encre,
        side: BorderSide(color: p.ligne, width: 1.5),
        shape: pill,
        textStyle: text.copyWith(fontSize: 15, fontWeight: FontWeight.w600),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: p.encre,
        textStyle: text.copyWith(fontSize: 15, fontWeight: FontWeight.w500),
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(foregroundColor: p.encre),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: p.surface,
      hintStyle: text.copyWith(color: p.gradin, fontSize: 15),
      labelStyle: text.copyWith(color: p.gradin, fontSize: 15),
      floatingLabelStyle: text.copyWith(color: p.encre, fontSize: 15),
      prefixIconColor: p.gradin,
      suffixIconColor: p.gradin,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: BorderSide(color: p.ligne, width: 1.5),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: BorderSide(color: p.ligne, width: 1.5),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: BorderSide(color: p.encre, width: 2),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: BorderSide(color: p.alerte, width: 1.5),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: BorderSide(color: p.alerte, width: 2),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    ),
    cardTheme: CardThemeData(
      color: p.surface,
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 10),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: p.ligne, width: 1.5),
      ),
    ),
    chipTheme: base.chipTheme.copyWith(
      color: selectedOrNot,
      side: BorderSide(color: p.ligne, width: 1.5),
      shape: pill,
      showCheckmark: false,
      labelStyle: text.copyWith(fontSize: 13, color: p.encre),
      secondaryLabelStyle: text.copyWith(fontSize: 13, color: p.surPelouse),
      iconTheme: IconThemeData(color: p.gradin, size: 16),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(
      style: ButtonStyle(
        backgroundColor: selectedOrNot,
        foregroundColor: WidgetStateProperty.resolveWith(
          (states) =>
              states.contains(WidgetState.selected) ? p.surPelouse : p.encre,
        ),
        side: WidgetStatePropertyAll(BorderSide(color: p.ligne, width: 1.5)),
        textStyle: WidgetStatePropertyAll(
          text.copyWith(fontSize: 14, fontWeight: FontWeight.w500),
        ),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: p.surface,
      surfaceTintColor: Colors.transparent,
      indicatorColor: p.pelouse,
      height: 72,
      iconTheme: WidgetStateProperty.resolveWith(
        (states) => IconThemeData(
          color: states.contains(WidgetState.selected)
              ? p.surPelouse
              : p.gradin,
        ),
      ),
      labelTextStyle: WidgetStateProperty.resolveWith(
        (states) => text.copyWith(
          fontSize: 12,
          color: states.contains(WidgetState.selected) ? p.encre : p.gradin,
          fontWeight: states.contains(WidgetState.selected)
              ? FontWeight.w600
              : FontWeight.w400,
        ),
      ),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: p.surface,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
      dragHandleColor: p.ligne,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: p.surface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      titleTextStyle: scoutDisplay(28, p.encre),
      contentTextStyle: text.copyWith(
        fontSize: 15,
        height: 1.5,
        color: p.encre,
      ),
    ),
    popupMenuTheme: PopupMenuThemeData(
      color: p.surface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(10),
        side: BorderSide(color: p.ligne, width: 1.5),
      ),
      textStyle: text.copyWith(fontSize: 15, color: p.encre),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: p.encre,
      contentTextStyle: text.copyWith(fontSize: 14, color: p.craie),
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
    ),
    dividerTheme: DividerThemeData(color: p.ligne, thickness: 1.5, space: 32),
    listTileTheme: ListTileThemeData(
      iconColor: p.gradin,
      textColor: p.encre,
      subtitleTextStyle: text.copyWith(fontSize: 13, color: p.gradin),
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(
      color: p.pelouse,
      linearTrackColor: p.ligne,
      circularTrackColor: Colors.transparent,
    ),
    badgeTheme: BadgeThemeData(
      backgroundColor: p.pelouse,
      textColor: p.surPelouse,
    ),
    dropdownMenuTheme: DropdownMenuThemeData(
      textStyle: text.copyWith(fontSize: 15, color: p.encre),
    ),
    textSelectionTheme: TextSelectionThemeData(
      cursorColor: p.encre,
      selectionColor: p.pelouse.withValues(alpha: 0.3),
      selectionHandleColor: p.pelouse,
    ),
  );
}
