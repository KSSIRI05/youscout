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
      publicationStatus = json['publicationStatus'] as String? ?? 'UNPUBLISHED',
      removed = json['moderationStatus'] == 'REMOVED';
  final String id, title, uploadStatus;
  final String? processingStatus, publicationStatus;

  /// Retirée par la modération : visible du propriétaire, mais ni lisible ni publiable.
  final bool removed;
  bool get ready => uploadStatus == 'UPLOADED' && processingStatus == 'READY';
  bool get published => publicationStatus == 'PUBLISHED';
  bool get waiting =>
      uploadStatus == 'UPLOADED' &&
      (processingStatus == null || processingStatus == 'WAITING');
  String get label => removed
      ? 'Retirée par la modération'
      : switch (processingStatus) {
          'READY' => 'Prête à regarder',
          'FAILED' => 'Traitement échoué. Essaie un autre clip.',
          _ =>
            uploadStatus == 'UPLOADED'
                ? 'Traitement en cours…'
                : 'Envoi à terminer',
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
    this.skillIds = const [],
  ]);
  factory FeedVideo.fromJson(Map<String, dynamic> json) {
    List<String> field(Object? value, String key) =>
        (value as List<dynamic>? ?? const [])
            .map((item) => (item as Map<String, dynamic>)[key] as String)
            .toList(growable: false);
    return FeedVideo(
      json['videoId'] as String,
      json['title'] as String,
      DateTime.parse(json['publishedAt'] as String),
      json['ownerSubject'] as String,
      field(json['skills'], 'name'),
      field(json['hashtags'], 'name'),
      field(json['skills'], 'id'),
    );
  }
  final String id, title, ownerSubject;
  final DateTime publishedAt;
  final List<String> skills, hashtags;

  /// Identifiants des compétences, dans le même ordre que [skills], pour la notation.
  final List<String> skillIds;
}

/// Résumé Rating d'une compétence : moyenne, nombre de notes et note de l'appelant.
class SkillRating {
  const SkillRating({this.count = 0, this.average, this.myScore});
  final int count;
  final double? average;
  final int? myScore;

  /// Indexe la réponse de Rating par compétence ; une compétence absente n'est pas encore notée.
  static Map<String, SkillRating> bySkill(Map<String, dynamic> json) => {
    for (final item in (json['skills'] as List<dynamic>? ?? const []))
      (item as Map<String, dynamic>)['skillId'] as String: SkillRating(
        count: (item['count'] as num).toInt(),
        average: (item['average'] as num?)?.toDouble(),
        myScore: (item['myScore'] as num?)?.toInt(),
      ),
  };

  String get label => count == 0
      ? 'Pas encore notée'
      : '${average!.toStringAsFixed(1)} / 5 · $count note${count > 1 ? 's' : ''}';
}
