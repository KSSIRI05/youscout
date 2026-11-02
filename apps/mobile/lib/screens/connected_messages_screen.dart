import 'package:flutter/material.dart';
import '../backend/connected_session.dart';
import '../core/theme.dart';

/// Messages persistants, accessibles uniquement aux deux membres de l’échange.
class ConnectedMessagesScreen extends StatefulWidget {
  const ConnectedMessagesScreen({
    required this.session,
    this.initialRecipient,
    super.key,
  });
  final ConnectedSession session;
  final String? initialRecipient;
  @override
  State<ConnectedMessagesScreen> createState() =>
      _ConnectedMessagesScreenState();
}

class _ConnectedMessagesScreenState extends State<ConnectedMessagesScreen> {
  final recipient = TextEditingController();
  final message = TextEditingController();
  List<Map<String, dynamic>> items = [];
  bool loading = false;
  String? activeRecipient;

  @override
  void initState() {
    super.initState();
    final value = widget.initialRecipient;
    if (value != null) {
      recipient.text = value;
      WidgetsBinding.instance.addPostFrameCallback((_) => load());
    }
  }

  Future<void> load() async {
    final target = recipient.text.trim();
    if (target.isEmpty) return;
    setState(() {
      loading = true;
      activeRecipient = target;
    });
    try {
      final result = await widget.session.socialList(
        'messages',
        query: {'with': target},
      );
      if (mounted) setState(() => items = result);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Conversation indisponible. Vérifie l’identifiant du joueur.',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> send() async {
    final text = message.text.trim();
    final target = activeRecipient;
    if (target == null || text.isEmpty) return;
    try {
      final sent = await widget.session.socialPost('messages', {
        'recipientSubject': target,
        'body': text,
      });
      if (!mounted) return;
      setState(() {
        items = [...items, sent];
        message.clear();
      });
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('Message non envoyé.')));
      }
    }
  }

  @override
  void dispose() {
    recipient.dispose();
    message.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.all(20),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Messages', style: Theme.of(context).textTheme.headlineSmall),
        const Text(
          'Échanges enregistrés sur le serveur YouScout.',
          style: TextStyle(color: ScoutColors.muted),
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: recipient,
                decoration: const InputDecoration(
                  labelText: 'Identifiant public du joueur',
                ),
                onSubmitted: (_) => load(),
              ),
            ),
            IconButton(onPressed: load, icon: const Icon(Icons.arrow_forward)),
          ],
        ),
        if (loading) const LinearProgressIndicator(),
        const SizedBox(height: 8),
        Expanded(
          child: items.isEmpty
              ? const Center(
                  child: Text(
                    'Ouvre une conversation avec l’identifiant du joueur.',
                  ),
                )
              : ListView(
                  children: [
                    for (final item in items)
                      Align(
                        alignment:
                            item['senderSubject'] ==
                                widget.session.profile?.subject
                            ? Alignment.centerRight
                            : Alignment.centerLeft,
                        child: Card(
                          child: Padding(
                            padding: const EdgeInsets.all(12),
                            child: Text(item['body'] as String),
                          ),
                        ),
                      ),
                  ],
                ),
        ),
        if (activeRecipient != null)
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: message,
                  maxLength: 2000,
                  decoration: const InputDecoration(
                    hintText: 'Écrire un message',
                    counterText: '',
                  ),
                  onSubmitted: (_) => send(),
                ),
              ),
              IconButton.filled(onPressed: send, icon: const Icon(Icons.send)),
            ],
          ),
      ],
    ),
  );
}
