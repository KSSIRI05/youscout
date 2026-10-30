import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';
import 'package:youscout_mobile/backend/api.dart';
import 'package:youscout_mobile/backend/private_video.dart';
import 'package:youscout_mobile/widgets/private_video_library.dart';
import 'package:youscout_mobile/widgets/private_video_player.dart';

Map<String, dynamic> ticket() => {
  'videoUrl': 'https://storage.test/video.mp4?signature=private',
  'thumbnailUrl': 'https://storage.test/thumbnail.jpg?signature=private',
  'expiresAt': DateTime.now()
      .add(const Duration(seconds: 30))
      .toIso8601String(),
};

class PlayerPlatform extends VideoPlayerPlatform {
  final sources = <DataSource>[];
  final disposed = <int>[];
  final seeks = <Duration>[];
  final streams = <int, StreamController<VideoEvent>>{};
  int plays = 0;
  @override
  Future<void> init() async {}
  @override
  Future<int> createWithOptions(VideoCreationOptions options) async {
    sources.add(options.dataSource);
    streams[sources.length] = StreamController<VideoEvent>()
      ..add(
        VideoEvent(
          eventType: VideoEventType.initialized,
          duration: const Duration(minutes: 3),
          size: const Size(320, 240),
        ),
      );
    return sources.length;
  }

  @override
  Stream<VideoEvent> videoEventsFor(int playerId) => streams[playerId]!.stream;
  @override
  Future<void> dispose(int playerId) async {
    disposed.add(playerId);
  }

  @override
  Future<void> setLooping(int playerId, bool looping) async {}
  @override
  Future<void> setVolume(int playerId, double volume) async {}
  @override
  Future<void> setPlaybackSpeed(int playerId, double speed) async {}
  @override
  Future<void> play(int playerId) async {
    plays++;
  }

  @override
  Future<void> pause(int playerId) async {}
  @override
  Future<void> seekTo(int playerId, Duration position) async {
    seeks.add(position);
  }

  @override
  Future<Duration> getPosition(int playerId) async =>
      const Duration(seconds: 12);
  @override
  Widget buildViewWithOptions(VideoViewOptions options) =>
      const SizedBox(key: Key('private-video-surface'));
}

void main() {
  testWidgets('inactive video tab stops polling and reloads when reopened', (
    tester,
  ) async {
    var calls = 0;
    Future<Map<String, dynamic>> read(
      String path, {
      Map<String, String>? query,
    }) async {
      calls++;
      return {
        'hasMore': false,
        'items': [
          {
            'id': 'one',
            'title': 'Clip',
            'uploadStatus': 'UPLOADED',
            'processingStatus': 'WAITING',
          },
        ],
      };
    }

    Widget page(bool active) => MaterialApp(
      home: Scaffold(
        body: PrivateVideoLibrary(request: read, active: active),
      ),
    );
    await tester.pumpWidget(page(false));
    await tester.pumpAndSettle();
    expect(calls, 0);
    await tester.pumpWidget(page(true));
    await tester.pumpAndSettle();
    expect(calls, 1);
    await tester.pumpWidget(page(false));
    await tester.pump(const Duration(seconds: 10));
    expect(calls, 1);
    await tester.pumpWidget(const SizedBox());
  });
  test('playback ticket rejects expired and insecure links', () {
    for (final data in [
      {...ticket(), 'videoUrl': 'http://storage.test/video'},
      {...ticket(), 'thumbnailUrl': 'https://user:password@storage.test/image'},
      {
        ...ticket(),
        'expiresAt': DateTime.now()
            .subtract(const Duration(seconds: 1))
            .toIso8601String(),
      },
    ]) {
      expect(() => PlaybackTicket.fromJson(data), throwsA(isA<ApiFailure>()));
    }
  });

  testWidgets('polls processing until READY then stops and labels failures', (
    tester,
  ) async {
    var calls = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: PrivateVideoLibrary(
              request: (path, {query}) async {
                calls++;
                return {
                  'hasMore': false,
                  'items': [
                    {
                      'id': 'one',
                      'title': 'Mon clip',
                      'uploadStatus': 'UPLOADED',
                      'processingStatus': calls == 1 ? 'WAITING' : 'READY',
                    },
                    {
                      'id': 'two',
                      'title': 'Média invalide',
                      'uploadStatus': 'UPLOADED',
                      'processingStatus': 'FAILED',
                    },
                  ],
                };
              },
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Traitement en cours…'), findsOneWidget);
    expect(
      find.text('Traitement échoué. Essaie un autre clip.'),
      findsOneWidget,
    );
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();
    expect(find.text('Prête à regarder'), findsOneWidget);
    await tester.pump(const Duration(seconds: 10));
    expect(calls, 2);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('disposal cancels status polling and ignores late responses', (
    tester,
  ) async {
    final pending = Completer<Map<String, dynamic>>();
    var calls = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: PrivateVideoLibrary(
          request: (path, {query}) {
            calls++;
            return pending.future;
          },
        ),
      ),
    );
    await tester.pumpWidget(const SizedBox());
    pending.complete({
      'hasMore': false,
      'items': [
        {
          'id': 'one',
          'title': 'Old account',
          'uploadStatus': 'UPLOADED',
          'processingStatus': 'WAITING',
        },
      ],
    });
    await tester.pump(const Duration(seconds: 10));
    expect(calls, 1);
    expect(find.text('Old account'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'player renews private links, keeps position, never sends Bearer, and disposes',
    (tester) async {
      final previous = VideoPlayerPlatform.instance;
      final platform = PlayerPlatform();
      VideoPlayerPlatform.instance = platform;
      addTearDown(() => VideoPlayerPlatform.instance = previous);
      var calls = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PrivateVideoPlayer(
              id: 'one',
              request: (path, {query}) async {
                expect(path, '/v1/videos/one/playback');
                calls++;
                return ticket();
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Lire la vidéo'));
      await tester.pump(const Duration(seconds: 1));
      await tester.pump(const Duration(seconds: 15));
      for (var i = 0; i < 10; i++) {
        await tester.pump(const Duration(milliseconds: 10));
      }
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 10)),
      );
      await tester.pump();
      expect(calls, 2);
      expect(platform.sources, hasLength(2));
      expect(platform.sources.every((s) => s.httpHeaders.isEmpty), isTrue);
      expect(platform.seeks, contains(const Duration(seconds: 12)));
      expect(platform.plays, 2);
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 60));
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 10)),
      );
      await tester.pump();
      expect(calls, 2);
      expect(platform.disposed, containsAll([1, 2]));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('player errors do not expose signed URLs and allow retry', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: PrivateVideoPlayer(
            id: 'one',
            request: (path, {query}) async {
              throw Exception('https://storage.test/video?signature=private');
            },
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Lecture interrompue ou lien expiré.'), findsOneWidget);
    expect(find.text('Réessayer la lecture'), findsOneWidget);
    expect(find.textContaining('signature='), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });
}
