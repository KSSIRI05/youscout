import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:flutter/foundation.dart';
import 'api.dart';
import 'chat_socket.dart';

/// Message enregistré par Chat ; `seq` numérote la conversation sans trou.
class DirectMessage {
  DirectMessage.fromJson(Map<String, dynamic> json)
    : id = json['id'] as String,
      conversationId = json['conversationId'] as String,
      seq = (json['seq'] as num).toInt(),
      senderSubject = json['senderSubject'] as String,
      recipientSubject = json['recipientSubject'] as String,
      clientMessageId = json['clientMessageId'] as String,
      body = json['body'] as String,
      redacted = json['redacted'] == true,
      acceptedAt = DateTime.parse(json['acceptedAt'] as String);
  final String id, conversationId, senderSubject, recipientSubject;
  final String clientMessageId, body;

  /// U22 : texte effacé après la fermeture du compte de l'expéditeur.
  final bool redacted;
  final int seq;
  final DateTime acceptedAt;
}

enum ChatLink { connecting, live, offline }

typedef ChatToken = ({String access, DateTime expiresAt});

/// Connexion temps réel à Chat. La première trame authentifie, le jeton est renvoyé avant son
/// expiration et la connexion est rétablie avec un délai croissant. Après une reprise, l'écran
/// relit l'historique depuis sa dernière séquence ([reconnected]).
class ChatConnection extends ChangeNotifier {
  ChatConnection({
    required this.socketUri,
    required this.token,
    required this.restSend,
    ChatConnector? connector,
    Duration Function(int attempt)? backoff,
    this.acceptTimeout = const Duration(seconds: 8),
  }) : connector = connector ?? connectChatChannel,
       backoff =
           backoff ?? ((attempt) => Duration(seconds: min(30, 1 << attempt)));

  final Uri socketUri;
  final Future<ChatToken> Function() token;
  final Future<Map<String, dynamic>> Function(
    String peer,
    String clientMessageId,
    String body,
  )
  restSend;
  final ChatConnector connector;
  final Duration Function(int attempt) backoff;
  final Duration acceptTimeout;

  ChatLink link = ChatLink.connecting;
  final _messages = StreamController<DirectMessage>.broadcast();
  final _delivered = StreamController<({String peer, int seq})>.broadcast();
  final _reconnected = StreamController<void>.broadcast();
  final Map<String, Completer<DirectMessage>> _pending = {};
  ChatChannel? _channel;
  Timer? _ping, _renew, _retry;
  bool _ready = false, _everReady = false, _disposed = false;
  int _attempt = 0;

  Stream<DirectMessage> get messages => _messages.stream;
  Stream<({String peer, int seq})> get delivered => _delivered.stream;
  Stream<void> get reconnected => _reconnected.stream;

  static String newClientMessageId() {
    final random = Random.secure();
    return List.generate(
      16,
      (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'),
    ).join();
  }

  void start() => _open();

  Future<void> _open() async {
    if (_disposed) return;
    _setLink(ChatLink.connecting);
    try {
      final channel = await connector(socketUri);
      if (_disposed) {
        await channel.close();
        return;
      }
      _channel = channel;
      channel.frames.listen(
        _onFrame,
        onDone: () => _onClosed(channel),
        onError: (Object _) => _onClosed(channel),
      );
      await _authenticate(channel);
    } catch (_) {
      _scheduleRetry();
    }
  }

  Future<void> _authenticate(ChatChannel channel) async {
    final current = await token();
    if (_disposed || _channel != channel) return;
    channel.send(jsonEncode({'type': 'auth', 'token': current.access}));
    // Renouvelle avant l'expiration : Chat ferme la connexion d'un jeton expiré.
    var wait =
        current.expiresAt.difference(DateTime.now()) -
        const Duration(seconds: 45);
    if (wait < const Duration(seconds: 10)) wait = const Duration(seconds: 10);
    _renew?.cancel();
    _renew = Timer(wait, () => _authenticate(channel).catchError((_) {}));
  }

  void _onFrame(String raw) {
    final Map<String, dynamic> frame;
    try {
      frame = jsonDecode(raw) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    switch (frame['type']) {
      case 'ready':
        _ready = true;
        _attempt = 0;
        _ping ??= Timer.periodic(
          const Duration(seconds: 30),
          (_) => _channel?.send('{"type":"ping"}'),
        );
        if (_everReady) _reconnected.add(null);
        _everReady = true;
        _setLink(ChatLink.live);
      case 'accepted':
        final message = DirectMessage.fromJson(
          frame['message'] as Map<String, dynamic>,
        );
        _pending.remove(frame['clientMessageId'])?.complete(message);
      case 'message':
        _messages.add(
          DirectMessage.fromJson(frame['message'] as Map<String, dynamic>),
        );
      case 'delivered':
        _delivered.add((
          peer: frame['peerSubject'] as String,
          seq: (frame['seq'] as num).toInt(),
        ));
      case 'error':
        final status = (frame['status'] as num?)?.toInt() ?? 503;
        _pending
            .remove(frame['clientMessageId'])
            ?.completeError(
              ApiFailure(
                status,
                frame['error'] as String? ?? 'Message refusé.',
              ),
            );
    }
  }

  void _onClosed(ChatChannel channel) {
    if (_channel != channel) return;
    _channel = null;
    _ready = false;
    _ping?.cancel();
    _ping = null;
    _renew?.cancel();
    if (_disposed) return;
    _setLink(ChatLink.offline);
    // 4403 : rôle absent ou changement de compte, une reconnexion ne changerait rien.
    if (channel.closeCode != 4403) _scheduleRetry();
  }

  void _scheduleRetry() {
    if (_disposed) return;
    _setLink(ChatLink.offline);
    _retry?.cancel();
    _retry = Timer(backoff(_attempt++), _open);
  }

  /// Envoie par WebSocket, ou par REST sans connexion ou sans accusé : le même
  /// [clientMessageId] garantit que le serveur n'enregistre le message qu'une fois.
  Future<DirectMessage> send(
    String peer,
    String body,
    String clientMessageId,
  ) async {
    final channel = _channel;
    if (channel != null && _ready) {
      final accepted = _pending[clientMessageId] = Completer<DirectMessage>();
      channel.send(
        jsonEncode({
          'type': 'send',
          'to': peer,
          'clientMessageId': clientMessageId,
          'body': body,
        }),
      );
      try {
        return await accepted.future.timeout(acceptTimeout);
      } on TimeoutException {
        _pending.remove(clientMessageId);
      }
    }
    return DirectMessage.fromJson(await restSend(peer, clientMessageId, body));
  }

  /// Confirme la réception de la conversation jusqu'à [seq] ; reprise à la prochaine lecture sinon.
  void markDelivered(String peer, int seq) {
    if (_ready) {
      _channel?.send(
        jsonEncode({'type': 'delivered', 'peer': peer, 'seq': seq}),
      );
    }
  }

  void _setLink(ChatLink value) {
    if (link == value || _disposed) return;
    link = value;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _ping?.cancel();
    _renew?.cancel();
    _retry?.cancel();
    _channel?.close();
    for (final pending in _pending.values) {
      pending.completeError(const ApiFailure(503, 'Connexion fermée.'));
    }
    _pending.clear();
    _messages.close();
    _delivered.close();
    _reconnected.close();
    super.dispose();
  }
}
