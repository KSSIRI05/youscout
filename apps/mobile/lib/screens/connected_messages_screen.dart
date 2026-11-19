import 'dart:async';
import 'package:flutter/material.dart';
import '../backend/api.dart';
import '../backend/chat_connection.dart';
import '../backend/chat_socket.dart';
import '../backend/connected_session.dart';
import '../core/theme.dart';

/// Messagerie instantanée (U21) : historique Chat, livraison en direct et reprise après coupure.
class ConnectedMessagesScreen extends StatefulWidget {
  const ConnectedMessagesScreen({
    required this.session,
    this.active = true,
    this.initialRecipient,
    this.connector,
    super.key,
  });
  final ConnectedSession session;

  /// La connexion s'ouvre au premier affichage de l'onglet, puis reste ouverte.
  final bool active;
  final String? initialRecipient;

  /// Remplace la connexion WebSocket réelle dans les tests.
  final ChatConnector? connector;
  @override
  State<ConnectedMessagesScreen> createState() =>
      _ConnectedMessagesScreenState();
}

/// Message tapé par l'appelant, pas encore accusé par Chat ; le même identifiant sert au renvoi.
/// Remplace le texte d'un message dont l'expéditeur a fermé son compte (U22).
const deletedText = 'Message supprimé (compte fermé)';

class _Outgoing {
  _Outgoing(this.clientMessageId, this.body);
  final String clientMessageId, body;
  String? failure;
}

class _ConnectedMessagesScreenState extends State<ConnectedMessagesScreen> {
  final recipient = TextEditingController();
  final message = TextEditingController();
  late final ChatConnection chat;
  final subscriptions = <StreamSubscription<Object?>>[];
  List<DirectMessage> messages = [];
  final outgoing = <_Outgoing>[];
  List<Map<String, dynamic>> conversations = [];
  int peerDeliveredSeq = 0;
  bool loading = false, started = false;
  String? activeRecipient;

  String? get me => widget.session.profile?.subject;
  bool get hasOlder => messages.isNotEmpty && messages.first.seq > 1;

  @override
  void initState() {
    super.initState();
    chat = ChatConnection(
      socketUri: widget.session.chatSocketUri,
      token: widget.session.chatToken,
      restSend: widget.session.chatSend,
      connector: widget.connector,
    )..addListener(_changed);
    subscriptions
      ..add(chat.messages.listen(_received))
      ..add(chat.delivered.listen(_peerReceived))
      ..add(chat.reconnected.listen((_) => _catchUp()));
    final value = widget.initialRecipient;
    if (value != null) recipient.text = value;
    _startWhenVisible();
  }

  @override
  void didUpdateWidget(ConnectedMessagesScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    _startWhenVisible();
  }

