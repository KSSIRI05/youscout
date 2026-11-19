import 'package:flutter/material.dart';
import '../core/theme.dart';
import '../data/models.dart';
import '../state/scout_store.dart';
import '../widgets/local_video.dart';
import '../widgets/scout_widgets.dart';
import '../widgets/terrain.dart';
import 'messages_screen.dart';

class FeedScreen extends StatefulWidget {
  const FeedScreen({required this.store, required this.onExplore, super.key});
  final ScoutStore store;
  final VoidCallback onExplore;
  @override
  State<FeedScreen> createState() => _FeedScreenState();
}

class _FeedScreenState extends State<FeedScreen> {
  bool following = false;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.store,
    builder: (context, _) {
      final posts = following
          ? widget.store.followingPosts
          : widget.store.posts;
      return ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'YouScout',
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
              ),
              const ScoutTag('Démo'),
            ],
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            children: [
              ChoiceChip(
                label: const Text('Pour toi'),
                selected: !following,
                onSelected: (_) => setState(() => following = false),
              ),
              ChoiceChip(
                label: const Text('Abonnements'),
                selected: following,
                onSelected: (_) => setState(() => following = true),
              ),
            ],
          ),
          const SizedBox(height: 18),
          if (posts.isEmpty)
            EmptyState(
              'Ton prochain coup de cœur t’attend.',
              'Suis un talent ou un hashtag pour retrouver ses publications ici.',
              action: ScoutButton(
                'Découvrir les talents',
                onPressed: widget.onExplore,
              ),
            ),
          for (var i = 0; i < posts.length; i++) ...[
            if (i > 0) const Divider(height: 36),
            PostCard(post: posts[i], store: widget.store),
          ],
        ],
      );
    },
  );
}

