import 'dart:async';
import 'package:flutter/material.dart';
import '../backend/private_video.dart';
import '../core/theme.dart';
import 'private_video_player.dart';
import 'terrain.dart';

typedef VideoPublish = Future<Map<String, dynamic>> Function(String id);
typedef VideoDelete = Future<void> Function(String id);

class PrivateVideoLibrary extends StatefulWidget {
  const PrivateVideoLibrary({
    required this.request,
    this.publish,
    this.delete,
    this.refreshKey,
    this.active = true,
    super.key,
  });
  final VideoRead request;
  final VideoPublish? publish;
  final VideoDelete? delete;
  final String? refreshKey;
  final bool active;
  @override
  State<PrivateVideoLibrary> createState() => _PrivateVideoLibraryState();
}

class _PrivateVideoLibraryState extends State<PrivateVideoLibrary> {
  List<PrivateVideo> videos = [];
  Timer? poll;
  String? selected;
  bool loading = false, failed = false, more = false;
  final Set<String> publishing = {};
  final Set<String> deleting = {};
  String? publicationMessage;
  int offset = 0, generation = 0;
  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  void didUpdateWidget(PrivateVideoLibrary oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.active) {
      generation++;
      poll?.cancel();
      selected = null;
      loading = false;
    } else if (!oldWidget.active || widget.refreshKey != oldWidget.refreshKey) {
      offset = 0;
      load();
    }
  }

  Future<void> load() async {
    if (!widget.active) return;
    final current = ++generation;
    poll?.cancel();
    setState(() {
      loading = true;
      failed = false;
    });
    try {
      final data = await widget.request(
        '/v1/videos',
        query: {'offset': '$offset'},
      );
      if (!mounted || generation != current) return;
      setState(() {
        videos = (data['items'] as List)
            .map((v) => PrivateVideo.fromJson(v as Map<String, dynamic>))
            .toList();
        more = data['hasMore'] as bool;
        if (!videos.any((v) => v.id == selected && v.ready && !v.removed)) {
          selected = null;
        }
      });
      if (videos.any((v) => v.waiting)) {
        poll = Timer(const Duration(seconds: 4), load);
      }
    } catch (_) {
      if (mounted && generation == current) setState(() => failed = true);
    } finally {
      if (mounted && generation == current) setState(() => loading = false);
    }
  }

  Future<void> publish(PrivateVideo video) async {
    final action = widget.publish;
    if (action == null || !widget.active || publishing.contains(video.id)) {
      return;
    }
    setState(() {
      publishing.add(video.id);
      publicationMessage = null;
    });
    try {
      await action(video.id);
      if (!mounted) return;
      setState(() => publicationMessage = 'Vidéo publiée dans le fil.');
      await load();
    } catch (_) {
      if (mounted) {
        setState(
          () => publicationMessage = 'Publication impossible. Réessaie.',
        );
      }
    } finally {
      if (mounted) setState(() => publishing.remove(video.id));
    }
  }

  Future<void> delete(PrivateVideo video) async {
    final action = widget.delete;
    if (action == null || !widget.active || deleting.contains(video.id)) {
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text('Supprimer « ${video.title} » ?'),
        content: const Text(
          'La vidéo sera retirée de ta bibliothèque et du fil, et ses fichiers seront effacés. Cette action est définitive.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Supprimer'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      deleting.add(video.id);
      publicationMessage = null;
    });
    try {
      await action(video.id);
      if (!mounted) return;
      setState(() {
        videos = videos.where((item) => item.id != video.id).toList();
        if (selected == video.id) selected = null;
        publicationMessage = 'Vidéo supprimée.';
      });
      await load();
    } catch (_) {
      if (mounted) {
        setState(
          () => publicationMessage = 'Suppression impossible. Réessaie.',
        );
      }
    } finally {
      if (mounted) setState(() => deleting.remove(video.id));
    }
  }

  Widget? status(PrivateVideo video) => video.removed
      ? const ScoutTag('Retirée', tone: TagTone.alerte)
      : video.ready
      ? video.published
            ? const ScoutTag('Publié', tone: TagTone.valide)
            : widget.publish == null
            ? const Icon(Icons.chevron_right)
            : FilledButton(
                style: FilledButton.styleFrom(
                  minimumSize: const Size(48, 40),
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                ),
                onPressed: publishing.contains(video.id)
                    ? null
                    : () => publish(video),
                child: publishing.contains(video.id)
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('Publier'),
              )
      : null;

  @override
  void dispose() {
    generation++;
    poll?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 28),
        Row(
          children: [
            Expanded(
              child: Text(
                'Mes vidéos',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
            ),
            IconButton(
              tooltip: 'Actualiser mes vidéos',
              onPressed: loading ? null : load,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        const SizedBox(height: 4),
        if (loading) const LinearProgressIndicator(),
        if (failed)
          const ScoutNotice(
            'Impossible de charger tes vidéos. Actualise pour réessayer.',
            error: true,
          ),
        if (publicationMessage != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: ScoutNotice(
              publicationMessage!,
              key: const Key('publication-message'),
              error: publicationMessage!.contains('impossible'),
            ),
          ),
        if (!loading && !failed && videos.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Text(
              'Tes clips apparaîtront ici après leur envoi.',
              style: TextStyle(color: p.gradin),
            ),
          ),
        if (selected != null) ...[
          const SizedBox(height: 8),
          PrivateVideoPlayer(
            key: ValueKey(selected),
            id: selected!,
            request: widget.request,
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: () => setState(() => selected = null),
              child: const Text('Fermer le lecteur'),
            ),
          ),
        ],
        for (final video in videos) ...[
          Divider(height: 1, color: p.ligne),
          InkWell(
            onTap: video.ready && !video.removed
                ? () => setState(() => selected = video.id)
                : null,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 10),
              child: Row(
                children: [
                  SizedBox(
                    width: 52,
                    height: 52,
                    child: video.removed
                        ? DecoratedBox(
                            decoration: BoxDecoration(
                              color: p.raised,
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Icon(Icons.block, color: p.gradin),
                          )
                        : PitchSurface(
                            marks: PitchMarks.none,
                            radius: 8,
                            stripe: 13,
                            child: Icon(
                              video.ready
                                  ? Icons.play_arrow_rounded
                                  : video.processingStatus == 'FAILED'
                                  ? Icons.error_outline
                                  : Icons.schedule,
                            ),
                          ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          video.title,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontWeight: FontWeight.w500),
                        ),
                        Text(
                          video.label,
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(
                                color: video.processingStatus == 'FAILED'
                                    ? p.alerte
                                    : null,
                              ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  ?status(video),
                  if (widget.delete != null)
                    IconButton(
                      key: Key('delete-${video.id}'),
                      tooltip: 'Supprimer la vidéo',
                      onPressed: deleting.contains(video.id)
                          ? null
                          : () => delete(video),
                      icon: const Icon(Icons.delete_outline),
                    ),
                ],
              ),
            ),
          ),
        ],
        if (offset > 0 || more)
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              TextButton(
                onPressed: loading || offset == 0
                    ? null
                    : () {
                        offset -= 20;
                        selected = null;
                        load();
                      },
                child: const Text('Précédentes'),
              ),
              TextButton(
                onPressed: loading || !more
                    ? null
                    : () {
                        offset += 20;
                        selected = null;
                        load();
                      },
                child: const Text('Suivantes'),
              ),
            ],
          ),
      ],
    );
  }
}
