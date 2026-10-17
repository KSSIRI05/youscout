import 'package:flutter_test/flutter_test.dart';
import 'package:youscout_mobile/backend/auth.dart';
import 'package:youscout_mobile/backend/pkce.dart';

void main() {
  test('S256 matches the RFC 7636 test vector', () {
    const attempt = PkceAttempt(
      'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk',
      'state',
      0,
    );
    expect(attempt.challenge, 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });
  test('each attempt has a fresh URL-safe verifier and independent state', () {
    final a = PkceAttempt.create(), b = PkceAttempt.create();
    expect(a.verifier, matches(RegExp(r'^[A-Za-z0-9_-]{43}$')));
    expect(a.state, isNot(a.verifier));
    expect(a.state, isNot(b.state));
    expect(PkceAttempt.fromJson(a.toJson()).challenge, a.challenge);
  });
  test('callback rejects wrong, absent, old and future state', () {
    const attempt = PkceAttempt('verifier', 'expected', 1000);
    expect(
      () => attempt.validate(
        'wrong',
        now: DateTime.fromMillisecondsSinceEpoch(2000),
      ),
      throwsA(isA<AuthFailure>()),
    );
    expect(
      () => attempt.validate(
        null,
        now: DateTime.fromMillisecondsSinceEpoch(2000),
      ),
      throwsA(isA<AuthFailure>()),
    );
    expect(
      () => attempt.validate(
        'expected',
        now: DateTime.fromMillisecondsSinceEpoch(601001),
      ),
      throwsA(isA<AuthFailure>()),
    );
    expect(
      () => attempt.validate(
        'expected',
        now: DateTime.fromMillisecondsSinceEpoch(0),
      ),
      throwsA(isA<AuthFailure>()),
    );
    expect(
      () => attempt.validate(
        'expected',
        now: DateTime.fromMillisecondsSinceEpoch(2000),
      ),
      returnsNormally,
    );
  });
}
