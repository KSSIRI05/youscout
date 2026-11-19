import 'package:flutter/material.dart';
import '../backend/api.dart';
import '../backend/connected_session.dart';
import '../core/theme.dart';
import '../widgets/scout_widgets.dart';
import '../widgets/terrain.dart';
import 'connected_upload_screen.dart';
import 'connected_feed_screen.dart';
import 'connected_messages_screen.dart';
import 'connected_notifications_screen.dart';
import 'dart:async';

/// Onglets du mode connecté, dans le même ordre que la démo.
abstract final class ConnectedTab {
  static const feed = 0, explore = 1, upload = 2, messages = 3, profile = 4;
}

class ConnectedScreen extends StatefulWidget {
  const ConnectedScreen({required this.session, super.key});
  final ConnectedSession session;
  @override
  State<ConnectedScreen> createState() => _ConnectedScreenState();
}

class _ConnectedScreenState extends State<ConnectedScreen> {
  int tab = ConnectedTab.feed;
  String? messageRecipient;
  int unread = 0;
  Timer? _unreadPoll;

  @override
  void initState() {
    super.initState();
    _refreshUnread();
    // Le badge se met à jour sans push : un relevé toutes les 30 s tant que l'écran est ouvert.
    _unreadPoll = Timer.periodic(
      const Duration(seconds: 30),
      (_) => _refreshUnread(),
    );
  }

  @override
  void dispose() {
    _unreadPoll?.cancel();
    super.dispose();
  }

  Future<void> _refreshUnread() async {
    if (!widget.session.signedIn) return;
    try {
      final count = await widget.session.unreadNotifications();
      if (mounted) setState(() => unread = count);
    } catch (_) {
      // Notification indisponible (profil feed absent) : le badge reste tel quel.
    }
  }

  Future<void> _openNotifications() async {
    final peer = await Navigator.push<String>(
      context,
      MaterialPageRoute(
        builder: (_) => ConnectedNotificationsScreen(session: widget.session),
      ),
    );
    if (!mounted) return;
    if (peer != null) {
      setState(() {
        messageRecipient = peer;
        tab = ConnectedTab.messages;
      });
    }
    await _refreshUnread();
  }

