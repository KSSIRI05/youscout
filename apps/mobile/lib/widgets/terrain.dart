import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../core/theme.dart';

/// Moyenne affichée à la française : 4,2.
String frScore(double value) => value.toStringAsFixed(1).replaceAll('.', ',');

/// Date courte et relative d'une publication.
String frWhen(DateTime at, {DateTime? now}) {
  final delta = (now ?? DateTime.now()).difference(at);
  if (delta.inMinutes < 1) return 'À l’instant';
  if (delta.inMinutes < 60) return 'Il y a ${delta.inMinutes} min';
  if (delta.inHours < 24) return 'Il y a ${delta.inHours} h';
  if (delta.inDays < 7) return 'Il y a ${delta.inDays} j';
  final local = at.toLocal();
  String two(int v) => v.toString().padLeft(2, '0');
  return '${two(local.day)}/${two(local.month)}/${local.year}';
}

String initialsOf(String name) {
  final parts = name.trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty);
  final letters = parts.take(2).map((p) => p.characters.first.toUpperCase());
  return letters.isEmpty ? '?' : letters.join();
}

/// Plot d'entraînement : un plot vaut un point sur l'échelle 1 à 5.
class ConeIcon extends StatelessWidget {
  const ConeIcon({this.filled = true, this.size = 18, super.key});
  final bool filled;
  final double size;
  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    return CustomPaint(
      size: Size(size * 0.92, size),
      painter: _ConePainter(
        filled: filled,
        fill: p.plot,
        band: p.surface,
        outline: p.gradin.withValues(alpha: 0.55),
      ),
    );
  }
}

class _ConePainter extends CustomPainter {
  const _ConePainter({
    required this.filled,
    required this.fill,
    required this.band,
    required this.outline,
  });
  final bool filled;
  final Color fill, band, outline;

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width, h = size.height;
    final body = Path()
      ..moveTo(w * 0.5, h * 0.06)
      ..lineTo(w * 0.80, h * 0.80)
      ..lineTo(w * 0.20, h * 0.80)
      ..close();
    final base = RRect.fromLTRBR(
      w * 0.04,
      h * 0.80,
      w * 0.96,
      h * 0.95,
      Radius.circular(h * 0.04),
    );
    if (filled) {
      final paint = Paint()..color = fill;
      canvas
        ..drawPath(body, paint)
        ..drawRRect(base, paint);
      final stripe = Path()
        ..moveTo(w * 0.385, h * 0.36)
        ..lineTo(w * 0.615, h * 0.36)
        ..lineTo(w * 0.67, h * 0.50)
        ..lineTo(w * 0.33, h * 0.50)
        ..close();
      canvas.drawPath(stripe, Paint()..color = band);
    } else {
      final paint = Paint()
        ..color = outline
        ..style = PaintingStyle.stroke
        ..strokeWidth = math.max(1.2, h * 0.07)
        ..strokeJoin = StrokeJoin.round;
      canvas
        ..drawPath(body, paint)
        ..drawRRect(base, paint);
    }
  }

  @override
  bool shouldRepaint(_ConePainter old) =>
      old.filled != filled ||
      old.fill != fill ||
      old.band != band ||
      old.outline != outline;
}

/// Note en lecture seule : cinq plots, dont [score] remplis.
class ConeScore extends StatelessWidget {
  const ConeScore(this.score, {this.size = 16, super.key});
  final int score;
  final double size;
  @override
  Widget build(BuildContext context) => Semantics(
    label: '$score sur 5',
    child: ExcludeSemantics(
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (var i = 1; i <= 5; i++)
            Padding(
              padding: const EdgeInsets.only(right: 2),
              child: ConeIcon(filled: i <= score, size: size),
            ),
        ],
      ),
    ),
  );
}

