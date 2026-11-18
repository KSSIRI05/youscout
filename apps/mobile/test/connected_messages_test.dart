import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:youscout_mobile/backend/api.dart';
import 'package:youscout_mobile/backend/auth.dart';
import 'package:youscout_mobile/backend/config.dart';
import 'package:youscout_mobile/backend/connected_session.dart';
import 'package:youscout_mobile/screens/connected_messages_screen.dart';
import 'chat_connection_test.dart' show FakeChannel, message;

class SignedIn extends AuthDriver {
  @override
  Future<SessionTokens?> resume() async => null;
  @override
  Future<SessionTokens?> login({bool register = false}) async => SessionTokens(
    'access-chat',
    'refresh-chat',
    DateTime.now().add(const Duration(minutes: 5)),
  );
  @override
  Future<SessionTokens> refresh(SessionTokens tokens) async => tokens;
  @override
  Future<void> logout(SessionTokens tokens) async {}
  @override
  void dispose() {}
}

http.Response json(Object body) => http.Response(
  jsonEncode(body),
  200,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

void main() {
  testWidgets('shows live messages once and the delivery of my own', (
    tester,
  ) async {
    final session = ConnectedSession(
      auth: SignedIn(),
      api: BackendApi(
        const BackendConfig(),
        client: MockClient((request) async {
          return switch (request.url.path) {
            '/v1/users/me' => json({
              'subject': 'alice',
              'displayName': 'Alice',
              'biography': '',
            }),
            '/v1/conversations/bob/messages' => json({
              'conversationId': 'conv',
              'peerSubject': 'bob',
              'lastSeq': 1,
              'peerDeliveredSeq': 0,
              'messages': [message(1)],
            }),
            _ => json({'items': []}),
          };
        }),
      ),
    );
    await tester.runAsync(session.login);
    final channel = FakeChannel();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ConnectedMessagesScreen(
            session: session,
            initialRecipient: 'bob',
            connector: (_) async => channel,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    channel.push({'type': 'ready', 'subject': 'alice'});
    await tester.pumpAndSettle();
    expect(
      find.text('En direct · messages enregistrés sur YouScout'),
      findsOneWidget,
    );
    expect(find.text('Message 1'), findsOneWidget);

    expect(channel.sent.first['type'], 'auth');

    await tester.enterText(find.byType(TextField).last, 'Salut Bob');
    await tester.tap(find.byIcon(Icons.send));
    await tester.pump();
    expect(find.text('Envoi…'), findsOneWidget);
    final send = channel.sent.lastWhere((f) => f['type'] == 'send');
    channel.push({
      'type': 'accepted',
      'clientMessageId': send['clientMessageId'],
      'created': true,
      'message': {
        ...message(2, from: 'alice'),
        'clientMessageId': send['clientMessageId'],
        'body': 'Salut Bob',
      },
    });
    await tester.pumpAndSettle();
    expect(find.text('Envoi…'), findsNothing);
    expect(find.text('Envoyé'), findsOneWidget);

    channel.push({
      'type': 'delivered',
      'conversationId': 'conv',
      'peerSubject': 'bob',
      'seq': 2,
    });
    await tester.pumpAndSettle();
    expect(find.text('Reçu'), findsOneWidget);

    // Une trame reçue deux fois (renvoi après reprise) ne s'affiche qu'une fois.
    channel.push({'type': 'message', 'message': message(3)});
    channel.push({'type': 'message', 'message': message(3)});
    await tester.pumpAndSettle();
    expect(find.text('Message 3'), findsOneWidget);
    expect(channel.sent.where((f) => f['type'] == 'delivered').last, {
      'type': 'delivered',
      'peer': 'bob',
      'seq': 3,
    });

    await tester.pumpWidget(const SizedBox.shrink());
    session.dispose();
  });
}
