import 'package:flutter/material.dart';
import '../core/theme.dart';
import '../data/models.dart';
import '../state/scout_store.dart';
import '../widgets/local_video.dart';
import '../widgets/scout_widgets.dart';
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
        padding: const EdgeInsets.all(24),
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'YouScout',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
              ),
              const Text(
                'DÉMO',
                style: TextStyle(
                  color: ScoutColors.brand,
                  fontSize: 10,
                  letterSpacing: 1,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 16,
            children: [
              TextButton(
                onPressed: () => setState(() => following = false),
                child: Text(
                  'Pour toi',
                  style: TextStyle(
                    color: !following ? ScoutColors.brand : ScoutColors.muted,
                  ),
                ),
              ),
              TextButton(
                onPressed: () => setState(() => following = true),
                child: Text(
                  'Abonnements',
                  style: TextStyle(
                    color: following ? ScoutColors.brand : ScoutColors.muted,
                  ),
                ),
              ),
            ],
          ),
          if (posts.isEmpty)
            EmptyState(
              'Ton prochain coup de cœur t’attend.',
              'Suis un talent ou un hashtag pour retrouver ses publications ici.',
              action: ScoutButton(
                'Découvrir les talents',
                onPressed: widget.onExplore,
              ),
            ),
          for (final post in posts)
            Padding(
              padding: const EdgeInsets.only(bottom: 30),
              child: PostCard(post: post, store: widget.store),
            ),
        ],
      );
    },
  );
}

