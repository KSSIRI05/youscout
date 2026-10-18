/// Adresses publiques : aucun secret client n'est embarqué dans Flutter.
class BackendConfig {
  const BackendConfig({
    this.api = const String.fromEnvironment(
      'API_URL',
      defaultValue: 'http://localhost:8080',
    ),
    this.issuer = const String.fromEnvironment(
      'OIDC_ISSUER',
      defaultValue: 'http://localhost:8180/realms/youscout',
    ),
    this.allowInsecureOidc = const bool.fromEnvironment('ALLOW_INSECURE_OIDC'),
  });
  final String api, issuer;
  final bool allowInsecureOidc;
  static const clientId = 'youscout-mobile';
  static const nativeRedirect = 'ma.youscout://oauth/callback';
  Uri endpoint(String name) =>
      Uri.parse('$issuer/protocol/openid-connect/$name');

  void validate() {
    for (final value in [api, issuer]) {
      final uri = Uri.parse(value);
      final local = ['localhost', '127.0.0.1', '::1'].contains(uri.host);
      if (uri.userInfo.isNotEmpty ||
          uri.hasQuery ||
          uri.hasFragment ||
          !(uri.scheme == 'https' || (uri.scheme == 'http' && local))) {
        throw const FormatException(
          'Utiliser HTTPS ou une adresse localhost de développement.',
        );
      }
    }
  }
}
