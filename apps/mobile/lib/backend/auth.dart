class SessionTokens {
  const SessionTokens(
    this.access,
    this.refresh,
    this.expiresAt, {
    this.idToken,
  });
  final String access, refresh;
  final DateTime expiresAt;
  final String? idToken;

  factory SessionTokens.fromJson(
    Map<String, dynamic> json, {
    String? previousRefresh,
    String? previousIdToken,
  }) {
    final access = json['access_token'] as String?;
    final refresh = json['refresh_token'] as String? ?? previousRefresh;
    final seconds = json['expires_in'];
    if (access == null ||
        access.isEmpty ||
        refresh == null ||
        refresh.isEmpty ||
        seconds is! num ||
        seconds <= 0) {
      throw const FormatException('Réponse de connexion invalide.');
    }
    return SessionTokens(
      access,
      refresh,
      DateTime.now().add(Duration(seconds: seconds.toInt())),
      idToken: json['id_token'] as String? ?? previousIdToken,
    );
  }
}

/// Les jetons restent en mémoire ; seul l'état temporaire PKCE traverse la redirection web.
abstract class AuthDriver {
  Future<SessionTokens?> resume();
  Future<SessionTokens?> login({bool register = false});
  Future<SessionTokens> refresh(SessionTokens tokens);
  Future<void> logout(SessionTokens tokens);
  void dispose();
}

class AuthFailure implements Exception {
  const AuthFailure(this.message, {this.expired = false});
  final String message;
  final bool expired;
  @override
  String toString() => message;
}
