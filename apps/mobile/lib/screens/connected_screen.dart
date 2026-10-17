import 'package:flutter/material.dart';
import '../backend/api.dart';
import '../backend/connected_session.dart';
import '../core/theme.dart';

class ConnectedScreen extends StatefulWidget {
  const ConnectedScreen({required this.session, super.key});
  final ConnectedSession session;
  @override
  State<ConnectedScreen> createState() => _ConnectedScreenState();
}

class _ConnectedScreenState extends State<ConnectedScreen> {
  int tab = 0;
  @override
  Widget build(BuildContext context) {
    final session = widget.session;
    return Scaffold(
      appBar: AppBar(
        title: const Text('YouScout'),
        actions: [
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
                padding: const EdgeInsets.all(16),
                child: Text(
                  session.error!,
                  key: const Key('connected-error'),
                  style: const TextStyle(color: Colors.orangeAccent),
                ),
              ),
            Expanded(
              child: tab == 0
                  ? session.profile == null
                        ? Center(
                            child: FilledButton(
                              onPressed: session.busy ? null : session.reload,
                              child: const Text('Recharger mon profil'),
                            ),
                          )
                        : ProfileEditor(
                            key: ValueKey(session.profile!.subject),
                            session: session,
                            profile: session.profile!,
                          )
                  : CataloguePanel(session: session),
            ),
          ],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (value) => setState(() => tab = value),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.person_outline),
            label: 'Mon profil',
          ),
          NavigationDestination(icon: Icon(Icons.tag), label: 'Catalogue'),
        ],
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
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(24),
    children: [
      Text(
        widget.profile.name,
        style: Theme.of(context).textTheme.headlineMedium,
      ),
      const SizedBox(height: 8),
      const Text(
        'Ton profil est enregistré dans ton compte.',
        style: TextStyle(color: ScoutColors.muted),
      ),
      const SizedBox(height: 24),
      Form(
        key: form,
        child: Column(
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
              decoration: const InputDecoration(labelText: 'Biographie'),
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
                padding: EdgeInsets.only(top: 16),
                child: Text(
                  'Profil enregistré.',
                  style: TextStyle(color: ScoutColors.brand),
                ),
              ),
          ],
        ),
      ),
    ],
  );
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
    final session = widget.session;
    final search = tags ? tagSearch : skillSearch;
    final items = tags ? session.hashtags : session.skills;
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        Text(
          'Découvre ton terrain.',
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 16),
        SegmentedButton<bool>(
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
          Text(
            '${session.following.length} hashtag(s) suivi(s)',
            style: const TextStyle(color: ScoutColors.brand),
          ),
          const SizedBox(height: 16),
          TextField(
            key: const Key('new-hashtag'),
            controller: newTag,
            decoration: const InputDecoration(
              labelText: 'Un nouveau hashtag à suivre',
              hintText: '#football',
            ),
          ),
          TextButton(
            onPressed: session.busy
                ? null
                : () async {
                    await session.createAndFollow(newTag.text);
                    if (mounted && session.error == null) {
                      newTag.clear();
                      tagSearch.clear();
                    }
                  },
            child: const Text('Créer ou retrouver et suivre'),
          ),
        ],
        if (items.isEmpty && !session.busy)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 24),
            child: Text('Aucun résultat.'),
          ),
        for (final item in items)
          Card(
            child: ListTile(
              title: Text(tags ? '#${item.name}' : item.name),
              subtitle: item.description.isEmpty
                  ? null
                  : Text(item.description),
              trailing: tags
                  ? IconButton(
                      key: Key('follow-${item.id}'),
                      tooltip: session.following.contains(item.id)
                          ? 'Ne plus suivre #${item.name}'
                          : 'Suivre #${item.name}',
                      onPressed: session.busy
                          ? null
                          : () => session.toggleHashtag(item),
                      icon: Icon(
                        session.following.contains(item.id)
                            ? Icons.check_circle
                            : Icons.add_circle_outline,
                        color: session.following.contains(item.id)
                            ? ScoutColors.brand
                            : null,
                      ),
                    )
                  : null,
            ),
          ),
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
