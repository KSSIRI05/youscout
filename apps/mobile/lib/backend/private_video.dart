import 'api.dart';

typedef VideoRead =
    Future<Map<String, dynamic>> Function(
      String path, {
      Map<String, String>? query,
    });

class PrivateVideo {
  PrivateVideo.fromJson(Map<String, dynamic> json)
    : id = json['id'] as String,
      title = json['title'] as String,
      uploadStatus = json['uploadStatus'] as String,
      processingStatus = json['processingStatus'] as String?;
  final String id, title, uploadStatus;
  final String? processingStatus;
  bool get ready => uploadStatus == 'UPLOADED' && processingStatus == 'READY';
  bool get waiting =>
      uploadStatus == 'UPLOADED' &&
      (processingStatus == null || processingStatus == 'WAITING');
  String get label => switch (processingStatus) {
    'READY' => 'Prête à regarder',
    'FAILED' => 'Traitement échoué. Essaie un autre clip.',
    _ =>
      uploadStatus == 'UPLOADED' ? 'Traitement en cours…' : 'Envoi à terminer',
  };
}

/// Ces liens restent en mémoire et ne sont jamais inclus dans les diagnostics.
class PlaybackTicket {
  PlaybackTicket.fromJson(Map<String, dynamic> json)
    : video = Uri.parse(json['videoUrl'] as String),
      thumbnail = Uri.parse(json['thumbnailUrl'] as String),
      expiresAt = DateTime.parse(json['expiresAt'] as String) {
    for (final uri in [video, thumbnail]) {
      if (uri.scheme != 'https' ||
          uri.host.isEmpty ||
          uri.userInfo.isNotEmpty) {
        throw const ApiFailure(503, 'Lecture indisponible. Réessaie.');
      }
    }
    if (!expiresAt.isAfter(DateTime.now().add(const Duration(seconds: 20)))) {
      throw const ApiFailure(503, 'Le lien de lecture a expiré. Réessaie.');
    }
  }
  final Uri video, thumbnail;
  final DateTime expiresAt;
}
