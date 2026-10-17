import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:web/web.dart' as web;
import 'auth.dart';
import 'config.dart';
import 'pkce.dart';

AuthDriver createAuthDriver(BackendConfig config) => WebAuthDriver(config);

class WebAuthDriver implements AuthDriver {
  WebAuthDriver(this.config, {http.Client? client})
    : _client = client ?? http.Client();
  final BackendConfig config;
  final http.Client _client;
  static const _pendingKey = 'youscout.pkce.pending';
  String get _redirect => '${web.window.location.origin}/auth/callback';

  @override
  Future<SessionTokens?> login({bool register = false}) async {
    config.validate();
    final attempt = PkceAttempt.create();
    web.window.sessionStorage.setItem(
      _pendingKey,
      jsonEncode(attempt.toJson()),
    );
    final url = config
        .endpoint(register ? 'registrations' : 'auth')
        .replace(
          queryParameters: {
            'client_id': BackendConfig.clientId,
            'redirect_uri': _redirect,
            'response_type': 'code',
            'scope': 'openid profile email',
            'state': attempt.state,
            'code_challenge': attempt.challenge,
            'code_challenge_method': 'S256',
          },
        );
    // Aucun jeton ni mot de passe n'est placé dans le stockage du navigateur.
    web.window.location.assign(url.toString());
    return null;
  }

  @override
  Future<SessionTokens?> resume() async {
    final uri = Uri.parse(web.window.location.href);
    if (uri.path != '/auth/callback') return null;
    final pending = web.window.sessionStorage.getItem(_pendingKey);
    web.window.sessionStorage.removeItem(_pendingKey);
    // Retire immédiatement le code de l'historique avant tout appel réseau.
    web.window.history.replaceState(null, '', '/');
    if (pending == null) {
      throw const AuthFailure(
        'Connexion introuvable ou déjà utilisée. Recommence la connexion.',
      );
    }
    final attempt = PkceAttempt.fromJson(
      jsonDecode(pending) as Map<String, dynamic>,
    );
    attempt.validate(uri.queryParameters['state']);
    if (uri.queryParameters.containsKey('error')) {
      throw const AuthFailure('Connexion annulée ou refusée.');
    }
    final code = uri.queryParameters['code'];
    if (code == null || code.isEmpty) {
      throw const AuthFailure('Code de connexion absent.');
    }
    return _token({
      'grant_type': 'authorization_code',
      'code': code,
      'code_verifier': attempt.verifier,
      'redirect_uri': _redirect,
    });
  }

  Future<SessionTokens> _token(
    Map<String, String> body, {
    SessionTokens? previous,
  }) async {
    final response = await _client
        .post(
          config.endpoint('token'),
          body: {'client_id': BackendConfig.clientId, ...body},
        )
        .timeout(const Duration(seconds: 15));
    if (response.statusCode != 200) {
      // Un serveur momentanément indisponible ne prouve pas que le refresh token est expiré.
      if (response.statusCode >= 500) {
        throw const AuthFailure(
          'Connexion indisponible. Réessaie dans un instant.',
        );
      }
      Object? problem;
      try {
        problem = jsonDecode(response.body);
      } on FormatException {
        /* Réponse non JSON : erreur générique. */
      }
      if (previous != null &&
          problem is Map &&
          problem['error'] == 'invalid_grant') {
        throw const AuthFailure(
          'La session a expiré. Reconnecte-toi.',
          expired: true,
        );
      }
      throw const AuthFailure('Connexion refusée. Réessaie ou reconnecte-toi.');
    }
    return SessionTokens.fromJson(
      jsonDecode(response.body) as Map<String, dynamic>,
      previousRefresh: previous?.refresh,
      previousIdToken: previous?.idToken,
    );
  }

  @override
  Future<SessionTokens> refresh(SessionTokens tokens) => _token({
    'grant_type': 'refresh_token',
    'refresh_token': tokens.refresh,
  }, previous: tokens);

  @override
  Future<void> logout(SessionTokens tokens) async {
    final response = await _client
        .post(
          config.endpoint('logout'),
          body: {
            'client_id': BackendConfig.clientId,
            'refresh_token': tokens.refresh,
          },
        )
        .timeout(const Duration(seconds: 15));
    if (response.statusCode != 204 && response.statusCode != 400) {
      throw const AuthFailure(
        'Session locale fermée. La déconnexion du serveur n’a pas pu être confirmée.',
      );
    }
    final hint = tokens.idToken;
    if (hint != null) {
      web.window.location.assign(
        config
            .endpoint('logout')
            .replace(
              queryParameters: {
                'id_token_hint': hint,
                'post_logout_redirect_uri': '${web.window.location.origin}/',
              },
            )
            .toString(),
      );
    }
  }

  @override
  void dispose() => _client.close();
}
