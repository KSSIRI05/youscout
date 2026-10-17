import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import '../core/theme.dart';
import '../state/scout_store.dart';
import '../widgets/local_video.dart';
import '../widgets/scout_widgets.dart';

class UploadScreen extends StatefulWidget {
  const UploadScreen({
    required this.store,
    required this.onPublished,
    super.key,
  });
  final ScoutStore store;
  final VoidCallback onPublished;
  @override
  State<UploadScreen> createState() => _UploadScreenState();
}

class _UploadScreenState extends State<UploadScreen> {
  final description = TextEditingController(),
      hashtags = TextEditingController();
  final form = GlobalKey<FormState>();
  final Set<String> skills = {};
  XFile? file;
  bool selected = false, picking = false;
  @override
  void dispose() {
    description.dispose();
    hashtags.dispose();
    super.dispose();
  }

  Future<void> pick(ImageSource source) async {
    setState(() => picking = true);
    try {
      final next = await ImagePicker().pickVideo(source: source);
      if (next == null) return;
      // This local preview cap protects the prototype; it is not a backend quota.
      if (await next.length() > 100 * 1024 * 1024) {
        if (mounted) {
          scoutMessage(
            context,
            'Choisis un clip de moins de 100 Mo pour cet aperçu local.',
          );
        }
        return;
      }
      if (mounted) {
        setState(() {
          file = next;
          selected = true;
        });
      }
    } catch (_) {
      if (mounted) {
        scoutMessage(
          context,
          source == ImageSource.camera
              ? 'La caméra n’est pas disponible ou son accès est refusé. Essaie la galerie.'
              : 'Impossible d’ouvrir ce fichier. Essaie une autre vidéo.',
        );
      }
    } finally {
      if (mounted) setState(() => picking = false);
    }
  }

  void publish() {
    if (!form.currentState!.validate()) return;
    if (skills.isEmpty) {
      scoutMessage(context, 'Sélectionne au moins une compétence.');
      return;
    }
    widget.store.publish(
      description: description.text,
      skills: skills,
      hashtags: hashtags.text,
      mediaPath: file?.path,
    );
    setState(() {
      selected = false;
      file = null;
      description.clear();
      hashtags.clear();
      skills.clear();
    });
    scoutMessage(
      context,
      'Publication ajoutée à ta démo locale. Aucun fichier envoyé.',
    );
    widget.onPublished();
  }

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(24),
    children: [
      SectionHeading(
        selected ? 'Prêt à te montrer ?' : 'Montre ton jeu.',
        subtitle: selected
            ? 'Ajoute du contexte à ta vidéo.'
            : 'Une action, une compétence, une nouvelle opportunité.',
      ),
      const SizedBox(height: 18),
      if (selected) ...[
        if (file != null)
          LocalVideo(file!.path)
        else
          const FootballArt(variant: 'details', height: 180),
        const SizedBox(height: 12),
        Text(
          file?.name ?? 'Illustration de démonstration',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        Form(
          key: form,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 20),
              TextFormField(
                key: const Key('post-description'),
                controller: description,
                maxLines: 3,
                maxLength: 500,
                decoration: const InputDecoration(
                  labelText: 'Description',
                  hintText: 'Raconte ton action…',
                ),
                validator: (v) => v == null || v.trim().isEmpty
                    ? 'Ajoute une description.'
                    : null,
              ),
              const SizedBox(height: 12),
              const Text(
                'Compétences démontrées',
                style: TextStyle(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 6,
                children:
                    [
                          'Dribble',
                          'Technique',
                          'Vitesse',
                          'Frappe',
                          'Finition',
                          'Vision du jeu',
                        ]
                        .map(
                          (s) => FilterChip(
                            label: Text(s),
                            selected: skills.contains(s),
                            labelStyle: TextStyle(
                              color: skills.contains(s)
                                  ? ScoutColors.background
                                  : ScoutColors.ink,
                            ),
                            onSelected: (v) => setState(() {
                              v ? skills.add(s) : skills.remove(s);
                            }),
                          ),
                        )
                        .toList(),
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: hashtags,
                maxLength: 150,
                decoration: const InputDecoration(
                  labelText: 'Hashtags',
                  hintText: '#FootballMaroc #Entraînement',
                ),
              ),
              const SizedBox(height: 16),
              ScoutButton('Ajouter à mon feed local', onPressed: publish),
              const SizedBox(height: 10),
              ScoutButton(
                'Changer de clip',
                secondary: true,
                onPressed: () => setState(() {
                  selected = false;
                  file = null;
                }),
              ),
            ],
          ),
        ),
      ] else ...[
        const FootballArt(variant: 'upload', height: 285),
        const SizedBox(height: 20),
        if (!kIsWeb) ...[
          ScoutButton(
            'Filmer une vidéo',
            icon: Icons.videocam_outlined,
            onPressed: picking ? null : () => pick(ImageSource.camera),
          ),
          const SizedBox(height: 12),
        ],
        ScoutButton(
          picking ? 'Ouverture…' : 'Choisir dans la galerie',
          icon: Icons.video_library_outlined,
          onPressed: picking ? null : () => pick(ImageSource.gallery),
        ),
        const SizedBox(height: 12),
        ScoutButton(
          'Essayer avec le contenu de démo',
          secondary: true,
          onPressed: () => setState(() {
            selected = true;
            file = null;
          }),
        ),
        const SizedBox(height: 20),
        const Text(
          'CONSEILS POUR TON CLIP',
          style: TextStyle(color: ScoutColors.brand, fontSize: 12),
        ),
        const SizedBox(height: 8),
        const Text(
          'Filme en vertical, garde le ballon visible et montre la compétence du début à la fin.',
          style: TextStyle(color: ScoutColors.muted),
        ),
      ],
      const SizedBox(height: 20),
      const Text(
        'Aperçu local uniquement. Vidéos et publications ne sont ni envoyées ni conservées après rechargement.',
        style: TextStyle(color: ScoutColors.muted, fontSize: 12),
      ),
    ],
  );
}
