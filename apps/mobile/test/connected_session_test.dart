import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:youscout_mobile/backend/api.dart';
import 'package:youscout_mobile/backend/auth.dart';
import 'package:youscout_mobile/backend/config.dart';
import 'package:youscout_mobile/backend/connected_session.dart';

class FakeAuth extends AuthDriver {
  int renewals = 0, logouts = 0;
  bool expired = false, offline = false, logoutFails = false;
  SessionTokens tokens = SessionTokens(
    'access-first',
    'refresh-first',
    DateTime.now().add(const Duration(minutes: 5)),
  );
  @override
  Future<SessionTokens?> resume() async => null;
  @override
  Future<SessionTokens?> login({bool register = false}) async => tokens;
  @override
  Future<SessionTokens> refresh(SessionTokens tokens) async {
    renewals++;
    if (offline) throw Exception('network');
    if (expired) throw const AuthFailure('Session expirée.', expired: true);
    return SessionTokens(
      'access-renewed',
      'refresh-renewed',
      DateTime.now().add(const Duration(minutes: 5)),
    );
  }

  @override
  Future<void> logout(SessionTokens tokens) async {
    logouts++;
    if (logoutFails) throw Exception('network');
  }

  @override
  void dispose() {}
}

class ApiFixture {
  String name = 'Joueur réel', biography = 'Mon profil';
  final following = <String>{};
  final requests = <http.Request>[];
  int? reject;
  bool unauthorizedUntilRefresh = false;
  int skillCount = 1, followingCount = 0;
  http.Response respond(http.Request request) {
    requests.add(request);
    expect(request.headers['Authorization'], startsWith('Bearer access-'));
    if (reject != null) return http.Response('{}', reject!);
    if (unauthorizedUntilRefresh &&
        request.headers['Authorization'] == 'Bearer access-first') {
      return http.Response('{}', 401);
    }
    final path = request.url.path;
    Object? body;
    if (path == '/v1/users/me') {
      if (request.method == 'PATCH') {
        final data = jsonDecode(request.body) as Map;
        expect(data.keys, unorderedEquals(['displayName', 'biography']));
        name = data['displayName'] as String;
        biography = data['biography'] as String;
      }
      body = {
        'subject': 'verified-account',
        'displayName': name,
        'biography': biography,
      };
    } else if (path.endsWith('/follow')) {
      final id = request.url.pathSegments[2];
      request.method == 'DELETE' ? following.remove(id) : following.add(id);
      return http.Response('', 204);
    } else if (path == '/v1/hashtags' && request.method == 'POST') {
      body = {'id': 'tag-real', 'name': 'football'};
    } else if (path == '/v1/hashtags/following') {
      final offset = int.parse(request.url.queryParameters['offset']!);
      body = {
        'items': List.generate(
          followingCount,
          (i) => {'id': 'follow-$i', 'name': 'tag$i'},
        ).skip(offset).take(20).toList(),
      };
    } else if (path == '/v1/skills') {
      final offset = int.parse(request.url.queryParameters['offset']!);
      body = {
        'items': List.generate(
          skillCount,
          (i) => {'id': 'skill-$i', 'name': 'Dribble $i'},
        ).skip(offset).take(20).toList(),
      };
    } else {
      expect(path, '/v1/hashtags');
      body = {
        'items': [
          {'id': 'tag-real', 'name': 'football'},
        ],
      };
    }
    return http.Response(
      jsonEncode(body),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );
  }

  ConnectedSession session(FakeAuth auth) => ConnectedSession(
    auth: auth,
    api: BackendApi(
      const BackendConfig(),
      client: MockClient((request) async => respond(request)),
    ),
  );
}

