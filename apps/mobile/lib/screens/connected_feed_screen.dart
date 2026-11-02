import 'package:flutter/material.dart';
import '../backend/connected_session.dart';
import '../backend/private_video.dart';
import '../core/theme.dart';
import '../widgets/private_video_player.dart';

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
    } catch (_) {
      if (mounted && generation == current) setState(() => failed = true);
    } finally {
      if (mounted && generation == current) setState(() => loading = false);
    }
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
    List<Map<String, dynamic>> items = [];
    try {
      items = await widget.session.socialList('videos/${video.id}/comments');
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
        builder: (context, refresh) => Padding(
          padding: EdgeInsets.fromLTRB(
            20,
            20,
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
              const SizedBox(height: 12),
              SizedBox(
                height: 260,
                child: items.isEmpty
                    ? const Center(
                        child: Text('Aucun commentaire pour le moment.'),
                      )
                    : ListView(
                        children: [
                          for (final item in items)
                            ListTile(
                              leading: const CircleAvatar(
                                child: Icon(Icons.person),
                              ),
                              title: Text(item['body'] as String),
                              subtitle: Text(
                                (item['authorSubject'] as String).substring(
                                  0,
                                  8,
                                ),
                              ),
                            ),
                        ],
                      ),
              ),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: input,
                      maxLength: 1000,
                      decoration: const InputDecoration(
                        hintText: 'Écrire un commentaire',
                      ),
                    ),
                  ),
                  IconButton.filled(
                    onPressed: () async {
                      if (input.text.trim().isEmpty) return;
                      try {
                        final item = await widget.session.socialPost(
                          'videos/${video.id}/comments',
                          {'body': input.text.trim()},
                        );
                        refresh(() {
                          items = [...items, item];
                          input.clear();
                        });
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
        ),
      ),
    );
    input.dispose();
  }

  Future<void> _profile(FeedVideo video) async {
    try {
      final profile = await widget.session.publicProfile(video.ownerSubject);
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(profile['displayName'] as String),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                (profile['biography'] as String?)?.isNotEmpty == true
                    ? profile['biography'] as String
                    : 'Aucune biographie.',
              ),
              const SizedBox(height: 14),
              Text(
                '${profile['followerCount']} abonnés · ${profile['followingCount']} abonnements',
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: const Text('Fermer'),
            ),
            TextButton.icon(
              onPressed: () {
                Navigator.pop(dialogContext);
                widget.onMessage(video.ownerSubject);
              },
              icon: const Icon(Icons.chat_outlined),
              label: const Text('Message'),
            ),
            FilledButton(
              onPressed: () async {
                try {
                  await widget.session.followUser(
                    video.ownerSubject,
                    follow: profile['following'] != true,
                  );
                  if (!dialogContext.mounted) return;
                  Navigator.pop(dialogContext);
                } catch (_) {
                  _message('Impossible de modifier cet abonnement.');
                }
              },
              child: Text(
                profile['following'] == true ? 'Ne plus suivre' : 'Suivre',
              ),
            ),
          ],
        ),
      );
    } catch (_) {
      _message('Profil public indisponible.');
    }
  }

  Future<void> _report(FeedVideo video) async {
    final reason = TextEditingController();
    final submitted = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Signaler cette vidéo'),
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
        await widget.session.socialPost('videos/${video.id}/reports', {
          'reason': reason.text.trim(),
        });
        _message('Signalement transmis.');
      } catch (_) {
        _message('Impossible de transmettre le signalement.');
      }
    }
    reason.dispose();
  }

  @override
  void dispose() {
    generation++;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => RefreshIndicator(
    onRefresh: () => load(firstPage: true),
    child: ListView(
      padding: const EdgeInsets.all(20),
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                'Le fil',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
            ),
            IconButton(
              tooltip: 'Actualiser le fil',
              onPressed: loading ? null : () => load(firstPage: true),
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        const Text(
          'Les vidéos publiées par la communauté YouScout.',
          style: TextStyle(color: ScoutColors.muted),
        ),
        Wrap(
          spacing: 8,
          children: [
            ChoiceChip(
              label: const Text('Tout'),
              selected: !followingOnly,
              onSelected: (_) {
                setState(() {
                  followingOnly = false;
                  offset = 0;
                  hasMore = false;
                  videos = [];
                  selected = null;
                });
                load(firstPage: true);
              },
            ),
            ChoiceChip(
              label: const Text('Abonnements'),
              selected: followingOnly,
              onSelected: (_) {
                setState(() {
                  followingOnly = true;
                  offset = 0;
                  hasMore = false;
                  videos = [];
                  selected = null;
                });
                load(firstPage: true);
              },
            ),
          ],
        ),
        const SizedBox(height: 16),
        if (loading) const LinearProgressIndicator(),
        if (failed) ...[
          const Text(
            'Impossible de charger le fil. Vérifie ta connexion puis réessaie.',
          ),
          TextButton(
            onPressed: loading ? null : () => load(firstPage: true),
            child: const Text('Réessayer'),
          ),
        ],
        if (!loading && !failed && videos.isEmpty && !followingOnly)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 32),
            child: Text('Aucune vidéo publiée pour le moment.'),
          ),
        if (!loading && !failed && videos.isEmpty && followingOnly)
          const Padding(
            padding: EdgeInsets.only(bottom: 24),
            child: Text('Suis des joueurs pour voir leurs videos ici.'),
          ),
        if (selected != null) ...[
          PrivateVideoPlayer(
            key: ValueKey('public-$selected'),
            id: selected!,
            publicPlayback: true,
            request: (path, {query}) =>
                widget.session.videoGet(path, query: query),
          ),
          TextButton(
            onPressed: () => setState(() => selected = null),
            child: const Text('Fermer le lecteur'),
          ),
        ],
        for (final video in videos)
          Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ListTile(
                  leading: const CircleAvatar(child: Icon(Icons.sports_soccer)),
                  title: Text(
                    video.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                  subtitle: Text(
                    video.publishedAt.toLocal().toString().substring(0, 16),
                  ),
                  trailing: const Icon(
                    Icons.play_circle_outline,
                    color: ScoutColors.brand,
                  ),
                  onTap: () => setState(() => selected = video.id),
                ),
                if (video.skills.isNotEmpty || video.hashtags.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                    child: Wrap(
                      spacing: 6,
                      runSpacing: 4,
                      children: [
                        for (final skill in video.skills)
                          Chip(
                            avatar: const Icon(Icons.sports, size: 15),
                            label: Text(skill),
                          ),
                        for (final tag in video.hashtags)
                          Chip(avatar: const Text('#'), label: Text(tag)),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        for (final video in videos)
          Padding(
            padding: const EdgeInsets.only(left: 12, right: 12, bottom: 12),
            child: Row(
              children: [
                TextButton.icon(
                  onPressed: () => _profile(video),
                  icon: const Icon(Icons.person_outline),
                  label: const Text('Profil'),
                ),
                TextButton.icon(
                  onPressed: () => _like(video),
                  icon: Icon(
                    likeStates[video.id]?['liked'] == true
                        ? Icons.favorite
                        : Icons.favorite_border,
                  ),
                  label: Text('${likeStates[video.id]?['count'] ?? 'J’aime'}'),
                ),
                TextButton.icon(
                  onPressed: () => _comments(video),
                  icon: const Icon(Icons.mode_comment_outlined),
                  label: const Text('Commenter'),
                ),
                PopupMenuButton<String>(
                  onSelected: (_) => _report(video),
                  itemBuilder: (_) => const [
                    PopupMenuItem(value: 'report', child: Text('Signaler')),
                  ],
                  icon: const Icon(Icons.more_horiz),
                ),
              ],
            ),
          ),
        if (offset > 0 || hasMore)
          Row(
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
      ],
    ),
  );
}
