import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:image_picker/image_picker.dart';
import 'api.dart';
import 'auth.dart';
import 'connected_session.dart';
import '../platform/upload_put_native.dart'
    if (dart.library.js_interop) '../platform/upload_put_web.dart'
    as transport;

typedef VideoRequest =
    Future<Map<String, dynamic>> Function(
      String path, {
      Map<String, Object>? body,
      String? idempotencyKey,
    });
typedef VideoPut =
    Future<void> Function(
      XFile file,
      Uri url,
      String contentType,
      void Function(double) progress,
    );

/// Une tentative garde sa clé et son fichier en mémoire pour éviter les doublons.
/// Après un PUT réussi, une reprise ne fait que confirmer les métadonnées.
class VideoUpload extends ChangeNotifier {
  VideoUpload({required this.request, VideoPut? put})
    : put = put ?? transport.putVideo;
  factory VideoUpload.forSession(ConnectedSession session) {
    final owner = session.profile?.subject;
    return VideoUpload(
      request: (path, {body, idempotencyKey}) async {
        if (!session.signedIn || session.profile?.subject != owner) {
          throw const ApiFailure(401, 'Reconnecte-toi pour continuer.');
        }
        return session.videoRequest(
          path,
          body: body,
          idempotencyKey: idempotencyKey,
        );
      },
    );
  }
  final VideoRequest request;
  final VideoPut put;
  static const maxSize = 100 * 1024 * 1024;
  XFile? file;
  String? title, contentType, key, id, error;
  bool busy = false, transferred = false, uploaded = false, _disposed = false;
  double progress = 0;
  String phase = '';

  static String? typeOf(XFile file) {
    final mime = file.mimeType?.toLowerCase();
    if (['video/mp4', 'video/webm', 'video/quicktime'].contains(mime)) {
      return mime;
    }
    return switch (file.name.split('.').last.toLowerCase()) {
      'mp4' => 'video/mp4',
      'webm' => 'video/webm',
      'mov' => 'video/quicktime',
      _ => null,
    };
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  Future<void> select(XFile selected) async {
    if (busy) return;
    final size = await selected.length();
    if (size <= 0 || size > maxSize || typeOf(selected) == null) {
      throw const ApiFailure(
        400,
        'Choisis une vidéo MP4, WebM ou MOV de 100 Mo maximum, non vide.',
      );
    }
    file = selected;
    title = key = id = error = null;
    contentType = typeOf(selected);
    transferred = uploaded = false;
    progress = 0;
    phase = '';
    _notify();
  }

  Future<void> send(String value) async {
    if (busy || uploaded || file == null || _disposed) return;
    final normalized = value.trim();
    if (normalized.isEmpty ||
        normalized.length > 150 ||
        normalized.contains('\u0000')) {
      error = 'Indique un titre de 1 à 150 caractères.';
      _notify();
      return;
    }
    // Le titre reste figé dès la première demande, même si sa réponse est perdue.
    title ??= normalized;
    key ??= List.generate(
      24,
      (_) => Random.secure().nextInt(256),
    ).map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    busy = true;
    error = null;
    _notify();
    try {
      if (!transferred) {
        phase = 'Préparation du transfert…';
        progress = 0;
        _notify();
        final ticket = await request(
          '/v1/videos/uploads',
          body: {
            'title': title!,
            'contentType': contentType!,
            'sizeBytes': await file!.length(),
          },
          idempotencyKey: key,
        );
        if (_disposed) return;
        id = ticket['id'] as String;
        final upload = ticket['upload'] as Map<String, dynamic>;
        final url = Uri.parse(upload['url'] as String);
        final headers = upload['headers'] as Map<String, dynamic>;
        // Ne transmet jamais le Bearer à S3, ni des en-têtes arbitraires du serveur.
        if (upload['method'] != 'PUT' ||
            url.scheme != 'https' ||
            url.userInfo.isNotEmpty ||
            headers['Content-Type'] != contentType ||
            !DateTime.parse(
              upload['expiresAt'] as String,
            ).isAfter(DateTime.now())) {
          throw const ApiFailure(
            503,
            'Le transfert ne peut pas démarrer. Réessaie.',
          );
        }
        phase = 'Envoi de la vidéo…';
        _notify();
        await put(file!, url, contentType!, (value) {
          progress = value.clamp(0, 1);
          _notify();
        });
        transferred = true;
      }
      if (_disposed) return;
      phase = 'Confirmation de la réception…';
      _notify();
      final result = await request('/v1/videos/$id/complete');
      if (result['id'] != id || result['status'] != 'UPLOADED') {
        throw const ApiFailure(503, 'Réception non confirmée. Réessaie.');
      }
      uploaded = true;
      phase = 'Vidéo reçue et enregistrée.';
    } catch (e) {
      // Les exceptions réseau peuvent contenir l'URL signée : ne jamais les afficher.
      error = e is AuthFailure
          ? e.message
          : e is ApiFailure
          ? switch (e.status) {
              401 => 'La session a expiré. Reconnecte-toi.',
              403 => 'Accès refusé. Vérifie tes droits puis réessaie.',
              409 =>
                transferred
                    ? 'Le fichier ne peut pas être confirmé. Réessaie ou sélectionne à nouveau la vidéo.'
                    : 'Ce transfert est en conflit. Sélectionne à nouveau la vidéo.',
              _ => e.message,
            }
          : 'Connexion interrompue. Réessaie le transfert.';
    } finally {
      busy = false;
      _notify();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