void main() {
  late FakeAuth auth;
  late ApiFixture fixture;
  late ConnectedSession session;
  setUp(() {
    auth = FakeAuth();
    fixture = ApiFixture();
    session = fixture.session(auth);
  });
  tearDown(() => session.dispose());

  test('loads the real profile and preserves server-confirmed edits', () async {
    await session.login();
    expect(session.profile!.subject, 'verified-account');
    await session.saveProfile('Nouveau nom', '');
    expect(fixture.name, 'Nouveau nom');
    expect(session.profile!.biography, '');
    fixture.reject = 400;
    await session.saveProfile('Refusé', 'Texte');
    expect(session.profile!.name, 'Nouveau nom');
    expect(session.error, contains('champs'));
  });

  test('validates profile limits before any write', () async {
    await session.login();
    final calls = fixture.requests.length;
    await session.saveProfile(' ', '');
    expect(fixture.requests.length, calls);
    expect(session.error, contains('80'));
    await session.saveProfile('😀' * 80, '');
    expect(session.error, isNull);
  });

  test('refreshes before expiry and uses the rotated access token', () async {
    auth.tokens = SessionTokens(
      'access-first',
      'refresh-first',
      DateTime.now(),
    );
    await session.login();
    expect(auth.renewals, 1);
    expect(
      fixture.requests.every(
        (r) => r.headers['Authorization'] == 'Bearer access-renewed',
      ),
      isTrue,
    );
  });

  test('retries a 401 once after refresh', () async {
    fixture.unauthorizedUntilRefresh = true;
    await session.login();
    expect(auth.renewals, 1);
    expect(session.profile, isNotNull);
    expect(session.error, isNull);
  });

  test('clears private data after a repeated 401', () async {
    await session.login();
    fixture.reject = 401;
    final before = fixture.requests.length;
    await session.reload();
    expect(fixture.requests.length - before, 2);
    expect(auth.renewals, 1);
    expect(session.signedIn, isFalse);
    expect(session.profile, isNull);
    expect(session.skills, isEmpty);
  });

  test('expired refresh token closes the session', () async {
    await session.login();
    auth.expired = true;
    fixture.reject = 401;
    await session.reload();
    expect(session.signedIn, isFalse);
    expect(session.error, contains('expirée'));
  });

  test('403 is not retried and does not erase the profile', () async {
    await session.login();
    fixture.reject = 403;
    await session.reload();
    expect(auth.renewals, 0);
    expect(session.signedIn, isTrue);
    expect(session.profile, isNotNull);
    expect(session.error, contains('droits'));
  });

  test('loads every followed hashtag page and the next skill page', () async {
    fixture.followingCount = 23;
    fixture.skillCount = 21;
    await session.login();
    expect(session.following.length, 23);
    expect(session.skills.length, 20);
    await session.search(tags: false, query: '', next: true);
    expect(session.skills.length, 21);
    expect(session.moreSkills, isFalse);
    await session.search(tags: false, query: 'passe');
    expect(fixture.requests.last.url.queryParameters, {
      'q': 'passe',
      'limit': '20',
      'offset': '0',
    });
  });

  test(
    'follows by server ID only and leaves state unchanged on rejection',
    () async {
      await session.login();
      final tag = session.hashtags.single;
      await session.toggleHashtag(tag);
      expect(session.following, contains(tag.id));
      expect(fixture.requests.last.url.query, isEmpty);
      expect(fixture.requests.last.body, isEmpty);
      fixture.reject = 403;
      await session.toggleHashtag(tag);
      expect(session.following, contains(tag.id));
      fixture.reject = null;
      await session.toggleHashtag(tag);
      expect(session.following, isNot(contains(tag.id)));
    },
  );

  test(
    'canonical creation then follow uses the ID returned by the server',
    () async {
      await session.login();
      await session.createAndFollow('#FOOTBALL');
      expect(session.following, contains('tag-real'));
      expect(fixture.following, contains('tag-real'));
    },
  );

  test(
    'logout clears the local account even when the server is offline',
    () async {
      await session.login();
      auth.logoutFails = true;
      await session.logout();
      expect(session.signedIn, isFalse);
      expect(session.profile, isNull);
      expect(session.following, isEmpty);
      expect(session.error, contains('locale fermée'));
    },
  );

  test('network failure at refresh remains retryable', () async {
    await session.login();
    fixture.reject = 401;
    auth.offline = true;
    await session.reload();
    expect(session.signedIn, isTrue);
    auth.offline = false;
    fixture.reject = null;
    await session.reload();
    expect(session.error, isNull);
  });

  test('production endpoints must use HTTPS', () {
    expect(
      () => const BackendConfig(api: 'http://example.org').validate(),
      throwsFormatException,
    );
    expect(() => const BackendConfig().validate(), returnsNormally);
  });
}