/// Saisie d'une note : chaque plot est un bouton ; les plots remplis se posent l'un après l'autre.
class ConeRatingInput extends StatelessWidget {
  const ConeRatingInput({
    required this.skill,
    required this.score,
    required this.onRate,
    this.size = 26,
    super.key,
  });
  final String skill;
  final int score;
  final ValueChanged<int>? onRate;
  final double size;
  @override
  Widget build(BuildContext context) {
    final still = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 1; i <= 5; i++)
          Tooltip(
            message: 'Noter $skill : $i sur 5',
            excludeFromSemantics: true,
            child: Semantics(
              button: true,
              selected: i == score,
              label: '$skill : $i sur 5',
              child: InkResponse(
                onTap: onRate == null ? null : () => onRate!(i),
                radius: size * 0.9,
                child: SizedBox(
                  width: size + 16,
                  height: size + 18,
                  child: Center(
                    child: AnimatedScale(
                      scale: i <= score ? 1 : 0.86,
                      duration: still
                          ? Duration.zero
                          : Duration(milliseconds: 150 + 45 * i),
                      curve: Curves.easeOutBack,
                      child: ConeIcon(filled: i <= score, size: size),
                    ),
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

/// Une ligne de la fiche d'un clip : compétence, moyenne et, si l'on peut noter, les plots à toucher.
class FicheSkill extends StatelessWidget {
  const FicheSkill({
    required this.skill,
    this.average,
    this.count = 0,
    this.myScore,
    this.onRate,
    this.onClear,
    this.showCommunity = true,
    super.key,
  });
  final String skill;
  final double? average;
  final int count;
  final int? myScore;
  final ValueChanged<int>? onRate;
  final VoidCallback? onClear;

  /// La démo ne connaît que la note de l'appareil : elle masque la moyenne.
  final bool showCommunity;

  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    final text = Theme.of(context).textTheme;
    final rated = count > 0 && average != null;
    final community = !showCommunity
        ? null
        : rated
        ? 'Moyenne ${frScore(average!)} sur 5, $count note${count > 1 ? 's' : ''}.'
        : 'Pas encore notée.';
    final mine = myScore == null
        ? (onRate == null ? null : 'Touche un plot pour noter.')
        : 'Ta note : $myScore.';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(child: Text(skill, style: text.titleSmall)),
              if (showCommunity)
                Text(
                  rated ? frScore(average!) : '–',
                  style: scoutDisplay(30, rated ? p.encre : p.gradin),
                ),
            ],
          ),
          if (onRate != null)
            Wrap(
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                ConeRatingInput(
                  skill: skill,
                  score: myScore ?? 0,
                  onRate: onRate,
                ),
                if (onClear != null && myScore != null)
                  TextButton(
                    onPressed: onClear,
                    child: const Text('Retirer ma note'),
                  ),
              ],
            )
          else if (rated)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: ConeScore(average!.round()),
            ),
          Text([?mine, ?community].join(' '), style: text.bodySmall),
        ],
      ),
    );
  }
}

enum PitchMarks { none, box, centre }

/// Pelouse tondue en bandes, avec ses lignes de craie. Sert d'emplacement aux clips sans image.
class PitchSurface extends StatelessWidget {
  const PitchSurface({
    this.marks = PitchMarks.box,
    this.radius = 0,
    this.stripe = 34,
    this.child,
    super.key,
  });
  final PitchMarks marks;
  final double radius, stripe;
  final Widget? child;
  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: CustomPaint(
        painter: _PitchPainter(
          marks: marks,
          stripe: stripe,
          a: p.pelouse,
          b: p.pelouseClaire,
          chalk: p.surPelouse.withValues(alpha: 0.5),
        ),
        child: DefaultTextStyle.merge(
          style: TextStyle(color: p.surPelouse),
          child: IconTheme.merge(
            data: IconThemeData(color: p.surPelouse),
            child: child ?? const SizedBox.expand(),
          ),
        ),
      ),
    );
  }
}

class _PitchPainter extends CustomPainter {
  const _PitchPainter({
    required this.marks,
    required this.stripe,
    required this.a,
    required this.b,
    required this.chalk,
  });
  final PitchMarks marks;
  final double stripe;
  final Color a, b, chalk;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = a);
    final band = Paint()..color = b;
    for (var y = stripe; y < size.height; y += stripe * 2) {
      canvas.drawRect(Rect.fromLTWH(0, y, size.width, stripe), band);
    }
    if (marks == PitchMarks.none) return;
    final line = Paint()
      ..color = chalk
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    final inset = math.min(14.0, size.shortestSide * 0.06);
    final field = Rect.fromLTRB(
      inset,
      inset,
      size.width - inset,
      size.height - inset,
    );
    canvas.drawRect(field, line);
    if (marks == PitchMarks.box) {
      final box = Rect.fromCenter(
        center: Offset(size.width / 2, field.top + field.height * 0.13),
        width: field.width * 0.58,
        height: field.height * 0.26,
      );
      final goal = Rect.fromCenter(
        center: Offset(size.width / 2, field.top + field.height * 0.045),
        width: field.width * 0.27,
        height: field.height * 0.09,
      );
      canvas
        ..drawRect(box, line)
        ..drawRect(goal, line)
        ..drawArc(
          Rect.fromCenter(
            center: Offset(size.width / 2, box.bottom - field.height * 0.05),
            width: field.width * 0.3,
            height: field.height * 0.16,
          ),
          0.35,
          math.pi - 0.7,
          false,
          line,
        );
    } else {
      final mid = field.center;
      canvas
        ..drawLine(
          Offset(field.left, mid.dy),
          Offset(field.right, mid.dy),
          line,
        )
        ..drawCircle(mid, field.shortestSide * 0.18, line)
        ..drawCircle(mid, 2.2, Paint()..color = chalk);
    }
  }

  @override
  bool shouldRepaint(_PitchPainter old) =>
      old.marks != marks ||
      old.stripe != stripe ||
      old.a != a ||
      old.b != b ||
      old.chalk != chalk;
}

