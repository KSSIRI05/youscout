import 'package:flutter/material.dart';
import '../core/theme.dart';
import '../data/models.dart';

class ScoutButton extends StatelessWidget {
  const ScoutButton(
    this.label, {
    required this.onPressed,
    this.secondary = false,
    this.icon,
    super.key,
  });
  final String label;
  final VoidCallback? onPressed;
  final bool secondary;
  final IconData? icon;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: double.infinity,
    child: FilledButton(
      style: secondary
          ? FilledButton.styleFrom(
              backgroundColor: ScoutColors.raised,
              foregroundColor: ScoutColors.ink,
            )
          : null,
      onPressed: onPressed,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (icon != null) ...[Icon(icon, size: 20), const SizedBox(width: 8)],
          Flexible(child: Text(label, textAlign: TextAlign.center)),
        ],
      ),
    ),
  );
}

class SectionHeading extends StatelessWidget {
  const SectionHeading(this.title, {this.subtitle, super.key});
  final String title;
  final String? subtitle;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(title, style: Theme.of(context).textTheme.headlineSmall),
      if (subtitle != null) ...[
        const SizedBox(height: 8),
        Text(subtitle!, style: const TextStyle(color: ScoutColors.muted)),
      ],
    ],
  );
}

class FootballArt extends StatelessWidget {
  const FootballArt({this.variant = 'demo', this.height = 230, super.key});
  final String variant;
  final double height;
  @override
  Widget build(BuildContext context) => Semantics(
    label: 'Illustration football de la maquette, pas une vidéo',
    image: true,
    child: SizedBox(
      width: double.infinity,
      height: height,
      child: Image.asset(
        'assets/images/football-$variant.png',
        fit: BoxFit.contain,
        excludeFromSemantics: true,
      ),
    ),
  );
}

class TalentTile extends StatelessWidget {
  const TalentTile(
    this.talent, {
    this.onTap,
    this.trailing,
    this.subtitle,
    super.key,
  });
  final Talent talent;
  final VoidCallback? onTap;
  final Widget? trailing;
  final String? subtitle;
  @override
  Widget build(BuildContext context) => Material(
    color: ScoutColors.surface,
    borderRadius: BorderRadius.circular(16),
    child: ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
      leading: CircleAvatar(
        backgroundColor: ScoutColors.raised,
        foregroundColor: ScoutColors.brand,
        child: Text(
          talent.initials,
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        ),
      ),
      title: Text(
        talent.name,
        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
      ),
      subtitle: Text(
        subtitle ?? '${talent.position} · ${talent.city}',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.bodySmall,
      ),
      onTap: onTap,
      trailing: trailing,
    ),
  );
}

class SkillChips extends StatelessWidget {
  const SkillChips(this.skills, {super.key});
  final List<String> skills;
  @override
  Widget build(BuildContext context) => Wrap(
    spacing: 8,
    runSpacing: 6,
    children: skills
        .map(
          (s) => Chip(
            label: Text(s, style: const TextStyle(color: ScoutColors.brand)),
          ),
        )
        .toList(),
  );
}

class EmptyState extends StatelessWidget {
  const EmptyState(this.title, this.description, {this.action, super.key});
  final String title, description;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 40),
    child: Column(
      children: [
        const Icon(
          Icons.sports_soccer_outlined,
          color: ScoutColors.brand,
          size: 42,
        ),
        const SizedBox(height: 16),
        Text(
          title,
          style: Theme.of(context).textTheme.titleMedium,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Text(
          description,
          style: const TextStyle(color: ScoutColors.muted),
          textAlign: TextAlign.center,
        ),
        if (action != null) ...[const SizedBox(height: 20), action!],
      ],
    ),
  );
}

void scoutMessage(BuildContext context, String text) =>
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(text)));
