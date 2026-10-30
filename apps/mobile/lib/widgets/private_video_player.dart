import 'dart:async';
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';
import '../backend/private_video.dart';

class PrivateVideoPlayer extends StatefulWidget {
  const PrivateVideoPlayer({
    required this.id,
    required this.request,
    super.key,
  });
  final String id;
  final VideoRead request;
  @override
  State<PrivateVideoPlayer> createState() => _PrivateVideoPlayerState();
}

class _PrivateVideoPlayerState extends State<PrivateVideoPlayer>
    with WidgetsBindingObserver {
  VideoPlayerController? controller;
  PlaybackTicket? ticket;
  Timer? renewal;
  bool loading = false, failed = false, started = false;
  int generation = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    load();
  }

  Future<void> load() async {
    final current = ++generation;
    renewal?.cancel();
    final old = controller;
    final position = old?.value.position ?? Duration.zero;
    final playing = old?.value.isPlaying ?? false;
    setState(() {
      controller = null;
      ticket = null;
      loading = true;
      failed = false;
    });
    await old?.dispose();
    VideoPlayerController? next;
    try {
      final fresh = PlaybackTicket.fromJson(
        await widget.request('/v1/videos/${widget.id}/playback'),
      );
      if (!mounted || current != generation) return;
      // Aucun Bearer Keycloak n'est transmis au stockage S3.
      next = VideoPlayerController.networkUrl(fresh.video);
      await next.initialize().timeout(const Duration(seconds: 20));
      if (!mounted || current != generation) {
        await next.dispose();
        return;
      }
      if (position > Duration.zero) await next.seekTo(position);
      if (playing) await next.play();
      if (!mounted || current != generation) {
        await next.dispose();
        return;
      }
      final active = next;
      next.addListener(() {
        if (!mounted || controller != active) return;
        if (active.value.hasError && !failed) {
          renewal?.cancel();
          setState(() {
            failed = true;
            ticket = null;
          });
        }
      });
      setState(() {
        controller = next;
        ticket = fresh;
        loading = false;
      });
      renewal = Timer(
        fresh.expiresAt
            .subtract(const Duration(seconds: 15))
            .difference(DateTime.now()),
        load,
      );
    } catch (_) {
      await next?.dispose();
      if (mounted && current == generation) {
        setState(() {
          loading = false;
          failed = true;
        });
      }
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) {
      controller?.pause();
    } else if (!loading &&
        ticket != null &&
        !ticket!.expiresAt.isAfter(
          DateTime.now().add(const Duration(seconds: 20)),
        )) {
      load();
    }
  }

  @override
  void dispose() {
    generation++;
    renewal?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (failed) {
      return Column(
        children: [
          const Text('Lecture interrompue ou lien expiré.'),
          TextButton(
            onPressed: loading ? null : load,
            child: const Text('Réessayer la lecture'),
          ),
        ],
      );
    }
    final active = controller;
    if (loading || active == null) {
      return const SizedBox(
        height: 160,
        child: Center(child: CircularProgressIndicator()),
      );
    }
    return Column(
      children: [
        ConstrainedBox(
          constraints: const BoxConstraints(maxHeight: 330),
          child: AspectRatio(
            aspectRatio: active.value.aspectRatio,
            child: started
                ? VideoPlayer(active)
                : Image.network(
                    ticket!.thumbnail.toString(),
                    fit: BoxFit.contain,
                    errorBuilder: (_, _, _) => const Center(
                      child: Icon(Icons.movie_outlined, size: 64),
                    ),
                  ),
          ),
        ),
        VideoProgressIndicator(active, allowScrubbing: true),
        ValueListenableBuilder<VideoPlayerValue>(
          valueListenable: active,
          builder: (context, value, _) => IconButton(
            tooltip: value.isPlaying ? 'Mettre en pause' : 'Lire la vidéo',
            onPressed: () async {
              if (!ticket!.expiresAt.isAfter(DateTime.now())) {
                await load();
                return;
              }
              setState(() => started = true);
              try {
                value.isPlaying ? await active.pause() : await active.play();
              } catch (_) {
                if (mounted) {
                  setState(() {
                    failed = true;
                    ticket = null;
                  });
                }
              }
            },
            icon: Icon(value.isPlaying ? Icons.pause : Icons.play_arrow),
          ),
        ),
      ],
    );
  }
}