class PostCard extends StatelessWidget {
  const PostCard({required this.post, required this.store, super.key});
  final ScoutPost post;
  final ScoutStore store;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      if (post.mediaPath != null)
        LocalVideo(post.mediaPath!)
      else
        const FootballArt(height: 330),
      if (post.isDemo)
        const Padding(
          padding: EdgeInsets.only(bottom: 6),
          child: Text(
            'Illustration de démonstration',
            style: TextStyle(color: ScoutColors.muted, fontSize: 10),
          ),
        ),
      SkillChips(post.skills),
      const SizedBox(height: 10),
      TalentTile(
        store.talent(post.authorId),
        onTap: () => openTalent(context, store, post.authorId),
      ),
      const SizedBox(height: 14),
      Text(post.description),
      Wrap(
        spacing: 6,
        children: post.hashtags
            .map(
              (tag) => TextButton(
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 2),
                ),
                onPressed: () => showHashtag(context, store, tag),
                child: Text('#$tag'),
              ),
            )
            .toList(),
      ),
      Row(
        children: [
          Expanded(
            child: FilledButton.tonalIcon(
              key: Key('like-${post.id}'),
              onPressed: () => store.toggleLike(post.id),
              icon: Icon(
                store.liked.contains(post.id)
                    ? Icons.favorite
                    : Icons.favorite_border,
                size: 18,
              ),
              label: Text('${store.likeCount(post)}'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: FilledButton.tonal(
              onPressed: () => showComments(context, store, post),
              child: Text('${post.comments.length} avis'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: FilledButton(
              onPressed: () => showRating(context, store, post),
              child: const Text('Évaluer'),
            ),
          ),
        ],
      ),
      Align(
        alignment: Alignment.centerRight,
        child: PopupMenuButton<String>(
          tooltip: 'Options de la publication',
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
      ),
    ],
  );
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
        padding: const EdgeInsets.all(24),
        children: [
          const SectionHeading(
            'Le talent est partout.',
            subtitle:
                'Découvre les joueurs et les compétences qui t’inspirent.',
          ),
          const SizedBox(height: 16),
          TextField(
            key: const Key('talent-search'),
            onChanged: (v) => setState(() => query = v),
            decoration: const InputDecoration(
              hintText: 'Rechercher un talent, un hashtag…',
              prefixIcon: Icon(Icons.search),
            ),
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            children: ['Tous', 'Dribble', 'Frappe', 'Technique']
                .map(
                  (s) => ChoiceChip(
                    label: Text(s),
                    selected: skill == s,
                    onSelected: (_) => setState(() => skill = s),
                    labelStyle: TextStyle(
                      color: skill == s
                          ? ScoutColors.background
                          : ScoutColors.brand,
                    ),
                  ),
                )
                .toList(),
          ),
          const SizedBox(height: 18),
          const Text(
            'À DÉCOUVRIR CETTE SEMAINE',
            style: TextStyle(color: ScoutColors.brand, fontSize: 12),
          ),
          const SizedBox(height: 12),
          const FootballArt(variant: 'discover', height: 154),
          const SizedBox(height: 12),
          if (matches.isEmpty)
            const EmptyState(
              'Aucun talent trouvé.',
              'Essaie un autre nom ou une autre compétence.',
            ),
          for (final t in matches)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: TalentTile(
                t,
                onTap: () => openTalent(context, widget.store, t.id),
              ),
            ),
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

void openTalent(BuildContext context, ScoutStore store, String id) =>
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => TalentScreen(store: store, talentId: id),
      ),
    );

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
        return ListView(
          padding: const EdgeInsets.all(24),
          children: [
            TalentTile(t, subtitle: '${t.handle} · ${t.city}'),
            const SizedBox(height: 16),
            Text(
              t.position.toUpperCase(),
              style: const TextStyle(color: ScoutColors.brand),
            ),
            const SizedBox(height: 12),
            Text(t.bio),
            const SizedBox(height: 20),
            if (talentId != 'me') ...[
              ScoutButton(
                store.followed.contains(talentId) ? 'Ne plus suivre' : 'Suivre',
                onPressed: store.blocked.contains(talentId)
                    ? null
                    : () => store.toggleFollow(talentId),
              ),
              const SizedBox(height: 10),
              ScoutButton(
                'Message',
                secondary: true,
                onPressed: store.blocked.contains(talentId)
                    ? null
                    : () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) =>
                              ChatScreen(store: store, recipient: talentId),
                        ),
                      ),
              ),
              const SizedBox(height: 16),
            ],
            SkillChips(t.skills),
            const SizedBox(height: 16),
            if (store.blocked.contains(talentId))
              const EmptyState(
                'Utilisateur bloqué',
                'Ses publications sont masquées pour cette session.',
              ),
            for (final p in store.posts.where((p) => p.authorId == talentId))
              PostCard(post: p, store: store),
            if (talentId != 'me')
              TextButton(
                onPressed: () async {
                  if (store.blocked.contains(talentId)) {
                    store.unblock(talentId);
                  } else {
                    await confirmBlock(context, store, talentId);
                  }
                },
                child: Text(
                  store.blocked.contains(talentId)
                      ? 'Débloquer cet utilisateur'
                      : 'Bloquer cet utilisateur',
                ),
              ),
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
      return ListView(
        padding: const EdgeInsets.all(24),
        children: [
          const SectionHeading(
            'Mon terrain.',
            subtitle: 'Ton profil, tes vidéos et tes progrès.',
          ),
          const SizedBox(height: 20),
          TalentTile(store.me, subtitle: store.me.handle),
          const SizedBox(height: 16),
          Text(store.me.bio),
          const SizedBox(height: 16),
          Text(
            '${mine.length} publications · ${store.followed.length} talents suivis',
          ),
          const SizedBox(height: 16),
          ScoutButton(
            'Modifier mon profil',
            secondary: true,
            onPressed: () => editProfile(context, store),
          ),
          const SizedBox(height: 16),
          SkillChips(store.me.skills),
          const SizedBox(height: 16),
          for (final p in mine) PostCard(post: p, store: store),
          if (mine.isEmpty)
            const EmptyState(
              'Ton histoire commence ici.',
              'Ajoute ton premier clip pour montrer ton jeu.',
            ),
          ScoutButton('Publier une nouvelle vidéo', onPressed: onUpload),
          const SizedBox(height: 12),
          ScoutButton(
            'Utilisateurs bloqués',
            secondary: true,
            onPressed: () => showBlocked(context, store),
          ),
          const SizedBox(height: 12),
          TextButton(
            onPressed: store.leaveDemo,
            child: const Text('Quitter la démo'),
          ),
          const Text(
            'Données en mémoire : recharger l’application réinitialise la démonstration.',
            style: TextStyle(color: ScoutColors.muted, fontSize: 12),
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

Future<void> showRating(
  BuildContext context,
  ScoutStore store,
  ScoutPost post,
) async {
  var skill = post.skills.first;
  var value = store.ratings['${post.id}/$skill'] ?? 4;
  await showDialog<void>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, setState) => AlertDialog(
        title: const Text('Un regard qui aide.'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            DropdownButtonFormField<String>(
              initialValue: skill,
              items: post.skills
                  .map((s) => DropdownMenuItem(value: s, child: Text(s)))
                  .toList(),
              onChanged: (s) => setState(() {
                skill = s!;
                value = store.ratings['${post.id}/$skill'] ?? 4;
              }),
            ),
            const SizedBox(height: 20),
            Wrap(
              children: List.generate(
                5,
                (i) => IconButton(
                  tooltip: 'Noter ${i + 1} sur 5',
                  onPressed: () => setState(() => value = i + 1),
                  icon: Icon(
                    i < value ? Icons.star : Icons.star_border,
                    color: ScoutColors.brand,
                  ),
                ),
              ),
            ),
            Text('$value / 5'),
            const SizedBox(height: 8),
            const Text(
              'Échelle provisoire de la démonstration.',
              style: TextStyle(color: ScoutColors.muted, fontSize: 12),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Annuler'),
          ),
          FilledButton(
            onPressed: () {
              store.rate(post, skill, value);
              Navigator.pop(ctx);
            },
            child: const Text('Enregistrer'),
          ),
        ],
      ),
    ),
  );
}

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
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Commentaires · ${post.comments.length}',
                    style: Theme.of(ctx).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 12),
                  Expanded(
                    child: ListView(
                      children: [
                        if (post.comments.isEmpty)
                          const Text('Sois le premier à encourager ce talent.'),
                        for (final c in post.comments)
                          Padding(
                            padding: EdgeInsets.only(
                              left: c.parentId == null ? 0 : 20,
                              bottom: 12,
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  store.talent(c.authorId).name,
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w600,
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
                  if (reply != null)
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            'Réponse à ${store.talent(reply!.authorId).name}',
                            style: const TextStyle(color: ScoutColors.brand),
                          ),
                        ),
                        IconButton(
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
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('#$tag', style: Theme.of(ctx).textTheme.headlineSmall),
          const SizedBox(height: 16),
          Text(
            '${store.posts.where((p) => p.hashtags.contains(tag) || p.skills.contains(tag)).length} publications dans cette démonstration',
          ),
          const SizedBox(height: 20),
          ScoutButton(
            store.followedHashtags.contains(tag)
                ? 'Ne plus suivre ce hashtag'
                : 'Suivre ce hashtag',
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
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'Utilisateurs bloqués',
                style: Theme.of(ctx).textTheme.titleMedium,
              ),
              const SizedBox(height: 16),
              if (store.blocked.isEmpty)
                const Text('Aucun utilisateur bloqué.'),
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