/// Un clip de la démo : la pelouse et le nom du joueur, puis la description, les compétences et les actions.
class PostCard extends StatelessWidget {
  const PostCard({required this.post, required this.store, super.key});
  final ScoutPost post;
  final ScoutStore store;
  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    final author = store.talent(post.authorId);
    final liked = store.liked.contains(post.id);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (post.mediaPath != null) ...[
          TalentTile(
            author,
            onTap: () => openTalent(context, store, post.authorId),
          ),
          const SizedBox(height: 8),
          LocalVideo(post.mediaPath!),
        ] else
          Semantics(
            button: true,
            label: 'Voir le profil de ${author.name}',
            excludeSemantics: true,
            child: InkWell(
              onTap: () => openTalent(context, store, post.authorId),
              borderRadius: BorderRadius.circular(12),
              child: SizedBox(
                height: 320,
                child: PitchSurface(
                  radius: 12,
                  child: Stack(
                    children: [
                      if (post.isDemo)
                        Positioned(
                          left: 26,
                          top: 24,
                          child: DecoratedBox(
                            decoration: ShapeDecoration(
                              color: p.pelouse,
                              shape: const StadiumBorder(),
                            ),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 10,
                                vertical: 3,
                              ),
                              child: Text(
                                'Illustration de démonstration',
                                style: TextStyle(
                                  color: p.surPelouse,
                                  fontSize: 12,
                                ),
                              ),
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
                              author.name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: scoutDisplay(34, p.surPelouse),
                            ),
                            const SizedBox(height: 2),
                            Text(
                              '${author.position}, ${author.city}',
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
        Text(post.description, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        Wrap(
          spacing: 6,
          runSpacing: 2,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            for (final s in post.skills) ScoutTag(s),
            for (final tag in post.hashtags)
              TextButton(
                style: TextButton.styleFrom(
                  foregroundColor: p.gradin,
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  minimumSize: const Size(0, 36),
                  textStyle: const TextStyle(
                    fontFamily: ScoutFonts.text,
                    fontSize: 13,
                  ),
                ),
                onPressed: () => showHashtag(context, store, tag),
                child: Text('#$tag'),
              ),
          ],
        ),
        Row(
          children: [
            Expanded(
              child: Wrap(
                children: [
                  TextButton.icon(
                    key: Key('like-${post.id}'),
                    style: TextButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                    ),
                    onPressed: () => store.toggleLike(post.id),
                    icon: Icon(
                      liked ? Icons.favorite : Icons.favorite_border,
                      size: 20,
                    ),
                    label: Text('${store.likeCount(post)}'),
                  ),
                  Tooltip(
                    message: 'Commentaires',
                    child: TextButton.icon(
                      style: TextButton.styleFrom(
                        padding: const EdgeInsets.symmetric(horizontal: 8),
                      ),
                      onPressed: () => showComments(context, store, post),
                      icon: const Icon(Icons.mode_comment_outlined, size: 20),
                      label: Text('${post.comments.length}'),
                    ),
                  ),
                ],
              ),
            ),
            if (post.skills.isNotEmpty)
              RateButton(onPressed: () => showRating(context, store, post)),
            PopupMenuButton<String>(
              tooltip: 'Options de la publication',
              icon: Icon(Icons.more_vert, color: p.encre),
              onSelected: (value) async {
                if (value == 'report') {
                  await showReport(context, store, post.id);
                  return;
                }
                if (value == 'block') {
                  await confirmBlock(context, store, post.authorId);
                  return;
                }
                if (value == 'delete') {
                  final yes = await confirmAction(
                    context,
                    'Supprimer cette publication ?',
                    'Elle sera retirée de cette session de démonstration.',
                  );
                  if (yes) store.deletePost(post);
                }
              },
              itemBuilder: (_) => [
                const PopupMenuItem(value: 'report', child: Text('Signaler')),
                if (post.authorId != 'me')
                  const PopupMenuItem(
                    value: 'block',
                    child: Text('Bloquer cet utilisateur'),
                  ),
                if (post.authorId == 'me')
                  const PopupMenuItem(
                    value: 'delete',
                    child: Text('Supprimer ma publication'),
                  ),
              ],
            ),
          ],
        ),
      ],
    );
  }
}

class DiscoverScreen extends StatefulWidget {
  const DiscoverScreen({required this.store, super.key});
  final ScoutStore store;
  @override
  State<DiscoverScreen> createState() => _DiscoverScreenState();
}

class _DiscoverScreenState extends State<DiscoverScreen> {
  String query = '', skill = 'Tous';
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.store,
    builder: (context, _) {
      final matches = widget.store.talents
          .where(
            (t) =>
                !widget.store.blocked.contains(t.id) &&
                '${t.name} ${t.handle} ${t.city} ${t.skills.join(' ')}'
                    .toLowerCase()
                    .contains(query.toLowerCase().replaceFirst('#', '')) &&
                (skill == 'Tous' || t.skills.contains(skill)),
          )
          .toList();
      return ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        children: [
          const SectionHeading(
            'Le talent est partout.',
            subtitle:
                'Découvre les joueurs et les compétences qui t’inspirent.',
          ),
          const SizedBox(height: 18),
          TextField(
            key: const Key('talent-search'),
            onChanged: (v) => setState(() => query = v),
            decoration: const InputDecoration(
              hintText: 'Rechercher un talent, un hashtag…',
              prefixIcon: Icon(Icons.search),
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              for (final s in ['Tous', 'Dribble', 'Frappe', 'Technique'])
                ChoiceChip(
                  label: Text(s),
                  selected: skill == s,
                  onSelected: (_) => setState(() => skill = s),
                ),
            ],
          ),
          const SizedBox(height: 12),
          if (matches.isEmpty)
            const EmptyState(
              'Aucun talent trouvé.',
              'Essaie un autre nom ou une autre compétence.',
            ),
          for (final t in matches) ...[
            const Divider(height: 1),
            _PlayerRow(
              talent: t,
              onTap: () => openTalent(context, widget.store, t.id),
            ),
          ],
          const SizedBox(height: 20),
          ScoutButton(
            'Explorer #Technique',
            secondary: true,
            onPressed: () => showHashtag(context, widget.store, 'Technique'),
          ),
        ],
      );
    },
  );
}

/// Joueur dans Explorer : son poste sur le terrain, son nom et ses compétences.
class _PlayerRow extends StatelessWidget {
  const _PlayerRow({required this.talent, required this.onTap});
  final Talent talent;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          PitchPosition(talent.position, width: 46),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(talent.name, style: scoutDisplay(26, context.scout.encre)),
                const SizedBox(height: 2),
                Text(
                  '${talent.position}, ${talent.city}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 8),
                SkillChips(talent.skills),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

void openTalent(BuildContext context, ScoutStore store, String id) =>
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => TalentScreen(store: store, talentId: id),
      ),
    );

/// Carte du joueur : nom en lettrage de maillot sur la pelouse, poste dessiné sur le terrain.
class PlayerHeader extends StatelessWidget {
  const PlayerHeader({required this.talent, super.key});
  final Talent talent;
  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 190),
      child: PitchSurface(
        marks: PitchMarks.none,
        radius: 14,
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Text(
                      talent.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: scoutDisplay(44, p.surPelouse),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${talent.position}, ${talent.city}',
                      style: TextStyle(color: p.surPelouse, fontSize: 14),
                    ),
                    Text(
                      talent.handle,
                      style: TextStyle(
                        color: p.surPelouse.withValues(alpha: 0.8),
                        fontSize: 13,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              DecoratedBox(
                decoration: BoxDecoration(
                  border: Border.all(color: p.surPelouse, width: 1.5),
                  borderRadius: BorderRadius.circular(5),
                ),
                child: PitchPosition(talent.position, width: 58),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class TalentScreen extends StatelessWidget {
  const TalentScreen({required this.store, required this.talentId, super.key});
  final ScoutStore store;
  final String talentId;
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Profil du talent')),
    body: ListenableBuilder(
      listenable: store,
      builder: (context, _) {
        final t = store.talent(talentId);
        final blocked = store.blocked.contains(talentId);
        final posts = store.posts.where((p) => p.authorId == talentId).toList();
        return ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
          children: [
            PlayerHeader(talent: t),
            const SizedBox(height: 14),
            Text(t.bio),
            const SizedBox(height: 12),
            SkillChips(t.skills),
            const SizedBox(height: 20),
            if (talentId != 'me') ...[
              Row(
                children: [
                  Expanded(
                    child: FilledButton(
                      style: store.followed.contains(talentId)
                          ? FilledButton.styleFrom(
                              backgroundColor: context.scout.pelouse,
                              foregroundColor: context.scout.surPelouse,
                            )
                          : null,
                      onPressed: blocked
                          ? null
                          : () => store.toggleFollow(talentId),
                      child: Text(
                        store.followed.contains(talentId)
                            ? 'Ne plus suivre'
                            : 'Suivre',
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: OutlinedButton(
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size(48, 52),
                      ),
                      onPressed: blocked
                          ? null
                          : () => Navigator.of(context).push(
                              MaterialPageRoute<void>(
                                builder: (_) => ChatScreen(
                                  store: store,
                                  recipient: talentId,
                                ),
                              ),
                            ),
                      child: const Text('Message'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
            ],
            if (blocked)
              const EmptyState(
                'Utilisateur bloqué',
                'Ses publications sont masquées pour cette session.',
              ),
            for (final p in posts) ...[
              const Divider(height: 36),
              PostCard(post: p, store: store),
            ],
            if (talentId != 'me') ...[
              const SizedBox(height: 12),
              TextButton(
                onPressed: () async {
                  if (blocked) {
                    store.unblock(talentId);
                  } else {
                    await confirmBlock(context, store, talentId);
                  }
                },
                child: Text(
                  blocked
                      ? 'Débloquer cet utilisateur'
                      : 'Bloquer cet utilisateur',
                ),
              ),
            ],
          ],
        );
      },
    ),
  );
}

class ProfileScreen extends StatelessWidget {
  const ProfileScreen({required this.store, required this.onUpload, super.key});
  final ScoutStore store;
  final VoidCallback onUpload;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: store,
    builder: (context, _) {
      final mine = store.posts.where((p) => p.authorId == 'me').toList();
      final p = context.scout;
      return ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
        children: [
          const SectionHeading(
            'Mon terrain.',
            subtitle: 'Ton profil, tes vidéos et tes progrès.',
          ),
          const SizedBox(height: 18),
          PlayerHeader(talent: store.me),
          const SizedBox(height: 14),
          Text(store.me.bio),
          const SizedBox(height: 8),
          Text(
            '${mine.length} publication${mine.length > 1 ? 's' : ''}, ${store.followed.length} talent${store.followed.length > 1 ? 's' : ''} suivi${store.followed.length > 1 ? 's' : ''}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 12),
          SkillChips(store.me.skills),
          const SizedBox(height: 18),
          ScoutButton(
            'Modifier mon profil',
            secondary: true,
            onPressed: () => editProfile(context, store),
          ),
          for (final post in mine) ...[
            const Divider(height: 36),
            PostCard(post: post, store: store),
          ],
          if (mine.isEmpty)
            const EmptyState(
              'Ton histoire commence ici.',
              'Ajoute ton premier clip pour montrer ton jeu.',
            ),
          const SizedBox(height: 16),
          ScoutButton('Publier une nouvelle vidéo', onPressed: onUpload),
          const SizedBox(height: 10),
          ScoutButton(
            'Utilisateurs bloqués',
            secondary: true,
            onPressed: () => showBlocked(context, store),
          ),
          const SizedBox(height: 10),
          TextButton(
            onPressed: store.leaveDemo,
            child: const Text('Quitter la démo'),
          ),
          Text(
            'Données en mémoire : recharger l’application réinitialise la démonstration.',
            textAlign: TextAlign.center,
            style: TextStyle(color: p.gradin, fontSize: 12),
          ),
        ],
      );
    },
  );
}

Future<bool> confirmAction(
  BuildContext context,
  String title,
  String body,
) async =>
    await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Confirmer'),
          ),
        ],
      ),
    ) ??
    false;

Future<void> confirmBlock(
  BuildContext context,
  ScoutStore store,
  String id,
) async {
  if (await confirmAction(
    context,
    'Bloquer cet utilisateur ?',
    'Ses contenus seront masqués et tu ne pourras plus lui écrire dans cette démo.',
  )) {
    store.block(id);
  }
}

Future<void> showReport(
  BuildContext context,
  ScoutStore store,
  String target,
) async {
  String reason = 'Contenu inapproprié';
  final sent = await showDialog<bool>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, setState) => AlertDialog(
        title: const Text('Une communauté respectée.'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Quel est le problème ?'),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              initialValue: reason,
              isExpanded: true,
              items:
                  [
                        'Contenu inapproprié',
                        'Harcèlement ou insultes',
                        'Spam ou fausse identité',
                        'Autre motif',
                      ]
                      .map(
                        (r) => DropdownMenuItem(
                          value: r,
                          child: Text(r, overflow: TextOverflow.ellipsis),
                        ),
                      )
                      .toList(),
              onChanged: (v) => setState(() => reason = v!),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Enregistrer'),
          ),
        ],
      ),
    ),
  );
  if (sent == true) {
    store.report(target, reason);
    if (context.mounted) {
      scoutMessage(
        context,
        'Signalement enregistré localement. Aucun modérateur n’a été contacté.',
      );
    }
  }
}

