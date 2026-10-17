import 'package:flutter/foundation.dart';
import 'package:flutter_appauth/flutter_appauth.dart';
import 'auth.dart';
import 'config.dart';

AuthDriver createAuthDriver(BackendConfig config) => NativeAuthDriver(config);

/// AppAuth utilise le navigateur système et prend en charge state et PKCE.
class NativeAuthDriver implements AuthDriver {
  NativeAuthDriver(this.config);
  final BackendConfig config;
  final FlutterAppAuth _auth = const FlutterAppAuth();
  bool get _insecure => kDebugMode && config.allowInsecureOidc;
  @override
  Future<SessionTokens?> resume() async => null;

  @override
  Future<SessionTokens?> login({bool register = false}) async {
    config.validate();
    if (![
      TargetPlatform.android,
      TargetPlatform.iOS,
    ].contains(defaultTargetPlatform)) {
      throw const AuthFailure(
        'Utilise la version web, Android ou iOS pour te connecter.',
      );
    }
    final result = await _auth.authorizeAndExchangeCode(
      AuthorizationTokenRequest(
        BackendConfig.clientId,
        BackendConfig.nativeRedirect,
        issuer: config.issuer,
        scopes: ['openid', 'profile', 'email'],
        allowInsecureConnections: _insecure,
        serviceConfiguration: register
            ? AuthorizationServiceConfiguration(
                authorizationEndpoint: config
                    .endpoint('registrations')
                    .toString(),
                tokenEndpoint: config.endpoint('token').toString(),
                endSessionEndpoint: config.endpoint('logout').toString(),
              )
            : null,
      ),
    );
    return _tokens(result);
  }

  SessionTokens _tokens(TokenResponse result, {SessionTokens? previous}) {
    final access = result.accessToken,
        refresh = result.refreshToken ?? previous?.refresh;
    final expiry = result.accessTokenExpirationDateTime;
    if (access == null || refresh == null || expiry == null) {
      throw const AuthFailure('Réponse de connexion incomplète.');
    }
    return SessionTokens(
      access,
      refresh,
      expiry,
      idToken: result.idToken ?? previous?.idToken,
    );
  }

  @override
  Future<SessionTokens> refresh(SessionTokens tokens) async {
    try {
      return _tokens(
        await _auth.token(
          TokenRequest(
            BackendConfig.clientId,
            BackendConfig.nativeRedirect,
            issuer: config.issuer,
            refreshToken: tokens.refresh,
            allowInsecureConnections: _insecure,
          ),
        ),
        previous: tokens,
      );
    } on FlutterAppAuthPlatformException catch (e) {
      if (e.platformErrorDetails.error ==
          FlutterAppAuthOAuthError.invalidGrant) {
        throw const AuthFailure(
          'La session a expiré. Reconnecte-toi.',
          expired: true,
        );
      }
      throw const AuthFailure(
        'Renouvellement impossible. Vérifie le réseau et réessaie.',
      );
    }
  }

  @override
  Future<void> logout(SessionTokens tokens) async {
    await _auth.endSession(
      EndSessionRequest(
        idTokenHint: tokens.idToken,
        postLogoutRedirectUrl: BackendConfig.nativeRedirect,
        issuer: config.issuer,
        allowInsecureConnections: _insecure,
      ),
    );
  }

  @override
  void dispose() {}
}
