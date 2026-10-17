import 'dart:convert';
import 'package:http/http.dart' as http;
import 'config.dart';

class ApiFailure implements Exception {
  const ApiFailure(this.status, this.message);
  final int status;
  final String message;
  @override
  String toString() => message;
}

/// Le jeton est obtenu juste avant chaque appel ; les réponses n'affichent jamais son contenu.
class BackendApi {
  BackendApi(this.config, {http.Client? client})
    : _client = client ?? http.Client();
  final BackendConfig config;
  final http.Client _client;

  Future<Object?> send(
    String method,
    String path,
    String token, {
    Map<String, Object>? body,
    Map<String, String>? query,
  }) async {
    final uri = Uri.parse('${config.api}$path').replace(queryParameters: query);
    final request = http.Request(method, uri)
      ..headers['Authorization'] = 'Bearer $token';
    request.headers['Accept'] = 'application/json, application/problem+json';
    if (body != null) {
      request.headers['Content-Type'] = 'application/json';
      request.body = jsonEncode(body);
    }
    final response = await (() async {
      return http.Response.fromStream(await _client.send(request));
    })().timeout(const Duration(seconds: 15));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final message = switch (response.statusCode) {
        400 => 'Vérifie les champs et leurs limites.',
        401 => 'La session a expiré. Reconnecte-toi.',
        403 => 'Ton compte ne dispose pas des droits nécessaires.',
        404 => 'Cette ressource n’est plus disponible.',
        409 => 'Cette valeur existe déjà.',
        _ => 'Le serveur est indisponible. Réessaie dans un instant.',
      };
      throw ApiFailure(response.statusCode, message);
    }
    return response.body.isEmpty
        ? null
        : jsonDecode(utf8.decode(response.bodyBytes));
  }

  void dispose() => _client.close();
}

class AccountProfile {
  const AccountProfile(this.subject, this.name, this.biography);
  factory AccountProfile.fromJson(Map<String, dynamic> data) => AccountProfile(
    data['subject'] as String,
    data['displayName'] as String,
    data['biography'] as String,
  );
  final String subject, name, biography;
}

class CatalogueItem {
  const CatalogueItem(this.id, this.name, {this.description = ''});
  factory CatalogueItem.fromJson(Map<String, dynamic> data) => CatalogueItem(
    data['id'] as String,
    data['name'] as String,
    description: data['description'] as String? ?? '',
  );
  final String id, name, description;
}