  void _startWhenVisible() {
    if (!widget.active || started) return;
    started = true;
    chat.start();
    final value = widget.initialRecipient;
    WidgetsBinding.instance.addPostFrameCallback(
      (_) => value != null ? load() : _loadConversations(),
    );
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  void _notice(String text) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
    }
  }

  Future<void> _loadConversations() async {
    try {
      final list = await widget.session.chatConversations();
      if (mounted) setState(() => conversations = list);
    } catch (_) {
      // La liste est un raccourci : l'identifiant reste saisissable à la main.
    }
  }

  Future<void> load([String? peer]) async {
    final target = (peer ?? recipient.text).trim();
    if (target.isEmpty) return;
    recipient.text = target;
    setState(() {
      loading = true;
      activeRecipient = target;
      messages = [];
      outgoing.clear();
      peerDeliveredSeq = 0;
    });
    try {
      final page = await widget.session.chatHistory(target);
      if (!mounted || activeRecipient != target) return;
      _merge(page);
    } catch (_) {
      _notice('Conversation indisponible. Vérifie l’identifiant du joueur.');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> loadOlder() async {
    final target = activeRecipient;
    if (target == null || !hasOlder) return;
    try {
      final page = await widget.session.chatHistory(
        target,
        beforeSeq: messages.first.seq,
      );
      if (mounted && activeRecipient == target) _merge(page);
    } catch (_) {
      _notice('Historique indisponible.');
    }
  }

  /// Après une coupure, relit uniquement ce qui suit la dernière séquence connue.
  Future<void> _catchUp() async {
    final target = activeRecipient;
    if (target == null) return _loadConversations();
    try {
      final page = await widget.session.chatHistory(
        target,
        afterSeq: messages.isEmpty ? 0 : messages.last.seq,
      );
      if (mounted && activeRecipient == target) _merge(page);
    } catch (_) {
      // La prochaine reprise ou un rechargement relira l'historique.
    }
  }

  void _merge(Map<String, dynamic> page) {
    final list = (page['messages'] as List)
        .map((item) => DirectMessage.fromJson(item as Map<String, dynamic>))
        .toList();
    setState(() {
      _add(list);
      final delivered = (page['peerDeliveredSeq'] as num?)?.toInt() ?? 0;
      if (delivered > peerDeliveredSeq) peerDeliveredSeq = delivered;
    });
    _acknowledge();
  }

  /// Fusionne par identifiant et trie par séquence : un message reçu deux fois n'apparaît qu'une fois.
  void _add(Iterable<DirectMessage> list) {
    final byId = {for (final m in messages) m.id: m};
    for (final m in list) {
      byId[m.id] = m;
      outgoing.removeWhere((o) => o.clientMessageId == m.clientMessageId);
    }
    messages = byId.values.toList()..sort((a, b) => a.seq.compareTo(b.seq));
  }

  void _acknowledge() {
    final target = activeRecipient;
    if (target == null || messages.isEmpty) return;
    if (messages.any((m) => m.senderSubject == target)) {
      chat.markDelivered(target, messages.last.seq);
    }
  }

  void _received(DirectMessage m) {
    final target = activeRecipient;
    if (!mounted) return;
    if (target == null) {
      _loadConversations();
      return;
    }
    if (m.senderSubject != target && m.recipientSubject != target) return;
    setState(() => _add([m]));
    _acknowledge();
  }

  void _peerReceived(({String peer, int seq}) event) {
    if (mounted &&
        event.peer == activeRecipient &&
        event.seq > peerDeliveredSeq) {
      setState(() => peerDeliveredSeq = event.seq);
    }
  }

  Future<void> send() async {
    final text = message.text.trim();
    if (activeRecipient == null || text.isEmpty) return;
    final entry = _Outgoing(ChatConnection.newClientMessageId(), text);
    setState(() {
      outgoing.add(entry);
      message.clear();
    });
    await _deliver(entry);
  }

  Future<void> _deliver(_Outgoing entry) async {
    final target = activeRecipient;
    if (target == null) return;
    if (entry.failure != null) setState(() => entry.failure = null);
    try {
      final sent = await chat.send(target, entry.body, entry.clientMessageId);
      if (mounted && activeRecipient == target) setState(() => _add([sent]));
    } on ApiFailure catch (failure) {
      if (mounted) {
        setState(
          () => entry.failure = switch (failure.status) {
            403 => 'Un blocage empêche cet échange.',
            404 => 'Ce joueur est introuvable.',
            400 => 'Le message doit contenir de 1 à 2000 caractères.',
            _ => 'Message non envoyé.',
          },
        );
      }
    } catch (_) {
      if (mounted) setState(() => entry.failure = 'Message non envoyé.');
    }
  }

  @override
  void dispose() {
    for (final subscription in subscriptions) {
      subscription.cancel();
    }
    chat
      ..removeListener(_changed)
      ..dispose();
    recipient.dispose();
    message.dispose();
    super.dispose();
  }

  Widget _bubble({
    required bool mine,
    required String body,
    String? status,
    bool failed = false,
    VoidCallback? onRetry,
  }) => Align(
    alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
    child: Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: mine
              ? CrossAxisAlignment.end
              : CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(body),
            if (status != null)
              Text(
                status,
                style: TextStyle(
                  fontSize: 12,
                  color: failed ? Colors.redAccent : ScoutColors.muted,
                ),
              ),
            if (onRetry != null)
              TextButton(onPressed: onRetry, child: const Text('Réessayer')),
          ],
        ),
      ),
    ),
  );

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.all(20),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Messages', style: Theme.of(context).textTheme.headlineSmall),
        Text(
          switch (chat.link) {
            ChatLink.live => 'En direct · messages enregistrés sur YouScout',
            ChatLink.connecting => 'Connexion à la messagerie…',
            ChatLink.offline =>
              'Hors ligne · reprise automatique, les envois passent par le serveur',
          },
          key: const Key('chat-link'),
          style: const TextStyle(color: ScoutColors.muted),
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
          child: activeRecipient == null
              ? (conversations.isEmpty
                    ? const Center(
                        child: Text(
                          'Ouvre une conversation avec l’identifiant du joueur.',
                        ),
                      )
                    : ListView(
                        children: [
                          for (final c in conversations)
                            ListTile(
                              title: Text(c['peerSubject'] as String),
                              subtitle: Text(
                                (c['lastMessage'] as Map)['redacted'] == true
                                    ? deletedText
                                    : (c['lastMessage'] as Map)['body']
                                          as String,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                              onTap: () => load(c['peerSubject'] as String),
                            ),
                        ],
                      ))
              : messages.isEmpty && outgoing.isEmpty
              ? const Center(child: Text('Aucun message pour l’instant.'))
              : ListView(
                  children: [
                    if (hasOlder)
                      Center(
                        child: TextButton(
                          onPressed: loadOlder,
                          child: const Text('Messages plus anciens'),
                        ),
                      ),
                    for (final m in messages)
                      _bubble(
                        mine: m.senderSubject == me,
                        body: m.redacted ? deletedText : m.body,
                        status: m.senderSubject != me
                            ? null
                            : m.seq <= peerDeliveredSeq
                            ? 'Reçu'
                            : 'Envoyé',
                      ),
                    for (final o in outgoing)
                      _bubble(
                        mine: true,
                        body: o.body,
                        status: o.failure ?? 'Envoi…',
                        failed: o.failure != null,
                        onRetry: o.failure == null ? null : () => _deliver(o),
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