  @override
  Widget build(BuildContext context) {
    final session = widget.session;
    final ready = session.profile != null;
    return Scaffold(
      appBar: AppBar(
        title: const Text('YouScout'),
        actions: [
          IconButton(
            key: const Key('notifications'),
            tooltip: 'Notifications',
            onPressed: _openNotifications,
            icon: Badge(
              isLabelVisible: unread > 0,
              label: Text(unread > 99 ? '99+' : '$unread'),
              child: const Icon(Icons.notifications_outlined),
            ),
          ),
          IconButton(
            tooltip: 'Actualiser',
            onPressed: session.busy ? null : session.reload,
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: 'Se déconnecter',
            onPressed: session.busy ? null : session.logout,
            icon: const Icon(Icons.logout),
          ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            if (session.busy) const LinearProgressIndicator(),
            if (session.error != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 12, 20, 4),
                child: ScoutNotice(
                  session.error!,
                  error: true,
                  key: const Key('connected-error'),
                ),
              ),
            Expanded(
              child: IndexedStack(
                index: tab,
                children: [
                  ready
                      ? ConnectedFeedScreen(
                          session: session,
                          active: tab == ConnectedTab.feed,
                          onMessage: (recipient) => setState(() {
                            messageRecipient = recipient;
                            tab = ConnectedTab.messages;
                          }),
                        )
                      : const SizedBox.shrink(),
                  CataloguePanel(session: session),
                  ready
                      ? ConnectedUploadScreen(
                          active: tab == ConnectedTab.upload,
                          key: ValueKey(session.profile!.subject),
                          session: session,
                        )
                      : const SizedBox.shrink(),
                  ready
                      ? ConnectedMessagesScreen(
                          key: ValueKey(messageRecipient ?? 'messages'),
                          active: tab == ConnectedTab.messages,
                          session: session,
                          initialRecipient: messageRecipient,
                        )
                      : const SizedBox.shrink(),
                  ready
                      ? ProfileEditor(
                          key: ValueKey(session.profile!.subject),
                          session: session,
                          profile: session.profile!,
                        )
                      : Center(
                          child: FilledButton(
                            onPressed: session.busy ? null : session.reload,
                            child: const Text('Recharger mon profil'),
                          ),
                        ),
                ],
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: ScoutTabs(
        selected: tab,
        onSelected: (value) => setState(() => tab = value),
      ),
    );
  }
}

class ProfileEditor extends StatefulWidget {
  const ProfileEditor({
    required this.session,
    required this.profile,
    super.key,
  });
  final ConnectedSession session;
  final AccountProfile profile;
  @override
  State<ProfileEditor> createState() => _ProfileEditorState();
}

class _ProfileEditorState extends State<ProfileEditor> {
  late final name = TextEditingController(text: widget.profile.name);
  late final bio = TextEditingController(text: widget.profile.biography);
  final form = GlobalKey<FormState>();
  bool saved = false;
  @override
  void didUpdateWidget(covariant ProfileEditor oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.profile.name != widget.profile.name) {
      name.text = widget.profile.name;
    }
    if (oldWidget.profile.biography != widget.profile.biography) {
      bio.text = widget.profile.biography;
    }
  }

  @override
  void dispose() {
    name.dispose();
    bio.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    final text = Theme.of(context).textTheme;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      children: [
        SizedBox(
          height: 168,
          child: PitchSurface(
            radius: 14,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(18, 0, 18, 18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  Text(
                    widget.profile.name,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: scoutDisplay(44, p.surPelouse),
                  ),
                ],
              ),
            ),
          ),
        ),
        const SizedBox(height: 12),
        Text(
          widget.profile.biography.isEmpty
              ? 'Ajoute une biographie pour dire qui tu es sur le terrain.'
              : widget.profile.biography,
          style: TextStyle(
            color: widget.profile.biography.isEmpty ? p.gradin : p.encre,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          'Ton profil est enregistré dans ton compte.',
          style: text.bodySmall,
        ),
        const SizedBox(height: 28),
        Text('Modifier mon profil', style: text.titleLarge),
        const SizedBox(height: 14),
        Form(
          key: form,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                key: const Key('account-name'),
                controller: name,
                enabled: !widget.session.busy,
                maxLength: 80,
                onChanged: (_) => setState(() => saved = false),
                decoration: const InputDecoration(labelText: 'Nom'),
                validator: (value) =>
                    value == null ||
                        value.trim().isEmpty ||
                        value.trim().runes.length > 80
                    ? 'Indique un nom de 1 à 80 caractères.'
                    : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                key: const Key('account-biography'),
                controller: bio,
                enabled: !widget.session.busy,
                maxLength: 500,
                maxLines: 5,
                onChanged: (_) => setState(() => saved = false),
                decoration: const InputDecoration(
                  labelText: 'Biographie',
                  alignLabelWithHint: true,
                ),
                validator: (value) => (value?.runes.length ?? 0) > 500
                    ? '500 caractères maximum.'
                    : null,
              ),
              const SizedBox(height: 16),
              FilledButton(
                key: const Key('save-account'),
                onPressed: widget.session.busy
                    ? null
                    : () async {
                        if (!form.currentState!.validate()) return;
                        await widget.session.saveProfile(name.text, bio.text);
                        if (mounted) {
                          setState(() => saved = widget.session.error == null);
                        }
                      },
                child: const Text('Enregistrer mon profil'),
              ),
              if (saved)
                const Padding(
                  padding: EdgeInsets.only(top: 14),
                  child: ScoutNotice('Profil enregistré.'),
                ),
            ],
          ),
        ),
        const Divider(height: 56),
        Text('Fermer mon compte', style: text.titleLarge),
        const SizedBox(height: 8),
        Text(
          'La fermeture est définitive : tu es déconnecté partout et tes données '
          'sont effacées de chaque service.',
          style: TextStyle(color: p.gradin),
        ),
        const SizedBox(height: 14),
        OutlinedButton(
          key: const Key('close-account'),
          style: OutlinedButton.styleFrom(
            foregroundColor: p.alerte,
            side: BorderSide(color: p.alerte, width: 1.5),
          ),
          onPressed: widget.session.busy
              ? null
              : () async {
                  final confirmed = await showDialog<bool>(
                    context: context,
                    builder: (_) => const CloseAccountDialog(),
                  );
                  if (confirmed == true) {
                    await widget.session.closeAccount(
                      CloseAccountDialog.confirmation,
                    );
                  }
                },
          child: const Text('Fermer mon compte'),
        ),
      ],
    );
  }
}

/// U22 : explique les conséquences puis exige la saisie de FERMER avant d'activer la confirmation.
class CloseAccountDialog extends StatefulWidget {
  const CloseAccountDialog({super.key});
  static const confirmation = 'FERMER';
  @override
  State<CloseAccountDialog> createState() => _CloseAccountDialogState();
}

class _CloseAccountDialogState extends State<CloseAccountDialog> {
  final typed = TextEditingController();
  @override
  void dispose() {
    typed.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    return AlertDialog(
      title: const Text('Fermer ton compte ?'),
      scrollable: true,
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Tes vidéos, likes, commentaires, notes et abonnements seront '
            'supprimés. Le texte de tes messages sera effacé chez tes '
            'interlocuteurs. Ton profil disparaîtra et ton adresse e-mail sera '
            'libérée. Cette action est irréversible.',
          ),
          const SizedBox(height: 16),
          TextField(
            key: const Key('close-account-confirmation'),
            controller: typed,
            autofocus: true,
            onChanged: (_) => setState(() {}),
            decoration: const InputDecoration(
              labelText: 'Saisis FERMER pour confirmer',
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Annuler'),
        ),
        FilledButton(
          key: const Key('close-account-confirm'),
          style: FilledButton.styleFrom(
            backgroundColor: p.alerte,
            foregroundColor: Theme.of(context).colorScheme.onError,
          ),
          onPressed: typed.text == CloseAccountDialog.confirmation
              ? () => Navigator.pop(context, true)
              : null,
          child: const Text('Fermer définitivement'),
        ),
      ],
    );
  }
}