/// Poste d'un joueur placé sur un terrain vu d'en haut, attaque vers le haut.
/// Ne s'affiche que pour un poste connu : la démo le fournit, le profil connecté non.
class PitchPosition extends StatelessWidget {
  const PitchPosition(this.position, {this.width = 64, super.key});
  final String position;
  final double width;

  static Offset? spot(String position) {
    final p = position.toLowerCase();
    if (p.contains('gardien')) return const Offset(0.5, 0.9);
    if (p.contains('latéral')) return const Offset(0.16, 0.72);
    if (p.contains('défens')) return const Offset(0.5, 0.74);
    if (p.contains('milieu')) return const Offset(0.5, 0.5);
    if (p.contains('ailier')) return const Offset(0.84, 0.3);
    if (p.contains('attaqu') || p.contains('avant')) {
      return const Offset(0.5, 0.17);
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final at = spot(position);
    if (at == null) return const SizedBox.shrink();
    final p = context.scout;
    return Semantics(
      label: 'Poste : $position',
      child: SizedBox(
        width: width,
        height: width * 1.38,
        child: PitchSurface(
          marks: PitchMarks.centre,
          radius: 4,
          stripe: width * 0.17,
          child: LayoutBuilder(
            builder: (context, box) => Stack(
              children: [
                Positioned(
                  left: box.maxWidth * at.dx - 6,
                  top: box.maxHeight * at.dy - 6,
                  child: Container(
                    width: 12,
                    height: 12,
                    decoration: BoxDecoration(
                      color: p.surPelouse,
                      shape: BoxShape.circle,
                      border: Border.all(color: p.encre, width: 2),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Initiales du joueur sur un rond de pelouse.
class PlayerAvatar extends StatelessWidget {
  const PlayerAvatar(this.name, {this.size = 40, super.key});
  final String name;
  final double size;
  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    return ExcludeSemantics(
      child: Container(
        width: size,
        height: size,
        alignment: Alignment.center,
        decoration: BoxDecoration(color: p.pelouse, shape: BoxShape.circle),
        child: Text(
          initialsOf(name),
          style: scoutDisplay(size * 0.46, p.surPelouse),
        ),
      ),
    );
  }
}

enum TagTone { neutral, turf, valide, alerte }

/// Étiquette d'état : publiée, en traitement, retirée…
class ScoutTag extends StatelessWidget {
  const ScoutTag(this.label, {this.tone = TagTone.neutral, super.key});
  final String label;
  final TagTone tone;
  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    final (bg, fg, border) = switch (tone) {
      TagTone.neutral => (p.surface, p.gradin, p.ligne),
      TagTone.turf => (p.pelouse, p.surPelouse, p.pelouse),
      TagTone.valide => (p.surface, p.valide, p.valide),
      TagTone.alerte => (p.surface, p.alerte, p.alerte),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
      decoration: ShapeDecoration(
        color: bg,
        shape: StadiumBorder(side: BorderSide(color: border, width: 1.5)),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontFamily: ScoutFonts.text,
          fontSize: 12,
          fontWeight: FontWeight.w500,
          color: fg,
        ),
      ),
    );
  }
}

/// Message d'état visible : une erreur dit ce qui s'est passé, une confirmation reprend le verbe de l'action.
class ScoutNotice extends StatelessWidget {
  const ScoutNotice(this.text, {this.error = false, super.key});
  final String text;
  final bool error;
  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    final color = error ? p.alerte : p.valide;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 2, right: 8),
          child: Icon(
            error ? Icons.error_outline : Icons.check_circle_outline,
            size: 18,
            color: color,
          ),
        ),
        Expanded(
          child: Text(text, style: TextStyle(color: color, height: 1.4)),
        ),
      ],
    );
  }
}

/// Bouton « Noter » : la seule action orange de l'application.
class RateButton extends StatelessWidget {
  const RateButton({required this.onPressed, this.label = 'Noter', super.key});
  final VoidCallback? onPressed;
  final String label;
  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    return FilledButton.icon(
      style: FilledButton.styleFrom(
        backgroundColor: p.plot,
        foregroundColor: p.surPlot,
        minimumSize: const Size(48, 44),
        padding: const EdgeInsets.symmetric(horizontal: 16),
      ),
      onPressed: onPressed,
      icon: _MonoCone(color: p.surPlot),
      label: Text(label),
    );
  }
}

class _MonoCone extends StatelessWidget {
  const _MonoCone({required this.color});
  final Color color;
  @override
  Widget build(BuildContext context) => CustomPaint(
    size: const Size(15, 16),
    painter: _ConePainter(
      filled: true,
      fill: color,
      band: context.scout.plot,
      outline: color,
    ),
  );
}