/// Fiche du clip de démonstration : une note de 1 à 5 par compétence, gardée sur l'appareil.
Future<void> showRating(
  BuildContext context,
  ScoutStore store,
  ScoutPost post,
) async => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  builder: (ctx) => ListenableBuilder(
    listenable: store,
    builder: (ctx, _) => ConstrainedBox(
      constraints: BoxConstraints(
        maxHeight: MediaQuery.sizeOf(ctx).height * 0.8,
      ),
      child: ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
        children: [
          Text('Fiche du clip', style: Theme.of(ctx).textTheme.headlineSmall),
          const SizedBox(height: 4),
          Text(post.description),
          Text(
            'Clip de ${store.talent(post.authorId).name}',
            style: Theme.of(ctx).textTheme.bodySmall,
          ),
          const SizedBox(height: 8),
          for (var i = 0; i < post.skills.length; i++) ...[
            if (i > 0) const Divider(height: 1),
            FicheSkill(
              skill: post.skills[i],
              showCommunity: false,
              myScore: store.ratings['${post.id}/${post.skills[i]}'],
              onRate: (value) => store.rate(post, post.skills[i], value),
            ),
          ],
          const SizedBox(height: 8),
          Text(
            'Démo : ta note reste sur cet appareil et disparaît au rechargement.',
            style: Theme.of(ctx).textTheme.bodySmall,
          ),
        ],
      ),
    ),
  ),
);

