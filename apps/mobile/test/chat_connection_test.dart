import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:youscout_mobile/backend/api.dart';
import 'package:youscout_mobile/backend/chat_connection.dart';
import 'package:youscout_mobile/backend/chat_socket.dart';

class FakeChannel implements ChatChannel {
  final incoming = StreamController<String>();
  final sent = <Map<String, dynamic>>[];
  @override
  int? closeCode;
  @override
  Stream<String> get frames => incoming.stream;
  @override
  void send(String frame) =>
      sent.add(jsonDecode(frame) as Map<String, dynamic>);
  @override
  Future<void> close() async => incoming.close();
  void push(Map<String, Object?> frame) => incoming.add(jsonEncode(frame));
  void drop(int code) {
    closeCode = code;
    incoming.close();
  }
}

Map<String, Object> message(int seq, {String from = 'bob', String id = 'c'}) =>
    {
      'id': 'm-$seq',
      'conversationId': 'conv',
      'seq': seq,
      'senderSubject': from,
      'recipientSubject': from == 'bob' ? 'alice' : 'bob',
      'clientMessageId': '$id$seq',
      'body': 'Message $seq',
      'acceptedAt': '2026-11-18T10:00:00Z',
    };

void main() {
  late List<FakeChannel> channels;
  late List<String> restSends;
  late int tokens;
  ChatConnection connection({
    Duration accept = const Duration(seconds: 8),
    Duration ready = const Duration(seconds: 3),
  }) => ChatConnection(
    socketUri: Uri.parse('ws://localhost:8080/v1/chat/ws'),
    token: () async {
      tokens++;
      return (
        access: 'token-$tokens',
        expiresAt: DateTime.now().add(const Duration(minutes: 5)),
      );
    },
    restSend: (peer, clientId, body) async {
      restSends.add(clientId);
      return message(7, from: 'alice', id: clientId);
    },
    connector: (uri) async {
      final channel = FakeChannel();
      channels.add(channel);
      return channel;
    },
    backoff: (_) => Duration.zero,
    acceptTimeout: accept,
    readyTimeout: ready,
  );

  setUp(() {
    channels = [];
    restSends = [];
    tokens = 0;
  });

  test('authenticates first, then sends and receives live', () async {
    final chat = connection()..start();
    await pumpEventQueue();
    final channel = channels.single;
    expect(channel.sent.first, {'type': 'auth', 'token': 'token-1'});
    channel.push({'type': 'ready', 'subject': 'alice'});
    await pumpEventQueue();
    expect(chat.link, ChatLink.live);

    final received = <DirectMessage>[];
    chat.messages.listen(received.add);
    final sending = chat.send('bob', 'Salut', 'client-1');
    await pumpEventQueue();
    expect(channel.sent.last, {
      'type': 'send',
      'to': 'bob',
      'clientMessageId': 'client-1',
      'body': 'Salut',
    });
    channel.push({
      'type': 'accepted',
      'clientMessageId': 'client-1',
      'created': true,
      'message': message(1, from: 'alice', id: 'client-'),
    });
    expect((await sending).seq, 1);
    channel.push({'type': 'message', 'message': message(2)});
    await pumpEventQueue();
    expect(received.single.body, 'Message 2');
    chat.markDelivered('bob', 2);
    expect(channel.sent.last, {'type': 'delivered', 'peer': 'bob', 'seq': 2});
    expect(restSends, isEmpty);
    chat.dispose();
  });

  test('resends the auth frame when ready does not arrive', () async {
    final chat = connection(ready: const Duration(milliseconds: 20))..start();
    await pumpEventQueue();
    final channel = channels.single;
    await Future<void>.delayed(const Duration(milliseconds: 60));
    final auths = channel.sent.where((f) => f['type'] == 'auth').length;
    expect(auths, greaterThanOrEqualTo(2));
    channel.push({'type': 'ready', 'subject': 'alice'});
    await pumpEventQueue();
    expect(chat.link, ChatLink.live);
    await Future<void>.delayed(const Duration(milliseconds: 60));
    expect(
      channel.sent.where((f) => f['type'] == 'auth').length,
      lessThanOrEqualTo(auths + 1),
    );
    chat.dispose();
  });

  test('a refused message surfaces the server status', () async {
    final chat = connection()..start();
    await pumpEventQueue();
    channels.single.push({'type': 'ready', 'subject': 'alice'});
    await pumpEventQueue();
    final sending = chat.send('bob', 'Salut', 'client-1');
    await pumpEventQueue();
    channels.single.push({
      'type': 'error',
      'clientMessageId': 'client-1',
      'status': 403,
      'error': 'Un blocage empêche ce message.',
    });
    await expectLater(
      sending,
      throwsA(isA<ApiFailure>().having((f) => f.status, 'status', 403)),
    );
    expect(restSends, isEmpty);
    chat.dispose();
  });

  test('without an acknowledgement the same id is sent through REST', () async {
    final chat = connection(accept: const Duration(milliseconds: 20))..start();
    await pumpEventQueue();
    channels.single.push({'type': 'ready', 'subject': 'alice'});
    await pumpEventQueue();
    final sent = await chat.send('bob', 'Salut', 'client-9');
    expect(restSends, ['client-9']);
    expect(sent.seq, 7);
    chat.dispose();
  });

  test('reconnects, authenticates again and asks for a catch-up', () async {
    final chat = connection()..start();
    var catchUps = 0;
    chat.reconnected.listen((_) => catchUps++);
    await pumpEventQueue();
    channels.single.push({'type': 'ready', 'subject': 'alice'});
    await pumpEventQueue();
    expect(catchUps, 0);

    channels.single.drop(4401);
    await pumpEventQueue();
    expect(channels, hasLength(2));
    expect(channels.last.sent.first, {'type': 'auth', 'token': 'token-2'});
    // Pendant la coupure, l'envoi passe par REST.
    expect(chat.link, ChatLink.connecting);
    await chat.send('bob', 'Hors ligne', 'client-2');
    expect(restSends, ['client-2']);
    channels.last.push({'type': 'ready', 'subject': 'alice'});
    await pumpEventQueue();
    expect(catchUps, 1);
    expect(chat.link, ChatLink.live);

    // 4403 : mauvais rôle ou autre compte, aucune nouvelle tentative.
    channels.last.drop(4403);
    await pumpEventQueue();
    expect(channels, hasLength(2));
    expect(chat.link, ChatLink.offline);
    chat.dispose();
  });
}
