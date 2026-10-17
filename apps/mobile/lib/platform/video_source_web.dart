import 'package:video_player/video_player.dart';

VideoPlayerController createVideoController(String path) =>
    VideoPlayerController.networkUrl(Uri.parse(path));