Future<void> showComments(
  BuildContext context,
  ScoutStore store,
  ScoutPost post,
) async {
  final controller = TextEditingController();
  ScoutComment? reply;
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (ctx) => ListenableBuilder(
      listenable: store,
      builder: (ctx, _) => StatefulBuilder(
        builder: (ctx, setState) => Padding(
          padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(ctx).bottom),
          child: SizedBox(
            height: MediaQuery.sizeOf(ctx).height * .72,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Commentaires (${post.comments.length})',
                    style: Theme.of(ctx).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 12),
                  Expanded(
                    child: ListView(
                      children: [
                        if (post.comments.isEmpty)
                          Text(
                            'Sois le premier à encourager ce talent.',
                            style: TextStyle(color: ctx.scout.gradin),
                          ),
                        for (final c in post.comments)
                          Padding(
                            padding: EdgeInsets.only(
                              left: c.parentId == null ? 0 : 36,
                              bottom: 8,
                            ),
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                PlayerAvatar(
                                  store.talent(c.authorId).name,
                                  size: c.parentId == null ? 36 : 28,
                                ),
                                const SizedBox(width: 10),
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        store.talent(c.authorId).name,
                                        style: const TextStyle(
                                          fontWeight: FontWeight.w600,
                                          fontSize: 14,
                                        ),
                                      ),
                                      Text(c.text),
                                      Wrap(
                                        children: [
                                          TextButton(
                                            onPressed: () =>
                                                setState(() => reply = c),
                                            child: const Text('Répondre'),
                                          ),
                                          if (c.authorId == 'me' ||
                                              post.authorId == 'me')
                                            TextButton(
                                              onPressed: () {
                                                store.deleteComment(post, c);
                                                if (reply?.id == c.id) {
                                                  setState(() => reply = null);
                                                }
                                              },
                                              child: const Text('Supprimer'),
                                            ),
                                          TextButton(
                                            onPressed: () =>
                                                showReport(ctx, store, c.id),
                                            child: const Text('Signaler'),
                                          ),
                                        ],
                                      ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ),
                  if (reply != null)
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            'Réponse à ${store.talent(reply!.authorId).name}',
                            style: TextStyle(color: ctx.scout.gradin),
                          ),
                        ),
                        IconButton(
                          tooltip: 'Annuler la réponse',
                          onPressed: () => setState(() => reply = null),
                          icon: const Icon(Icons.close),
                        ),
                      ],
                    ),
                  TextField(
                    controller: controller,
                    maxLength: 500,
                    decoration: const InputDecoration(
                      hintText: 'Ajouter un commentaire…',
                    ),
                  ),
                  ScoutButton(
                    'Envoyer le commentaire',
                    onPressed: () {
                      if (store.addComment(
                        post,
                        controller.text,
                        parentId: reply?.id,
                      )) {
                        controller.clear();
                        setState(() => reply = null);
                      }
                    },
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );
  controller.dispose();
}

Future<void> showHashtag(
  BuildContext context,
  ScoutStore store,
  String tag,
) async => showModalBottomSheet<void>(
  context: context,
  useSafeArea: true,
  builder: (ctx) => ListenableBuilder(
    listenable: store,
    builder: (ctx, _) => Padding(
      padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('#$tag', style: Theme.of(ctx).textTheme.headlineMedium),
          const SizedBox(height: 12),
          Text(
            '${store.posts.where((p) => p.hashtags.contains(tag) || p.skills.contains(tag)).length} publications dans cette démonstration',
            style: TextStyle(color: ctx.scout.gradin),
          ),
          const SizedBox(height: 20),
          ScoutButton(
            store.followedHashtags.contains(tag)
                ? 'Ne plus suivre ce hashtag'
                : 'Suivre ce hashtag',
            secondary: store.followedHashtags.contains(tag),
            onPressed: () => store.toggleHashtag(tag),
          ),
        ],
      ),
    ),
  ),
);

