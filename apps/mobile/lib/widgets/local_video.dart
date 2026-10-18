import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';
import '../core/theme.dart';
import '../platform/video_source.dart';

class LocalVideo extends StatefulWidget {
  const LocalVideo(this.path, {super.key});
  final String path;
  @override
  State<LocalVideo> createState() => _LocalVideoState();
}

class _LocalVideoState extends State<LocalVideo> {
  late VideoPlayerController _controller;
  String? _error;
  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _error = null;
    _controller = createVideoController(widget.path);
    _controller
        .initialize()
        .then((_) {
          if (mounted) setState(() {});
        })
        .catchError((Object _) {
          if (mounted) {
            setState(
              () => _error = 'Ce format ne peut pas être lu sur cet appareil.',
            );
          }
        });
  }

  @override
  void didUpdateWidget(LocalVideo oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.path != widget.path) {
      _controller.dispose();
      _load();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_error != null) {
      return SizedBox(
        height: 180,
        child: Center(child: Text(_error!, textAlign: TextAlign.center)),
      );
    }
    if (!_controller.value.isInitialized) {
      return const SizedBox(
        height: 180,
        child: Center(child: CircularProgressIndicator()),
      );
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: ColoredBox(
        color: Colors.black,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 330),
              child: AspectRatio(
                aspectRatio: _controller.value.aspectRatio,
                child: VideoPlayer(_controller),
              ),
            ),
            VideoProgressIndicator(
              _controller,
              allowScrubbing: true,
              colors: const VideoProgressColors(playedColor: ScoutColors.brand),
            ),
            IconButton(
              tooltip: _controller.value.isPlaying
                  ? 'Mettre en pause'
                  : 'Lire la vidéo',
              onPressed: () async {
                _controller.value.isPlaying
                    ? await _controller.pause()
                    : await _controller.play();
                if (mounted) setState(() {});
              },
              icon: Icon(
                _controller.value.isPlaying ? Icons.pause : Icons.play_arrow,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
