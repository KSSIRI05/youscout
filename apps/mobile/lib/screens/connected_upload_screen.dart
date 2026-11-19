import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import '../backend/api.dart';
import '../backend/connected_session.dart';
import '../backend/video_upload.dart';
import '../core/theme.dart';
import '../widgets/scout_widgets.dart';
import '../widgets/terrain.dart';
import '../widgets/private_video_library.dart';

class ConnectedUploadScreen extends StatefulWidget {
  const ConnectedUploadScreen({
    required this.session,
    this.upload,
    this.pickVideo,
    this.active = true,
    super.key,
  });
  final ConnectedSession session;
  final bool active;
  final VideoUpload? upload;
  final Future<XFile?> Function()? pickVideo;
  @override
  State<ConnectedUploadScreen> createState() => _ConnectedUploadScreenState();
}

class _ConnectedUploadScreenState extends State<ConnectedUploadScreen> {
  late final upload = widget.upload ?? VideoUpload.forSession(widget.session);
  late final owner = widget.session.profile?.subject;
  final title = TextEditingController();
  bool picking = false;
  String? selectionError;
  final Set<String> selectedSkills = {};
  final Set<String> selectedHashtags = {};

  Future<void> pick() async {
    setState(() {
      picking = true;
      selectionError = null;
    });
    try {
      final file =
          await (widget.pickVideo?.call() ??
              ImagePicker().pickVideo(source: ImageSource.gallery));
      if (!mounted || file == null) return;
      await upload.select(file);
      if (mounted) {
        setState(() {
          title.clear();
          selectedSkills.clear();
          selectedHashtags.clear();
        });
      }
    } catch (e) {
      if (mounted) {
        setState(
          () => selectionError = e is ApiFailure
              ? e.message
              : 'Impossible de lire cette vidéo. Essaie un autre fichier.',
        );
      }
    } finally {
      if (mounted) setState(() => picking = false);
    }
  }

  @override
  void dispose() {
    title.dispose();
    if (widget.upload == null) upload.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: upload,
    builder: (context, _) => ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      children: [
        const SectionHeading(
          'Envoie ton clip.',
          subtitle: 'MP4, WebM ou MOV, 100 Mo maximum.',
        ),
        const SizedBox(height: 20),
        ScoutButton(
          picking
              ? 'Ouverture…'
              : upload.file == null
              ? 'Choisir une vidéo'
              : 'Choisir une autre vidéo',
          icon: Icons.video_library_outlined,
          secondary: upload.file != null,
          onPressed: picking || upload.busy ? null : pick,
        ),
        if (selectionError != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: ScoutNotice(selectionError!, error: true),
          ),
        if (upload.file != null) ...[
          const SizedBox(height: 16),
          Row(
            children: [
              const SizedBox(
                width: 44,
                height: 44,
                child: PitchSurface(
                  marks: PitchMarks.none,
                  radius: 8,
                  stripe: 11,
                  child: Icon(Icons.movie_outlined, size: 20),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  upload.file!.name,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w500),
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          TextField(
            key: const Key('video-title'),
            controller: title,
            enabled: upload.key == null && !upload.busy,
            maxLength: 150,
            decoration: const InputDecoration(labelText: 'Titre de la vidéo'),
          ),
          const SizedBox(height: 8),
          Text('Compétences', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          if (widget.session.skills.isEmpty)
            const Text('Aucune compétence dans le catalogue.')
          else
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                for (final item in widget.session.skills)
                  FilterChip(
                    label: Text(item.name),
                    selected: selectedSkills.contains(item.id),
                    onSelected:
                        upload.key != null ||
                            (!selectedSkills.contains(item.id) &&
                                selectedSkills.length >= 10)
                        ? null
                        : (value) => setState(
                            () => value
                                ? selectedSkills.add(item.id)
                                : selectedSkills.remove(item.id),
                          ),
                  ),
              ],
            ),
          const SizedBox(height: 16),
          Text('Hashtags', style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 8),
          if (widget.session.hashtags.isEmpty)
            const Text('Aucun hashtag dans le catalogue.')
          else
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                for (final item in widget.session.hashtags)
                  FilterChip(
                    label: Text('#${item.name}'),
                    selected: selectedHashtags.contains(item.id),
                    onSelected:
                        upload.key != null ||
                            (!selectedHashtags.contains(item.id) &&
                                selectedHashtags.length >= 10)
                        ? null
                        : (value) => setState(
                            () => value
                                ? selectedHashtags.add(item.id)
                                : selectedHashtags.remove(item.id),
                          ),
                  ),
              ],
            ),
          const SizedBox(height: 10),
          Text(
            'Sélectionne jusqu’à 10 compétences et 10 hashtags du catalogue. Les compétences choisies seront notées sur la fiche du clip.',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          if (upload.busy) ...[
            const SizedBox(height: 12),
            LinearProgressIndicator(
              value: upload.phase == 'Envoi de la vidéo…'
                  ? upload.progress
                  : null,
            ),
            const SizedBox(height: 8),
            Text(
              upload.phase == 'Envoi de la vidéo…'
                  ? '${upload.phase} ${(upload.progress * 100).round()} %'
                  : upload.phase,
            ),
          ],
          if (upload.error != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: ScoutNotice(
                upload.error!,
                error: true,
                key: const Key('video-upload-error'),
              ),
            ),
          if (upload.uploaded) ...[
            const SizedBox(height: 12),
            const ScoutNotice(
              'Vidéo reçue et enregistrée.',
              key: Key('video-upload-success'),
            ),
            const SizedBox(height: 4),
            Text(
              'Suis le traitement dans Mes vidéos ci-dessous.',
              style: TextStyle(color: context.scout.gradin),
            ),
          ] else ...[
            const SizedBox(height: 16),
            ScoutButton(
              upload.transferred
                  ? 'Réessayer la confirmation'
                  : upload.key != null
                  ? 'Réessayer le transfert'
                  : 'Envoyer la vidéo',
              onPressed: upload.busy || picking
                  ? null
                  : () => upload.send(
                      title.text,
                      skillIds: selectedSkills.toList(),
                      hashtagIds: selectedHashtags.toList(),
                    ),
            ),
          ],
        ],
        const SizedBox(height: 24),
        Text(
          'La vidéo est conservée dans le stockage privé de ton compte. Le titre, les compétences et les hashtags sélectionnés seront enregistrés à la publication.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        PrivateVideoLibrary(
          active: widget.active,
          refreshKey: upload.uploaded ? upload.id : null,
          publish: (id) {
            if (!widget.session.signedIn ||
                widget.session.profile?.subject != owner) {
              throw const ApiFailure(401, 'Reconnecte-toi pour continuer.');
            }
            return widget.session.publishVideo(id);
          },
          delete: (id) {
            if (!widget.session.signedIn ||
                widget.session.profile?.subject != owner) {
              throw const ApiFailure(401, 'Reconnecte-toi pour continuer.');
            }
            return widget.session.deleteVideo(id);
          },
          request: (path, {query}) {
            if (!widget.session.signedIn ||
                widget.session.profile?.subject != owner) {
              throw const ApiFailure(401, 'Reconnecte-toi pour continuer.');
            }
            return widget.session.videoGet(path, query: query);
          },
        ),
      ],
    ),
  );
}
