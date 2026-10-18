import 'package:flutter/foundation.dart';
import '../data/demo_repository.dart';
import '../data/models.dart';

class ScoutStore extends ChangeNotifier {
  ScoutStore({ScoutRepository? repository}) {
    final source = repository ?? DemoScoutRepository();
    talents = List.of(source.loadTalents());
    _posts = source.loadPosts();
    conversations = source.loadConversations();
  }
  late List<Talent> talents;
  late final List<ScoutPost> _posts;
  late final Map<String, List<ChatMessage>> conversations;
  final Set<String> liked = {},
      followed = {},
      followedHashtags = {},
      blocked = {};
  final Map<String, int> ratings = {};
  final List<ContentReport> reports = [];
  bool inDemo = false;
  int _sequence = 0;
  String _id(String prefix) =>
      '$prefix-${DateTime.now().microsecondsSinceEpoch}-${_sequence++}';
  Talent talent(String id) => talents.firstWhere((t) => t.id == id);
  Talent get me => talent('me');
  List<ScoutPost> get posts =>
      List.unmodifiable(_posts.where((p) => !blocked.contains(p.authorId)));
  List<ScoutPost> get followingPosts => posts
      .where(
        (p) =>
            followed.contains(p.authorId) ||
            p.hashtags.any(followedHashtags.contains),
      )
      .toList();
  int likeCount(ScoutPost p) => p.initialLikes + (liked.contains(p.id) ? 1 : 0);
  void enterDemo() {
    inDemo = true;
    notifyListeners();
  }

  void leaveDemo() {
    inDemo = false;
    notifyListeners();
  }

  void toggleLike(String id) {
    liked.contains(id) ? liked.remove(id) : liked.add(id);
    notifyListeners();
  }

  void toggleFollow(String id) {
    if (id == 'me') return;
    followed.contains(id) ? followed.remove(id) : followed.add(id);
    notifyListeners();
  }

  void toggleHashtag(String tag) {
    followedHashtags.contains(tag)
        ? followedHashtags.remove(tag)
        : followedHashtags.add(tag);
    notifyListeners();
  }

  void block(String id) {
    if (id == 'me') return;
    blocked.add(id);
    followed.remove(id);
    notifyListeners();
  }

  void unblock(String id) {
    blocked.remove(id);
    notifyListeners();
  }

  bool addComment(ScoutPost post, String text, {String? parentId}) {
    if (text.trim().isEmpty || blocked.contains(post.authorId)) return false;
    post.comments.add(
      ScoutComment(
        id: _id('comment'),
        authorId: 'me',
        text: text.trim(),
        parentId: parentId,
      ),
    );
    notifyListeners();
    return true;
  }

  void deleteComment(ScoutPost post, ScoutComment comment) {
    if (comment.authorId != 'me' && post.authorId != 'me') {
      throw StateError('Suppression non autorisée.');
    }
    post.comments.removeWhere(
      (c) => c.id == comment.id || c.parentId == comment.id,
    );
    notifyListeners();
  }

  void rate(ScoutPost post, String skill, int value) {
    if (!post.skills.contains(skill) || value < 1 || value > 5) {
      throw ArgumentError('Évaluation invalide.');
    }
    ratings['${post.id}/$skill'] = value;
    notifyListeners();
  }

  void report(String targetId, String reason) {
    if (reason.trim().isEmpty) return;
    reports.add(ContentReport(targetId, reason));
    notifyListeners();
  }

  ScoutPost publish({
    required String description,
    required Set<String> skills,
    required String hashtags,
    String? mediaPath,
  }) {
    if (description.trim().isEmpty || skills.isEmpty) {
      throw ArgumentError('Description et compétence requises.');
    }
    final tags = hashtags
        .split(RegExp(r'[\s,]+'))
        .map((t) => t.replaceFirst(RegExp(r'^#+'), ''))
        .where((t) => t.isNotEmpty)
        .toSet()
        .toList();
    final post = ScoutPost(
      id: _id('post'),
      authorId: 'me',
      description: description.trim(),
      skills: skills.toList(),
      hashtags: tags,
      createdAt: DateTime.now(),
      mediaPath: mediaPath,
      isDemo: mediaPath == null,
    );
    _posts.insert(0, post);
    notifyListeners();
    return post;
  }

  void deletePost(ScoutPost post) {
    if (post.authorId != 'me') {
      throw StateError('Seul l’auteur peut supprimer sa vidéo.');
    }
    _posts.remove(post);
    liked.remove(post.id);
    ratings.removeWhere((key, _) => key.startsWith('${post.id}/'));
    notifyListeners();
  }

  bool send(String recipient, String text) {
    if (blocked.contains(recipient) || text.trim().isEmpty) return false;
    conversations
        .putIfAbsent(recipient, () => [])
        .add(ChatMessage(text: text.trim(), fromMe: true, at: DateTime.now()));
    notifyListeners();
    return true;
  }

  void editProfile(String name, String bio) {
    if (name.trim().isEmpty) throw ArgumentError('Nom requis.');
    final old = me;
    talents[talents.indexOf(old)] = Talent(
      id: old.id,
      name: name.trim(),
      handle: old.handle,
      city: old.city,
      position: old.position,
      initials: name
          .trim()
          .split(RegExp(r'\s+'))
          .take(2)
          .map((s) => s[0].toUpperCase())
          .join(),
      skills: old.skills,
      bio: bio.trim(),
    );
    notifyListeners();
  }
}
