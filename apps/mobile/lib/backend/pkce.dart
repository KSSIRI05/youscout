import 'dart:convert';
import 'dart:math';
import 'package:crypto/crypto.dart';
import 'auth.dart';

/// Données temporaires d'une seule redirection, sans jeton d'accès ni de renouvellement.
class PkceAttempt {
  const PkceAttempt(this.verifier, this.state, this.created);
  factory PkceAttempt.create() {
    final random = Random.secure();
    String value() => base64Url
        .encode(List.generate(32, (_) => random.nextInt(256)))
        .replaceAll('=', '');
    return PkceAttempt(value(), value(), DateTime.now().millisecondsSinceEpoch);
  }
  factory PkceAttempt.fromJson(Map<String, dynamic> data) => PkceAttempt(
    data['verifier'] as String,
    data['state'] as String,
    data['created'] as int,
  );
  final String verifier, state;
  final int created;
  String get challenge => base64Url
      .encode(sha256.convert(ascii.encode(verifier)).bytes)
      .replaceAll('=', '');
  Map<String, Object> toJson() => {
    'verifier': verifier,
    'state': state,
    'created': created,
  };

  void validate(String? returnedState, {DateTime? now}) {
    final age = (now ?? DateTime.now()).millisecondsSinceEpoch - created;
    if (returnedState != state || age < 0 || age > 600000) {
      throw const AuthFailure(
        'La demande de connexion est invalide ou expirée.',
      );
    }
  }
}
