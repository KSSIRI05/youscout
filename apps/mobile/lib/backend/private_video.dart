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
      processingStatus = json['processingStatus'] as String?,
      publicationStatus = json['publicationStatus'] as String? ?? 'UNPUBLISHED';
  final String id, title, uploadStatus;
  final String? processingStatus, publicationStatus;
  bool get ready => uploadStatus == 'UPLOADED' && processingStatus == 'READY';
  bool get published => publicationStatus == 'PUBLISHED';
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

class FeedVideo {
  const FeedVideo(
    this.id,
    this.title,
    this.publishedAt,
    this.ownerSubject, [
    this.skills = const [],
    this.hashtags = const [],
  ]);
  factory FeedVideo.fromJson(Map<String, dynamic> json) {
    List<String> names(Object? value) => (value as List<dynamic>? ?? const [])
        .map((item) => (item as Map<String, dynamic>)['name'] as String)
        .toList(growable: false);
    return FeedVideo(
      json['videoId'] as String,
      json['title'] as String,
      DateTime.parse(json['publishedAt'] as String),
      json['ownerSubject'] as String,
      names(json['skills']),
      names(json['hashtags']),
    );
  }
  final String id, title, ownerSubject;
  final DateTime publishedAt;
  final List<String> skills, hashtags;
}
