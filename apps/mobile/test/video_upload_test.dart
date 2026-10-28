import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:image_picker/image_picker.dart';
import 'package:youscout_mobile/backend/api.dart';
import 'package:youscout_mobile/backend/config.dart';
import 'package:youscout_mobile/backend/connected_session.dart';
import 'package:youscout_mobile/backend/video_upload.dart';
import 'package:youscout_mobile/screens/connected_upload_screen.dart';
import 'connected_session_test.dart' show ApiFixture, FakeAuth;

XFile clip({String name = 'clip.mp4', int size = 8}) =>
    XFile.fromData(Uint8List(size), name: name, path: name, length: size);

class UploadFixture {
  final paths = <String>[],
      keys = <String?>[],
      bodies = <Map<String, Object>?>[];
  int puts = 0;
  bool failCreate = false, failPut = false, failComplete = false;
  Future<Map<String, dynamic>> request(
    String path, {
    Map<String, Object>? body,
    String? idempotencyKey,
  }) async {
    paths.add(path);
    keys.add(idempotencyKey);
    bodies.add(body);
    if (path.endsWith('/uploads')) {
      if (failCreate) {
        failCreate = false;
        throw Exception('lost response');
      }
      return {
        'id': 'video-id',
        'upload': {
          'method': 'PUT',
          'url': 'https://example.test/private?signature=secret',
          'headers': {'Content-Type': 'video/mp4', 'Content-Length': '8'},
          'expiresAt': DateTime.now()
              .add(const Duration(minutes: 5))
              .toIso8601String(),
        },
      };
    }
    expect(path, '/v1/videos/video-id/complete');
    if (failComplete) {
      failComplete = false;
      throw const ApiFailure(503, 'Confirmation indisponible.');
    }
    return {'id': 'video-id', 'status': 'UPLOADED'};
  }

  Future<void> put(
    XFile file,
    Uri url,
    String type,
    void Function(double) progress,
  ) async {
    puts++;
    expect(type, 'video/mp4');
    expect(await file.length(), 8);
    progress(.5);
    progress(1);
    if (failPut) {
      failPut = false;
      throw Exception('secret signed URL');
    }
  }

  VideoUpload controller() => VideoUpload(request: request, put: put);
}

