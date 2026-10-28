import 'dart:io';
import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:image_picker/image_picker.dart';
import 'package:youscout_mobile/backend/api.dart';
import 'package:youscout_mobile/platform/upload_put_native.dart';

void main() {
  test(
    'direct transfer sends exact bytes and signed headers without Bearer',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      addTearDown(() => server.close(force: true));
      final bytes = Uint8List.fromList(List.generate(10000, (i) => i % 256));
      final received = server.first.then((request) async {
        expect(request.method, 'PUT');
        expect(request.headers.value('Authorization'), isNull);
        expect(request.headers.contentType.toString(), 'video/mp4');
        expect(request.contentLength, bytes.length);
        expect(
          await request.fold<List<int>>([], (all, part) => all..addAll(part)),
          bytes,
        );
        request.response.statusCode = 200;
        await request.response.close();
      });
      final progress = <double>[];
      await putVideo(
        XFile.fromData(bytes),
        Uri.parse('http://127.0.0.1:${server.port}/source?signature=fake'),
        'video/mp4',
        progress.add,
      );
      await received;
      expect(progress.last, 1);
    },
  );

  test('S3 refusal stays a failure with no URL in the message', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    addTearDown(() => server.close(force: true));
    final received = server.first.then((request) async {
      await request.drain<void>();
      request.response.statusCode = 403;
      await request.response.close();
    });
    await expectLater(
      putVideo(
        XFile.fromData(Uint8List(8)),
        Uri.parse('http://127.0.0.1:${server.port}/source?signature=secret'),
        'video/mp4',
        (_) {},
      ),
      throwsA(
        isA<ApiFailure>().having(
          (e) => e.message,
          'message',
          isNot(contains('secret')),
        ),
      ),
    );
    await received;
  });
}
