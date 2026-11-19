import 'dart:async';
import 'package:flutter/material.dart';
import '../backend/api.dart';
import '../backend/connected_session.dart';
import '../backend/private_video.dart';
import '../core/theme.dart';
import '../widgets/private_video_player.dart';
import '../widgets/terrain.dart';

class ConnectedFeedScreen extends StatefulWidget {
  const ConnectedFeedScreen({
    required this.session,
    required this.active,
    required this.onMessage,
    super.key,
  });
  final ConnectedSession session;
  final bool active;
  final ValueChanged<String> onMessage;
  @override
  State<ConnectedFeedScreen> createState() => _ConnectedFeedScreenState();
}

class _ConnectedFeedScreenState extends State<ConnectedFeedScreen> {
  List<FeedVideo> videos = [];
  String? selected;
  bool loading = false, failed = false, hasMore = false;
  int offset = 0, generation = 0;
  bool followingOnly = false;
  final Map<String, Map<String, dynamic>> likeStates = {};

  /// Noms publics lus auprès de User, une fois par compte, pour afficher un nom plutôt qu'un identifiant.
  final Map<String, String> names = {};

  @override
  void initState() {
    super.initState();
    if (widget.active) load();
  }

  @override
  void didUpdateWidget(covariant ConnectedFeedScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active && !oldWidget.active) {
      offset = 0;
      load();
    } else if (!widget.active && oldWidget.active) {
      generation++;
      selected = null;
      loading = false;
    }
  }

  Future<void> load({bool firstPage = false, int? pageOffset}) async {
    if (!widget.active) return;
    final requestedOffset = firstPage ? 0 : pageOffset ?? offset;
    final current = ++generation;
    setState(() {
      loading = true;
      failed = false;
    });
    try {
      final data = await widget.session.feedGet(
        offset: requestedOffset,
        followingOnly: followingOnly,
      );
      if (!mounted || generation != current) return;
      setState(() {
        offset = requestedOffset;
        videos = (data['items'] as List)
            .map((item) => FeedVideo.fromJson(item as Map<String, dynamic>))
            .toList();
        hasMore = data['hasMore'] as bool;
        if (!videos.any((video) => video.id == selected)) selected = null;
      });
      unawaited(_resolveNames(videos.map((video) => video.ownerSubject)));
    } catch (_) {
      if (mounted && generation == current) setState(() => failed = true);
    } finally {
      if (mounted && generation == current) setState(() => loading = false);
    }
  }

  String nameOf(String subject) {
    final me = widget.session.profile;
    if (me != null && me.subject == subject) return me.name;
    return names[subject] ?? 'Un joueur';
  }

  /// Un compte fermé, désactivé ou bloqué reste « Un joueur ».
  Future<void> _resolveNames(Iterable<String> subjects) async {
    final me = widget.session.profile?.subject;
    final missing = subjects
        .toSet()
        .where((s) => s != me && !names.containsKey(s))
        .toList();
    if (missing.isEmpty) return;
    final found = await Future.wait(
      missing.map((subject) async {
        try {
          final profile = await widget.session.publicProfile(subject);
          return (subject, profile['displayName'] as String);
        } catch (_) {
          return (subject, 'Un joueur');
        }
      }),
    );
    if (!mounted) return;
    setState(() {
      for (final (subject, name) in found) {
        names[subject] = name;
      }
    });
  }

  Future<void> _like(FeedVideo video) async {
    try {
      final current = likeStates[video.id];
      final next = current?['liked'] == true;
      final state = next
          ? await widget.session
                .socialDelete('videos/${video.id}/likes')
                .then(
                  (_) => widget.session.socialGet('videos/${video.id}/likes'),
                )
          : await widget.session.socialPut('videos/${video.id}/likes');
      if (mounted) setState(() => likeStates[video.id] = state);
    } catch (_) {
      if (mounted) _message('Impossible de mettre à jour le like.');
    }
  }

  void _message(String text) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> _comments(FeedVideo video) async {
    final input = TextEditingController();
    final me = widget.session.profile?.subject;
    List<Map<String, dynamic>> items = [];
    var hasOlder = false;
    Map<String, dynamic>? replyTo;
    try {
      items = await widget.session.socialList('videos/${video.id}/comments');
      hasOlder = items.length >= commentPageSize;
      await _resolveNames(items.map((c) => c['authorSubject'] as String));
    } catch (_) {
      _message('Impossible de charger les commentaires.');
      input.dispose();
      return;
    }
    if (!mounted) {
      input.dispose();
      return;
    }
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => StatefulBuilder(
        builder: (context, refresh) {
          final p = context.scout;
          Future<void> loadOlder() async {
            try {
              final older = await widget.session.socialList(
                'videos/${video.id}/comments',
                query: {'before': items.first['id'] as String},
              );
              await _resolveNames(
                older.map((c) => c['authorSubject'] as String),
              );
              refresh(() {
                items = [...older, ...items];
                hasOlder = older.length >= commentPageSize;
              });
            } catch (_) {
              _message('Impossible de charger les commentaires plus anciens.');
            }
          }

          Future<void> delete(Map<String, dynamic> comment) async {
            try {
              await widget.session.socialDelete('comments/${comment['id']}');
              refresh(
                () => items = items
                    .where((item) => item['id'] != comment['id'])
                    .toList(),
              );
            } catch (_) {
              _message('Le commentaire n’a pas pu être supprimé.');
            }
          }

          return Padding(
            padding: EdgeInsets.fromLTRB(
              20,
              0,
              20,
              MediaQuery.viewInsetsOf(context).bottom + 20,
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Commentaires',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                Text(
                  video.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 12),
                SizedBox(
                  height: 280,
                  child: items.isEmpty
                      ? Center(
                          child: Text(
                            'Aucun commentaire pour le moment.',
                            style: TextStyle(color: p.gradin),
                          ),
                        )
                      : ListView(
                          children: [
                            if (hasOlder)
                              Center(
                                child: TextButton(
                                  onPressed: loadOlder,
                                  child: const Text(
                                    'Commentaires plus anciens',
                                  ),
                                ),
                              ),
                            for (final entry in threadComments(items))
                              _CommentRow(
                                author: nameOf(
                                  entry.comment['authorSubject'] as String,
                                ),
                                body: entry.comment['body'] as String,
                                reply: entry.reply,
                                actions: [
                                  if (!entry.reply)
                                    IconButton(
                                      tooltip: 'Répondre',
                                      icon: const Icon(Icons.reply, size: 20),
                                      onPressed: () => refresh(
                                        () => replyTo = entry.comment,
                                      ),
                                    ),
                                  if (me != null &&
                                      entry.comment['authorSubject'] != me)
                                    IconButton(
                                      tooltip: 'Signaler',
                                      icon: const Icon(
                                        Icons.flag_outlined,
                                        size: 20,
                                      ),
                                      onPressed: () => _report(
                                        'comments/${entry.comment['id']}/reports',
                                        'Signaler ce commentaire',
                                      ),
                                    ),
                                  // Auteur ou propriétaire de la vidéo (U13).
                                  if (me != null &&
                                      (entry.comment['authorSubject'] == me ||
                                          video.ownerSubject == me))
                                    IconButton(
                                      tooltip: 'Supprimer',
                                      icon: const Icon(
                                        Icons.delete_outline,
                                        size: 20,
                                      ),
                                      onPressed: () => delete(entry.comment),
                                    ),
                                ],
                              ),
                          ],
                        ),
                ),
                if (replyTo != null)
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          'Réponse à ${nameOf(replyTo!['authorSubject'] as String)}',
                          style: TextStyle(color: p.gradin),
                        ),
                      ),
                      IconButton(
                        tooltip: 'Annuler la réponse',
                        icon: const Icon(Icons.close),
                        onPressed: () => refresh(() => replyTo = null),
                      ),
                    ],
                  ),
                const SizedBox(height: 8),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: TextField(
                        controller: input,
                        maxLength: 1000,
                        decoration: const InputDecoration(
                          hintText: 'Écrire un commentaire',
                          counterText: '',
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    IconButton.filled(
                      tooltip: 'Envoyer le commentaire',
                      onPressed: () async {
                        if (input.text.trim().isEmpty) return;
                        try {
                          final parent = replyTo;
                          final item = await widget.session
                              .socialPost('videos/${video.id}/comments', {
                                'body': input.text.trim(),
                                if (parent != null)
                                  'parentId': parent['id'] as String,
                              });
                          refresh(() {
                            items = [...items, item];
                            input.clear();
                            replyTo = null;
                          });
                        } on ApiFailure catch (failure) {
                          _message(
                            failure.status == 403
                                ? 'Un blocage empêche ce commentaire.'
                                : 'Le commentaire n’a pas pu être envoyé.',
                          );
                        } catch (_) {
                          _message('Le commentaire n’a pas pu être envoyé.');
                        }
                      },
                      icon: const Icon(Icons.send),
                    ),
                  ],
                ),
              ],
            ),
          );
        },
      ),
    );
    input.dispose();
  }

  /// U15 : la fiche du clip. Moyenne par compétence ; les autres joueurs notent de 1 à 5, modifient ou retirent leur note.
  Future<void> _ratings(FeedVideo video) async {
    final mine = video.ownerSubject == widget.session.profile?.subject;
    Map<String, SkillRating> ratings;
    try {
      ratings = SkillRating.bySkill(
        await widget.session.videoRatings(video.id),
      );
    } catch (_) {
      _message('Impossible de charger les notes.');
      return;
    }
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => StatefulBuilder(
        builder: (context, refresh) {
          Future<void> rate(String skillId, int? score) async {
            try {
              final updated = SkillRating.bySkill(
                await widget.session.rateSkill(video.id, skillId, score),
              );
              refresh(() => ratings = updated);
            } on ApiFailure catch (failure) {
              _message(
                failure.status == 403
                    ? 'Un blocage empêche cette note.'
                    : 'La note n’a pas pu être enregistrée.',
              );
            } catch (_) {
              _message('La note n’a pas pu être enregistrée.');
            }
          }

          return ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.sizeOf(context).height * 0.8,
            ),
            child: ListView(
              shrinkWrap: true,
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
              children: [
                Text(
                  'Fiche du clip',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 4),
                Text(video.title),
                Text(
                  'Clip de ${nameOf(video.ownerSubject)}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                if (mine)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'Tu ne peux pas noter ta propre vidéo.',
                      style: TextStyle(color: context.scout.gradin),
                    ),
                  ),
                const SizedBox(height: 8),
                for (var i = 0; i < video.skillIds.length; i++) ...[
                  if (i > 0) const Divider(height: 1),
                  Builder(
                    builder: (context) {
                      final id = video.skillIds[i];
                      final rating = ratings[id] ?? const SkillRating();
                      return FicheSkill(
                        skill: video.skills[i],
                        average: rating.average,
                        count: rating.count,
                        myScore: rating.myScore,
                        onRate: mine ? null : (score) => rate(id, score),
                        onClear: mine ? null : () => rate(id, null),
                      );
                    },
                  ),
                ],
              ],
            ),
          );
        },
      ),
    );
  }

  Future<void> _profile(FeedVideo video) async {
    try {
      final profile = await widget.session.publicProfile(video.ownerSubject);
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (dialogContext) {
          final p = dialogContext.scout;
          final biography = profile['biography'] as String? ?? '';
          final following = profile['following'] == true;
          return Dialog(
            clipBehavior: Clip.antiAlias,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SizedBox(
                  height: 150,
                  child: PitchSurface(
                    child: Stack(
                      children: [
                        Positioned(
                          right: 4,
                          top: 4,
                          child: IconButton(
                            tooltip: 'Fermer',
                            color: p.surPelouse,
                            onPressed: () => Navigator.pop(dialogContext),
                            icon: const Icon(Icons.close),
                          ),
                        ),
                        Positioned(
                          left: 20,
                          right: 20,
                          bottom: 16,
                          child: Text(
                            profile['displayName'] as String,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: scoutDisplay(40, p.surPelouse),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        biography.isNotEmpty ? biography : 'Aucune biographie.',
                        style: TextStyle(
                          color: biography.isNotEmpty ? p.encre : p.gradin,
                        ),
                      ),
                      const SizedBox(height: 12),
                      Text(
                        '${profile['followerCount']} abonnés, ${profile['followingCount']} abonnements',
                        style: Theme.of(dialogContext).textTheme.bodySmall,
                      ),
                      const SizedBox(height: 18),
                      Row(
                        children: [
                          Expanded(
                            child: OutlinedButton.icon(
                              onPressed: () {
                                Navigator.pop(dialogContext);
                                widget.onMessage(video.ownerSubject);
                              },
                              icon: const Icon(Icons.chat_outlined, size: 18),
                              label: const Text('Message'),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: FilledButton(
                              style: following
                                  ? FilledButton.styleFrom(
                                      backgroundColor: p.pelouse,
                                      foregroundColor: p.surPelouse,
                                    )
                                  : null,
                              onPressed: () async {
                                try {
                                  await widget.session.followUser(
                                    video.ownerSubject,
                                    follow: !following,
                                  );
                                  if (!dialogContext.mounted) return;
                                  Navigator.pop(dialogContext);
                                } catch (_) {
                                  _message(
                                    'Impossible de modifier cet abonnement.',
                                  );
                                }
                              },
                              child: Text(
                                following ? 'Ne plus suivre' : 'Suivre',
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          );
        },
      );
    } catch (_) {
      _message('Profil public indisponible.');
    }
  }

  /// Signale une vidéo (U16) ou un commentaire (U17) ; Social ignore un second signalement du même compte.
  Future<void> _report(String path, String title) async {
    final reason = TextEditingController();
    final submitted = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: reason,
          maxLength: 500,
          maxLines: 3,
          decoration: const InputDecoration(
            hintText: 'Explique le motif du signalement',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('Envoyer'),
          ),
        ],
      ),
    );
    if (submitted == true && reason.text.trim().isNotEmpty) {
      try {
        await widget.session.socialPost(path, {'reason': reason.text.trim()});
        _message('Signalement transmis.');
      } catch (_) {
        _message('Impossible de transmettre le signalement.');
      }
    }
    reason.dispose();
  }

  void _filter(bool following) {
    setState(() {
      followingOnly = following;
      offset = 0;
      hasMore = false;
      videos = [];
      selected = null;
    });
    load(firstPage: true);
  }

  @override
  void dispose() {
    generation++;
    super.dispose();
  }

  Widget _clip(BuildContext context, FeedVideo video) {
    final p = context.scout;
    final mine = video.ownerSubject == widget.session.profile?.subject;
    final liked = likeStates[video.id]?['liked'] == true;
    final likes = likeStates[video.id]?['count'];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (selected == video.id) ...[
          PrivateVideoPlayer(
            key: ValueKey('public-${video.id}'),
            id: video.id,
            publicPlayback: true,
            request: (path, {query}) =>
                widget.session.videoGet(path, query: query),
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: () => setState(() => selected = null),
              child: const Text('Fermer le lecteur'),
            ),
          ),
        ] else
          Semantics(
            button: true,
            label: 'Lire le clip ${video.title}',
            excludeSemantics: true,
            child: InkWell(
              onTap: () => setState(() => selected = video.id),
              borderRadius: BorderRadius.circular(12),
              child: SizedBox(
                height: 320,
                child: PitchSurface(
                  radius: 12,
                  child: Stack(
                    children: [
                      Center(
                        child: Container(
                          width: 64,
                          height: 64,
                          decoration: BoxDecoration(
                            color: p.surPelouse,
                            shape: BoxShape.circle,
                          ),
                          child: Icon(
                            Icons.play_arrow_rounded,
                            size: 40,
                            color: p.pelouse,
                          ),
                        ),
                      ),
                      Positioned(
                        left: 26,
                        right: 26,
                        bottom: 24,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              nameOf(video.ownerSubject),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: scoutDisplay(32, p.surPelouse),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              frWhen(video.publishedAt),
                              style: TextStyle(
                                color: p.surPelouse,
                                fontSize: 13,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        const SizedBox(height: 12),
        Text(video.title, style: Theme.of(context).textTheme.titleMedium),
        if (video.skills.isNotEmpty || video.hashtags.isNotEmpty) ...[
          const SizedBox(height: 8),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              for (final skill in video.skills) ScoutTag(skill),
              for (final tag in video.hashtags)
                Text('#$tag', style: TextStyle(color: p.gradin, fontSize: 13)),
            ],
          ),
        ],
        const SizedBox(height: 6),
        Row(
          children: [
            TextButton.icon(
              onPressed: () => _like(video),
              style: TextButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 8),
              ),
              icon: Icon(liked ? Icons.favorite : Icons.favorite_border),
              label: Text('${likes ?? 'J’aime'}'),
            ),
            IconButton(
              tooltip: 'Commenter',
              onPressed: () => _comments(video),
              icon: const Icon(Icons.mode_comment_outlined),
            ),
            const Spacer(),
            if (video.skillIds.isNotEmpty)
              mine
                  ? OutlinedButton(
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size(48, 44),
                      ),
                      onPressed: () => _ratings(video),
                      child: const Text('Voir les notes'),
                    )
                  : Tooltip(
                      message: 'Noter les compétences',
                      child: RateButton(onPressed: () => _ratings(video)),
                    ),
            PopupMenuButton<String>(
              tooltip: 'Options du clip',
              onSelected: (value) => value == 'profile'
                  ? _profile(video)
                  : _report(
                      'videos/${video.id}/reports',
                      'Signaler cette vidéo',
                    ),
              itemBuilder: (_) => [
                const PopupMenuItem(
                  value: 'profile',
                  child: Text('Voir le profil'),
                ),
                if (!mine)
                  const PopupMenuItem(value: 'report', child: Text('Signaler')),
              ],
              icon: Icon(Icons.more_vert, color: p.encre),
            ),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    return RefreshIndicator(
      onRefresh: () => load(firstPage: true),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
        physics: const AlwaysScrollableScrollPhysics(),
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'Le fil',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
              ),
              IconButton(
                tooltip: 'Actualiser le fil',
                onPressed: loading ? null : () => load(firstPage: true),
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
          Text(
            'Les vidéos publiées par la communauté YouScout.',
            style: TextStyle(color: p.gradin),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            children: [
              ChoiceChip(
                label: const Text('Tout'),
                selected: !followingOnly,
                onSelected: (_) => _filter(false),
              ),
              ChoiceChip(
                label: const Text('Abonnements'),
                selected: followingOnly,
                onSelected: (_) => _filter(true),
              ),
            ],
          ),
          const SizedBox(height: 16),
          if (loading) const LinearProgressIndicator(),
          if (failed) ...[
            const ScoutNotice(
              'Impossible de charger le fil. Vérifie ta connexion puis réessaie.',
              error: true,
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                onPressed: loading ? null : () => load(firstPage: true),
                child: const Text('Réessayer'),
              ),
            ),
          ],
          if (!loading && !failed && videos.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 32),
              child: Text(
                followingOnly
                    ? 'Suis des joueurs pour voir leurs vidéos ici.'
                    : 'Aucune vidéo publiée pour le moment.',
                style: TextStyle(color: p.gradin),
              ),
            ),
          for (var i = 0; i < videos.length; i++) ...[
            if (i > 0) const Divider(height: 36),
            _clip(context, videos[i]),
          ],
          if (offset > 0 || hasMore)
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  TextButton(
                    onPressed: loading || offset == 0
                        ? null
                        : () => load(pageOffset: offset - 20),
                    child: const Text('Précédentes'),
                  ),
                  TextButton(
                    onPressed: loading || !hasMore
                        ? null
                        : () => load(pageOffset: offset + 20),
                    child: const Text('Suivantes'),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _CommentRow extends StatelessWidget {
  const _CommentRow({
    required this.author,
    required this.body,
    required this.reply,
    required this.actions,
  });
  final String author, body;
  final bool reply;
  final List<Widget> actions;
  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.only(left: reply ? 40 : 0, top: 8, bottom: 8),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        PlayerAvatar(author, size: reply ? 28 : 36),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                author,
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: 14,
                ),
              ),
              Text(body),
            ],
          ),
        ),
        ...actions,
      ],
    ),
  );
}

/// Taille de page par défaut de Social ; une page pleine signale un historique plus ancien.
const commentPageSize = 50;

/// Place chaque réponse sous son commentaire principal ; une réponse dont le parent
/// n'est pas chargé (supprimé, bloqué ou plus ancien) reste visible en fin de liste.
List<({Map<String, dynamic> comment, bool reply})> threadComments(
  List<Map<String, dynamic>> items,
) {
  final roots = items.where((item) => item['parentId'] == null).toList();
  final rootIds = {for (final root in roots) root['id']};
  return [
    for (final root in roots) ...[
      (comment: root, reply: false),
      for (final item in items)
        if (item['parentId'] == root['id']) (comment: item, reply: true),
    ],
    for (final item in items)
      if (item['parentId'] != null && !rootIds.contains(item['parentId']))
        (comment: item, reply: true),
  ];
}

String shortSubject(String subject) =>
    subject.length <= 8 ? subject : subject.substring(0, 8);
