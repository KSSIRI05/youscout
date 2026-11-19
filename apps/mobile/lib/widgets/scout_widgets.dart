import 'package:flutter/material.dart';
import '../core/theme.dart';
import '../data/models.dart';
import 'terrain.dart';

/// Bouton pleine largeur : l'encre pour l'action principale, un contour pour l'action secondaire.
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
  Widget build(BuildContext context) {
    final content = Row(
      mainAxisSize: MainAxisSize.min,
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        if (icon != null) ...[Icon(icon, size: 20), const SizedBox(width: 8)],
        Flexible(child: Text(label, textAlign: TextAlign.center)),
      ],
    );
    return SizedBox(
      width: double.infinity,
      child: secondary
          ? OutlinedButton(
              style: OutlinedButton.styleFrom(minimumSize: const Size(48, 52)),
              onPressed: onPressed,
              child: content,
            )
          : FilledButton(onPressed: onPressed, child: content),
    );
  }
}

/// Barre d'onglets commune aux deux modes, séparée du contenu par une ligne de craie.
class ScoutTabs extends StatelessWidget {
  const ScoutTabs({
    required this.selected,
    required this.onSelected,
    super.key,
  });
  final int selected;
  final ValueChanged<int> onSelected;
  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      border: Border(top: BorderSide(color: context.scout.ligne, width: 1.5)),
    ),
    child: NavigationBar(
      selectedIndex: selected,
      onDestinationSelected: onSelected,
      destinations: const [
        NavigationDestination(
          icon: Icon(Icons.home_outlined),
          selectedIcon: Icon(Icons.home),
          label: 'Accueil',
        ),
        NavigationDestination(
          icon: Icon(Icons.explore_outlined),
          selectedIcon: Icon(Icons.explore),
          label: 'Explorer',
        ),
        NavigationDestination(
          icon: Icon(Icons.add_circle_outline),
          selectedIcon: Icon(Icons.add_circle),
          label: 'Publier',
        ),
        NavigationDestination(
          icon: Icon(Icons.chat_bubble_outline),
          selectedIcon: Icon(Icons.chat_bubble),
          label: 'Messages',
        ),
        NavigationDestination(
          icon: Icon(Icons.person_outline),
          selectedIcon: Icon(Icons.person),
          label: 'Profil',
        ),
      ],
    ),
  );
}

/// Titre d'écran en lettrage de maillot, avec une phrase d'explication.
class SectionHeading extends StatelessWidget {
  const SectionHeading(this.title, {this.subtitle, super.key});
  final String title;
  final String? subtitle;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(title, style: Theme.of(context).textTheme.headlineMedium),
      if (subtitle != null) ...[
        const SizedBox(height: 8),
        Text(subtitle!, style: TextStyle(color: context.scout.gradin)),
      ],
    ],
  );
}

/// Ligne d'un joueur : initiales, nom, poste et ville.
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
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    borderRadius: BorderRadius.circular(10),
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 4),
      child: Row(
        children: [
          PlayerAvatar(talent.name, size: 44),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  talent.name,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  subtitle ?? '${talent.position}, ${talent.city}',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
          ?trailing,
        ],
      ),
    ),
  );
}

/// Compétences d'un joueur ou d'un clip, en contour de craie : l'orange reste réservé aux notes.
class SkillChips extends StatelessWidget {
  const SkillChips(this.skills, {super.key});
  final List<String> skills;
  @override
  Widget build(BuildContext context) => Wrap(
    spacing: 6,
    runSpacing: 6,
    children: [for (final s in skills) ScoutTag(s)],
  );
}

class EmptyState extends StatelessWidget {
  const EmptyState(this.title, this.description, {this.action, super.key});
  final String title, description;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 36),
    child: Column(
      children: [
        Icon(
          Icons.sports_soccer_outlined,
          color: context.scout.gradin,
          size: 36,
        ),
        const SizedBox(height: 14),
        Text(
          title,
          style: Theme.of(context).textTheme.titleLarge,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Text(
          description,
          style: TextStyle(color: context.scout.gradin),
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
