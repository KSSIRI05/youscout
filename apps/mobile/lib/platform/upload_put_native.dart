import 'dart:io';
import 'package:image_picker/image_picker.dart';
import '../backend/api.dart';

/// Flux natif : évite de charger toute la vidéo en mémoire.
Future<void> putVideo(
  XFile file,
  Uri url,
  String type,
  void Function(double) progress,
) async {
  final client = HttpClient()..connectionTimeout = const Duration(seconds: 30);
  try {
    await (() async {
      final size = await file.length();
      final request = await client.putUrl(url);
      request.followRedirects = false;
      request.headers.set(HttpHeaders.contentTypeHeader, type);
      request.contentLength = size;
      var sent = 0;
      await for (final chunk in file.openRead()) {
        request.add(chunk);
        await request.flush();
        sent += chunk.length;
        progress(sent / size);
      }
      final response = await request.close();
      await response.drain<void>();
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw const ApiFailure(
          503,
          'S3 a refusé le transfert. Réessaie pour renouveler le lien.',
        );
      }
    })().timeout(const Duration(minutes: 10));
  } finally {
    client.close(force: true);
  }
}