class CataloguePanel extends StatefulWidget {
  const CataloguePanel({required this.session, super.key});
  final ConnectedSession session;
  @override
  State<CataloguePanel> createState() => _CataloguePanelState();
}

class _CataloguePanelState extends State<CataloguePanel> {
  bool tags = false;
  final skillSearch = TextEditingController(),
      tagSearch = TextEditingController(),
      newTag = TextEditingController();
  @override
  void dispose() {
    skillSearch.dispose();
    tagSearch.dispose();
    newTag.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    final session = widget.session;
    final search = tags ? tagSearch : skillSearch;
    final items = tags ? session.hashtags : session.skills;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      children: [
        Text(
          'Découvre ton terrain.',
          style: Theme.of(context).textTheme.headlineMedium,
        ),
        const SizedBox(height: 8),
        Text(
          tags
              ? 'Suis des hashtags pour retrouver les clips qui t’intéressent.'
              : 'Les compétences que les joueurs montrent et que la communauté note.',
          style: TextStyle(color: p.gradin),
        ),
        const SizedBox(height: 18),
        SegmentedButton<bool>(
          showSelectedIcon: false,
          segments: const [
            ButtonSegment(value: false, label: Text('Compétences')),
            ButtonSegment(value: true, label: Text('Hashtags')),
          ],
          selected: {tags},
          onSelectionChanged: (value) => setState(() => tags = value.single),
        ),
        const SizedBox(height: 16),
        TextField(
          key: Key(tags ? 'tag-search' : 'skill-search'),
          controller: search,
          maxLength: 80,
          decoration: InputDecoration(
            labelText: 'Rechercher',
            counterText: '',
            suffixIcon: IconButton(
              tooltip: 'Lancer la recherche',
              onPressed: session.busy
                  ? null
                  : () => session.search(tags: tags, query: search.text),
              icon: const Icon(Icons.search),
            ),
          ),
          onSubmitted: session.busy
              ? null
              : (value) => session.search(tags: tags, query: value),
        ),
        if (tags) ...[
          const SizedBox(height: 18),
          Text(
            '${session.following.length} hashtag(s) suivi(s)',
            style: TextStyle(color: p.valide, fontWeight: FontWeight.w500),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const Key('new-hashtag'),
            controller: newTag,
            decoration: const InputDecoration(
              labelText: 'Un nouveau hashtag à suivre',
              hintText: '#football',
            ),
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: session.busy
                  ? null
                  : () async {
                      await session.createAndFollow(newTag.text);
                      if (mounted && session.error == null) {
                        newTag.clear();
                        tagSearch.clear();
                      }
                    },
              icon: const Icon(Icons.add),
              label: const Text('Créer ou retrouver et suivre'),
            ),
          ),
        ],
        const SizedBox(height: 12),
        if (items.isEmpty && !session.busy)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 24),
            child: Text('Aucun résultat.'),
          ),
        for (final item in items) ...[
          Divider(height: 1, color: p.ligne),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        tags ? '#${item.name}' : item.name,
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      if (item.description.isNotEmpty)
                        Text(
                          item.description,
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                    ],
                  ),
                ),
                if (tags) _FollowTagButton(session: session, item: item),
              ],
            ),
          ),
        ],
        if (tags ? session.moreHashtags : session.moreSkills)
          TextButton(
            onPressed: session.busy
                ? null
                : () => session.search(
                    tags: tags,
                    query: search.text,
                    next: true,
                  ),
            child: const Text('Afficher la suite'),
          ),
      ],
    );
  }
}

class _FollowTagButton extends StatelessWidget {
  const _FollowTagButton({required this.session, required this.item});
  final ConnectedSession session;
  final CatalogueItem item;
  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    final followed = session.following.contains(item.id);
    final onPressed = session.busy ? null : () => session.toggleHashtag(item);
    return Tooltip(
      message: followed
          ? 'Ne plus suivre #${item.name}'
          : 'Suivre #${item.name}',
      child: followed
          ? FilledButton.icon(
              key: Key('follow-${item.id}'),
              style: FilledButton.styleFrom(
                backgroundColor: p.pelouse,
                foregroundColor: p.surPelouse,
                minimumSize: const Size(48, 40),
              ),
              onPressed: onPressed,
              icon: const Icon(Icons.check, size: 18),
              label: const Text('Suivi'),
            )
          : OutlinedButton(
              key: Key('follow-${item.id}'),
              style: OutlinedButton.styleFrom(minimumSize: const Size(48, 40)),
              onPressed: onPressed,
              child: const Text('Suivre'),
            ),
    );
  }
}
