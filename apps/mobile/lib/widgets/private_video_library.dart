import 'dart:async';
import 'package:flutter/material.dart';
import '../backend/private_video.dart';
import 'private_video_player.dart';

typedef VideoPublish = Future<Map<String, dynamic>> Function(String id);

class PrivateVideoLibrary extends StatefulWidget {
  const PrivateVideoLibrary({
    required this.request,
    this.publish,
    this.refreshKey,
    this.active = true,
    super.key,
  });
  final VideoRead request;
  final VideoPublish? publish;
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
        if (!videos.any((v) => v.id == selected && v.ready)) selected = null;
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

  @override
  void dispose() {
    generation++;
    poll?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      const SizedBox(height: 24),
      Row(
        children: [
          const Expanded(
            child: Text(
              'Mes vidéos',
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
            ),
          ),
          IconButton(
            tooltip: 'Actualiser mes vidéos',
            onPressed: loading ? null : load,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      if (loading) const LinearProgressIndicator(),
      if (failed)
        const Text(
          'Impossible de charger tes vidéos. Actualise pour réessayer.',
        ),
      if (publicationMessage != null)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(
            publicationMessage!,
            key: const Key('publication-message'),
          ),
        ),
      if (!loading && !failed && videos.isEmpty)
        const Text('Tes clips apparaîtront ici après leur envoi.'),
      if (selected != null) ...[
        PrivateVideoPlayer(
          key: ValueKey(selected),
          id: selected!,
          request: widget.request,
        ),
        TextButton(
          onPressed: () => setState(() => selected = null),
          child: const Text('Fermer le lecteur'),
        ),
      ],
      for (final video in videos)
        Card(
          child: ListTile(
            title: Text(video.title),
            subtitle: Text(video.label),
            leading: Icon(
              video.ready
                  ? Icons.play_circle_outline
                  : video.processingStatus == 'FAILED'
                  ? Icons.error_outline
                  : Icons.schedule,
            ),
            trailing: video.ready
                ? video.published
                      ? const Chip(label: Text('Publié'))
                      : widget.publish == null
                      ? const Icon(Icons.chevron_right)
                      : OutlinedButton(
                          onPressed: publishing.contains(video.id)
                              ? null
                              : () => publish(video),
                          child: publishing.contains(video.id)
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Text('Publier'),
                        )
                : null,
            onTap: video.ready
                ? () => setState(() => selected = video.id)
                : null,
          ),
        ),
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