Future<void> editProfile(BuildContext context, ScoutStore store) async {
  final name = TextEditingController(text: store.me.name),
      bio = TextEditingController(text: store.me.bio);
  final form = GlobalKey<FormState>();
  await showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('À ton image.'),
      content: Form(
        key: form,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: name,
                maxLength: 50,
                decoration: const InputDecoration(labelText: 'Nom'),
                validator: (s) =>
                    s == null || s.trim().isEmpty ? 'Indique ton nom.' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: bio,
                maxLength: 160,
                maxLines: 3,
                decoration: const InputDecoration(labelText: 'Bio'),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx),
          child: const Text('Annuler'),
        ),
        FilledButton(
          onPressed: () {
            if (form.currentState!.validate()) {
              store.editProfile(name.text, bio.text);
              Navigator.pop(ctx);
            }
          },
          child: const Text('Enregistrer'),
        ),
      ],
    ),
  );
  name.dispose();
  bio.dispose();
}

Future<void> showBlocked(BuildContext context, ScoutStore store) async =>
    showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      builder: (ctx) => ListenableBuilder(
        listenable: store,
        builder: (ctx, _) => Padding(
          padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Utilisateurs bloqués',
                style: Theme.of(ctx).textTheme.titleLarge,
              ),
              const SizedBox(height: 12),
              if (store.blocked.isEmpty)
                Text(
                  'Aucun utilisateur bloqué.',
                  style: TextStyle(color: ctx.scout.gradin),
                ),
              for (final id in store.blocked.toList())
                TalentTile(
                  store.talent(id),
                  trailing: TextButton(
                    onPressed: () => store.unblock(id),
                    child: const Text('Débloquer'),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
