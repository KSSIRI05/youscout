import 'package:flutter/material.dart';
import '../core/theme.dart';
import '../state/scout_store.dart';
import '../widgets/scout_widgets.dart';

class InboxScreen extends StatefulWidget {
  const InboxScreen({required this.store, super.key});
  final ScoutStore store;
  @override
  State<InboxScreen> createState() => _InboxScreenState();
}

class _InboxScreenState extends State<InboxScreen> {
  String query = '';
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.store,
    builder: (context, _) {
      final people = widget.store.talents
          .where(
            (t) =>
                t.id != 'me' &&
                !widget.store.blocked.contains(t.id) &&
                t.name.toLowerCase().contains(query.toLowerCase()),
          )
          .toList();
      return ListView(
        padding: const EdgeInsets.all(24),
        children: [
          const SectionHeading(
            'Les bonnes connexions.',
            subtitle: 'Échange avec la communauté du football.',
          ),
          const SizedBox(height: 16),
          TextField(
            onChanged: (v) => setState(() => query = v),
            decoration: const InputDecoration(
              hintText: 'Rechercher une conversation',
              prefixIcon: Icon(Icons.search),
            ),
          ),
          const SizedBox(height: 18),
          for (final t in people)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: TalentTile(
                t,
                subtitle:
                    widget.store.conversations[t.id]?.lastOrNull?.text ??
                    'Commencer une conversation',
                onTap: () => Navigator.push(
                  context,
                  MaterialPageRoute<void>(
                    builder: (_) =>
                        ChatScreen(store: widget.store, recipient: t.id),
                  ),
                ),
              ),
            ),
          if (people.isEmpty)
            const EmptyState(
              'Aucune conversation trouvée.',
              'Essaie un autre nom.',
            ),
          const SizedBox(height: 12),
          const Text(
            'Démo locale : les messages ne sont pas transmis à une autre personne.',
            style: TextStyle(color: ScoutColors.muted, fontSize: 12),
          ),
        ],
      );
    },
  );
}

class ChatScreen extends StatefulWidget {
  const ChatScreen({required this.store, required this.recipient, super.key});
  final ScoutStore store;
  final String recipient;
  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final input = TextEditingController();
  final scroll = ScrollController();
  @override
  void dispose() {
    input.dispose();
    scroll.dispose();
    super.dispose();
  }

  void send() {
    if (widget.store.send(widget.recipient, input.text)) {
      input.clear();
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (scroll.hasClients) {
          scroll.animateTo(
            scroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 200),
            curve: Curves.easeOut,
          );
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(widget.store.talent(widget.recipient).name)),
    body: SafeArea(
      child: ListenableBuilder(
        listenable: widget.store,
        builder: (context, _) {
          final blocked = widget.store.blocked.contains(widget.recipient);
          final messages = widget.store.conversations[widget.recipient] ?? [];
          return Column(
            children: [
              const Padding(
                padding: EdgeInsets.all(12),
                child: Text(
                  'Conversation de démonstration · enregistrement local uniquement',
                  style: TextStyle(fontSize: 11, color: ScoutColors.muted),
                  textAlign: TextAlign.center,
                ),
              ),
              Expanded(
                child: ListView.builder(
                  controller: scroll,
                  padding: const EdgeInsets.all(20),
                  itemCount: messages.length,
                  itemBuilder: (_, i) {
                    final m = messages[i];
                    return Align(
                      alignment: m.fromMe
                          ? Alignment.centerRight
                          : Alignment.centerLeft,
                      child: Container(
                        constraints: const BoxConstraints(maxWidth: 310),
                        margin: const EdgeInsets.only(bottom: 12),
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: m.fromMe
                              ? ScoutColors.raised
                              : ScoutColors.surface,
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(m.text),
                            const SizedBox(height: 6),
                            Text(
                              '${m.at.hour.toString().padLeft(2, '0')}:${m.at.minute.toString().padLeft(2, '0')}${m.fromMe ? ' · Local' : ''}',
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                          ],
                        ),
                      ),
                    );
                  },
                ),
              ),
              if (blocked)
                const Padding(
                  padding: EdgeInsets.all(20),
                  child: Text(
                    'Débloque cet utilisateur pour reprendre la conversation.',
                  ),
                )
              else
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      Expanded(
                        child: TextField(
                          key: const Key('message-input'),
                          controller: input,
                          minLines: 1,
                          maxLines: 4,
                          maxLength: 2000,
                          decoration: const InputDecoration(
                            hintText: 'Écrire un message…',
                            counterText: '',
                          ),
                          onSubmitted: (_) => send(),
                        ),
                      ),
                      const SizedBox(width: 8),
                      IconButton.filled(
                        tooltip: 'Envoyer le message',
                        onPressed: send,
                        icon: const Icon(Icons.send_outlined),
                      ),
                    ],
                  ),
                ),
            ],
          );
        },
      ),
    ),
  );
}
