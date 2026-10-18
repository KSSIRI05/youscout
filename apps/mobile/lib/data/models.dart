class Talent {
  const Talent({
    required this.id,
    required this.name,
    required this.handle,
    required this.city,
    required this.position,
    required this.initials,
    required this.skills,
    this.bio = 'Chaque entraînement est une occasion de progresser.',
  });
  final String id, name, handle, city, position, initials, bio;
  final List<String> skills;
}

class ScoutComment {
  const ScoutComment({
    required this.id,
    required this.authorId,
    required this.text,
    this.parentId,
  });
  final String id, authorId, text;
  final String? parentId;
}

class ScoutPost {
  ScoutPost({
    required this.id,
    required this.authorId,
    required this.description,
    required this.skills,
    required this.hashtags,
    required this.createdAt,
    this.mediaPath,
    this.isDemo = true,
    this.initialLikes = 0,
    List<ScoutComment>? comments,
  }) : comments = List.of(comments ?? <ScoutComment>[]);
  final String id, authorId, description;
  final List<String> skills, hashtags;
  final DateTime createdAt;
  final String? mediaPath;
  final bool isDemo;
  final int initialLikes;
  final List<ScoutComment> comments;
}

class ChatMessage {
  const ChatMessage({
    required this.text,
    required this.fromMe,
    required this.at,
  });
  final String text;
  final bool fromMe;
  final DateTime at;
}

class ContentReport {
  const ContentReport(this.targetId, this.reason);
  final String targetId, reason;
}
