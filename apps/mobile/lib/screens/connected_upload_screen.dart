import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import '../backend/api.dart';
import '../backend/connected_session.dart';
import '../backend/video_upload.dart';
import '../widgets/scout_widgets.dart';

class ConnectedUploadScreen extends StatefulWidget {
  const ConnectedUploadScreen({
    required this.session,
    this.upload,
    this.pickVideo,
    super.key,
  });
  final ConnectedSession session;
  final VideoUpload? upload;
  final Future<XFile?> Function()? pickVideo;
  @override
  State<ConnectedUploadScreen> createState() => _ConnectedUploadScreenState();
}

class _ConnectedUploadScreenState extends State<ConnectedUploadScreen> {
  late final upload = widget.upload ?? VideoUpload.forSession(widget.session);
  final title = TextEditingController();
  bool picking = false;
  String? selectionError;

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
      if (mounted) setState(title.clear);
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
      padding: const EdgeInsets.all(24),
      children: [
        const SectionHeading(
          'Envoie ton clip.',
          subtitle: 'MP4, WebM ou MOV • 100 Mo maximum',
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
        if (selectionError != null) Text(selectionError!),
        if (upload.file != null) ...[
          const SizedBox(height: 16),
          Text(upload.file!.name),
          const SizedBox(height: 16),
          TextField(
            key: const Key('video-title'),
            controller: title,
            enabled: upload.key == null && !upload.busy,
            maxLength: 150,
            decoration: const InputDecoration(labelText: 'Titre de la vidéo'),
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
              child: Text(
                upload.error!,
                key: const Key('video-upload-error'),
                style: const TextStyle(color: Colors.orangeAccent),
              ),
            ),
          if (upload.uploaded) ...[
            const SizedBox(height: 12),
            const Text(
              'Vidéo reçue et enregistrée.',
              key: Key('video-upload-success'),
            ),
            const Text(
              'Le traitement et la publication dans le fil seront disponibles prochainement.',
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
                  : () => upload.send(title.text),
            ),
          ],
        ],
        const SizedBox(height: 24),
        const Text(
          'La vidéo est conservée dans le stockage privé de ton compte. Le titre est enregistré ; les compétences et hashtags ne sont pas encore associés aux vidéos.',
        ),
      ],
    ),
  );
}