void main() {
  test('rejects insecure transfer URL before sending any bytes', () async {
    final fixture = UploadFixture();
    final controller = VideoUpload(
      request: (path, {body, idempotencyKey}) async {
        final ticket = await fixture.request(
          path,
          body: body,
          idempotencyKey: idempotencyKey,
        );
        (ticket['upload'] as Map)['url'] = 'http://example.test/source';
        return ticket;
      },
      put: fixture.put,
    );
    addTearDown(controller.dispose);
    await controller.select(clip());
    await controller.send('Clip');
    expect(fixture.puts, 0);
    expect(controller.uploaded, isFalse);
    expect(controller.error, isNotNull);
  });

  test('double click cannot start a second simultaneous transfer', () async {
    final fixture = UploadFixture();
    final pending = Completer<void>();
    var count = 0;
    final controller = VideoUpload(
      request: fixture.request,
      put: (file, uri, type, progress) async {
        count++;
        await pending.future;
      },
    );
    addTearDown(controller.dispose);
    await controller.select(clip());
    final first = controller.send('Clip');
    await controller.send('Clip');
    pending.complete();
    await first;
    expect(count, 1);
    expect(fixture.paths, hasLength(2));
  });

  test('prepare, direct PUT, then complete; duplicates are disabled', () async {
    final fixture = UploadFixture();
    final controller = fixture.controller();
    addTearDown(controller.dispose);
    await controller.select(clip());
    await controller.send(' Mon clip ');
    expect(fixture.paths, [
      '/v1/videos/uploads',
      '/v1/videos/video-id/complete',
    ]);
    expect(fixture.bodies.first, {
      'title': 'Mon clip',
      'contentType': 'video/mp4',
      'sizeBytes': 8,
    });
    expect(fixture.keys.first, matches(RegExp(r'^[0-9a-f]{48}$')));
    expect(controller.uploaded, isTrue);
    expect(controller.progress, 1);
    await controller.send('Mon clip');
    expect(fixture.puts, 1);
  });

  test('lost create response reuses key and original body', () async {
    final fixture = UploadFixture()..failCreate = true;
    final controller = fixture.controller();
    addTearDown(controller.dispose);
    await controller.select(clip());
    await controller.send('Premier titre');
    expect(controller.error, isNotNull);
    await controller.send('Titre changé');
    expect(fixture.keys[0], fixture.keys[1]);
    expect(fixture.bodies[0], fixture.bodies[1]);
    expect(controller.uploaded, isTrue);
  });

  test('failed PUT obtains fresh ticket with same key and hides URL', () async {
    final fixture = UploadFixture()..failPut = true;
    final controller = fixture.controller();
    addTearDown(controller.dispose);
    await controller.select(clip());
    await controller.send('Clip');
    expect(fixture.paths, ['/v1/videos/uploads']);
    expect(controller.error, isNot(contains('secret')));
    await controller.send('Clip');
    expect(fixture.puts, 2);
    expect(fixture.keys[0], fixture.keys[1]);
    expect(controller.uploaded, isTrue);
  });

  test('confirmation retry never PUTs or allocates again', () async {
    final fixture = UploadFixture()..failComplete = true;
    final controller = fixture.controller();
    addTearDown(controller.dispose);
    await controller.select(clip());
    await controller.send('Clip');
    expect(controller.transferred, isTrue);
    expect(controller.uploaded, isFalse);
    await controller.send('Clip');
    expect(fixture.puts, 1);
    expect(fixture.paths.where((p) => p.endsWith('/uploads')), hasLength(1));
    expect(controller.uploaded, isTrue);
  });

  test('reject invalid files and titles before network', () async {
    final fixture = UploadFixture();
    final controller = fixture.controller();
    addTearDown(controller.dispose);
    for (final file in [
      clip(name: 'bad.exe'),
      clip(size: 0),
      XFile.fromData(
        Uint8List(1),
        name: 'huge.mp4',
        length: VideoUpload.maxSize + 1,
      ),
    ]) {
      await expectLater(controller.select(file), throwsA(isA<ApiFailure>()));
    }
    await controller.select(clip());
    for (final title in ['', 'a' * 151, 'a\u0000b']) {
      await controller.send(title);
      expect(controller.error, isNotNull);
    }
    expect(fixture.paths, isEmpty);
  });

  test('dispose after PUT prevents confirmation', () async {
    final fixture = UploadFixture();
    late VideoUpload controller;
    controller = VideoUpload(
      request: fixture.request,
      put: (file, uri, type, progress) async {
        controller.dispose();
      },
    );
    await controller.select(clip());
    await controller.send('Clip');
    expect(fixture.paths, ['/v1/videos/uploads']);
  });

  test(
    'Keycloak refresh keeps idempotency header and logout blocks requests',
    () async {
      final fixture = ApiFixture(), auth = FakeAuth();
      final requests = <http.Request>[];
      final session = ConnectedSession(
        auth: auth,
        api: BackendApi(
          const BackendConfig(),
          client: MockClient((request) async {
            if (!request.url.path.startsWith('/v1/videos/')) {
              return fixture.respond(request);
            }
            requests.add(request);
            if (request.headers['Authorization'] == 'Bearer access-first') {
              return http.Response('{}', 401);
            }
            return http.Response(jsonEncode({'id': 'video-id'}), 201);
          }),
        ),
      );
      addTearDown(session.dispose);
      await session.login();
      await session.videoRequest(
        '/v1/videos/uploads',
        body: {'title': 'Clip'},
        idempotencyKey: 'one-stable-key-1234',
      );
      expect(auth.renewals, 1);
      expect(
        requests.map((r) => r.headers['Idempotency-Key']),
        everyElement('one-stable-key-1234'),
      );
      final controller = VideoUpload.forSession(session);
      addTearDown(controller.dispose);
      await session.logout();
      await expectLater(
        controller.request('/v1/videos/uploads'),
        throwsA(isA<ApiFailure>()),
      );
    },
  );

  testWidgets('connected upload displays confirmed success only after retry', (
    tester,
  ) async {
    final fixture = UploadFixture()..failComplete = true;
    final controller = fixture.controller();
    final session = ApiFixture().session(FakeAuth());
    addTearDown(controller.dispose);
    addTearDown(session.dispose);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ConnectedUploadScreen(
            session: session,
            upload: controller,
            pickVideo: () async => clip(),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Choisir une vidéo'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('video-title')), 'Mon clip');
    await tester.tap(find.text('Envoyer la vidéo'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('video-upload-success')), findsNothing);
    expect(find.byKey(const Key('video-upload-error')), findsOneWidget);
    await tester.tap(find.text('Réessayer la confirmation'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('video-upload-success')), findsOneWidget);
    expect(fixture.puts, 1);
  });
}
