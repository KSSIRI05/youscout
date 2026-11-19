import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:youscout_mobile/backend/api.dart';
import 'package:youscout_mobile/backend/config.dart';
import 'package:youscout_mobile/backend/connected_session.dart';
import 'package:youscout_mobile/core/theme.dart';
import 'package:youscout_mobile/screens/connected_feed_screen.dart';
import 'package:youscout_mobile/widgets/terrain.dart';
import 'connected_messages_test.dart' show SignedIn, json;

void main() {
  test('scores and dates read the French way', () {
    expect(frScore(4.25), '4,3');
    expect(frScore(3), '3,0');
    final now = DateTime(2026, 11, 19, 12);
    expect(
      frWhen(now.subtract(const Duration(minutes: 5)), now: now),
      'Il y a 5 min',
    );
    expect(
      frWhen(now.subtract(const Duration(hours: 3)), now: now),
      'Il y a 3 h',
    );
    expect(frWhen(DateTime(2026, 10, 2), now: now), '02/10/2026');
    expect(initialsOf('Inès Laurent'), 'IL');
    expect(initialsOf('  '), '?');
  });

  testWidgets('a cone is one point and tapping one rates the skill', (
    tester,
  ) async {
    int? rated;
    await tester.pumpWidget(
      MaterialApp(
        theme: scoutTheme(),
        home: Scaffold(
          body: FicheSkill(
            skill: 'Dribble',
            average: 4.2,
            count: 128,
            myScore: 2,
            onRate: (score) => rated = score,
            onClear: () {},
          ),
        ),
      ),
    );
    expect(find.text('4,2'), findsOneWidget);
    expect(
      find.text('Ta note : 2. Moyenne 4,2 sur 5, 128 notes.'),
      findsOneWidget,
    );
    expect(find.text('Retirer ma note'), findsOneWidget);
    await tester.tap(find.bySemanticsLabel('Dribble : 4 sur 5'));
    expect(rated, 4);
    expect(tester.takeException(), isNull);
  });

  testWidgets('the feed names the player and records a rating', (tester) async {
    final requests = <String>[];
    final session = ConnectedSession(
      auth: SignedIn(),
      api: BackendApi(
        const BackendConfig(),
        client: MockClient((request) async {
          requests.add('${request.method} ${request.url.path}');
          return switch (request.url.path) {
            '/v1/users/me' => json({
              'subject': 'alice',
              'displayName': 'Alice',
              'biography': '',
            }),
            '/v1/feed' => json({
              'hasMore': false,
              'items': [
                {
                  'videoId': 'v1',
                  'title': 'Crochet et frappe',
                  'publishedAt': '2026-11-19T10:00:00Z',
                  'ownerSubject': 'bob',
                  'skills': [
                    {'id': 's1', 'name': 'Dribble'},
                  ],
                  'hashtags': [],
                },
              ],
            }),
            '/v1/users/bob' => json({
              'subject': 'bob',
              'displayName': 'Bob Martin',
              'biography': '',
              'followingCount': 0,
              'followerCount': 0,
              'following': false,
            }),
            '/v1/ratings/videos/v1' ||
            '/v1/ratings/videos/v1/skills/s1' => json({
              'skills': [
                {
                  'skillId': 's1',
                  'count': request.method == 'PUT' ? 3 : 2,
                  'average': request.method == 'PUT' ? 4.0 : 3.5,
                  'myScore': request.method == 'PUT'
                      ? (jsonDecode(request.body) as Map)['score']
                      : null,
                },
              ],
            }),
            _ => json({'items': []}),
          };
        }),
      ),
    );
    addTearDown(session.dispose);
    await tester.runAsync(session.login);
    await tester.pumpWidget(
      MaterialApp(
        theme: scoutTheme(),
        home: Scaffold(
          body: ConnectedFeedScreen(
            session: session,
            active: true,
            onMessage: (_) {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Bob Martin'), findsOneWidget);
    expect(find.text('Crochet et frappe'), findsOneWidget);

    await tester.tap(find.text('Noter'));
    await tester.pumpAndSettle();
    expect(find.text('Fiche du clip'), findsOneWidget);
    expect(find.text('3,5'), findsOneWidget);
    await tester.tap(find.bySemanticsLabel('Dribble : 5 sur 5'));
    await tester.pumpAndSettle();
    expect(requests, contains('PUT /v1/ratings/videos/v1/skills/s1'));
    expect(
      find.text('Ta note : 5. Moyenne 4,0 sur 5, 3 notes.'),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
  });
}
