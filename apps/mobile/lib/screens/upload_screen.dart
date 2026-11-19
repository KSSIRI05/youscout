import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import '../core/theme.dart';
import '../state/scout_store.dart';
import '../widgets/local_video.dart';
import '../widgets/scout_widgets.dart';
import '../widgets/terrain.dart';

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
  Widget build(BuildContext context) {
    final p = context.scout;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
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
            SizedBox(
              height: 180,
              child: PitchSurface(
                radius: 12,
                child: Center(
                  child: Text(
                    'Illustration de démonstration',
                    style: TextStyle(color: p.surPelouse, fontSize: 13),
                  ),
                ),
              ),
            ),
          if (file != null) ...[
            const SizedBox(height: 12),
            Text(file!.name, style: Theme.of(context).textTheme.bodySmall),
          ],
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
                Text(
                  'Compétences démontrées',
                  style: Theme.of(context).textTheme.titleSmall,
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
          const SizedBox(
            height: 240,
            child: PitchSurface(
              radius: 12,
              child: Center(child: Icon(Icons.videocam_outlined, size: 48)),
            ),
          ),
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
          Text(
            'Conseils pour ton clip',
            style: Theme.of(context).textTheme.titleSmall,
          ),
          const SizedBox(height: 6),
          Text(
            'Filme en vertical, garde le ballon visible et montre la compétence du début à la fin.',
            style: TextStyle(color: p.gradin),
          ),
        ],
        const SizedBox(height: 20),
        Text(
          'Aperçu local uniquement. Vidéos et publications ne sont ni envoyées ni conservées après rechargement.',
          style: TextStyle(color: p.gradin, fontSize: 12),
        ),
      ],
    );
  }
}
