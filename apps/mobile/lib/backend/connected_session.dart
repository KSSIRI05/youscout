import 'dart:async';
import 'package:flutter/foundation.dart';
import 'api.dart';
import 'auth.dart';

/// État du compte réel, indépendant du store de démonstration.
class ConnectedSession extends ChangeNotifier {
  ConnectedSession({required this.auth, required this.api});
  final AuthDriver auth;
  final BackendApi api;
  SessionTokens? _tokens;
  Future<SessionTokens>? _refreshing;
  int _generation = 0;
  bool _disposed = false;
  bool busy = false;
  bool get signedIn => _tokens != null;
  String? error;
  AccountProfile? profile;
  List<CatalogueItem> skills = [], hashtags = [];
  final Set<String> following = {};
  String skillQuery = '', hashtagQuery = '';
  bool moreSkills = false, moreHashtags = false;
  static const pageSize = 20;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  String _message(Object e) => switch (e) {
    ApiFailure() => e.message,
    AuthFailure() => e.message,
    _ => 'Connexion impossible. Vérifie le réseau et réessaie.',
  };

  Future<void> _run(Future<void> Function() action) async {
    if (busy || _disposed) return;
    busy = true;
    error = null;
    _notify();
    try {
      await action();
    } catch (e) {
      if (!_disposed) error = _message(e);
    } finally {
      busy = false;
      _notify();
    }
  }

  Future<void> initialize() => _run(() async {
    api.config.validate();
    final tokens = await auth.resume();
    if (tokens != null) {
      _tokens = tokens;
      await _load();
    }
  });

  Future<void> login({bool register = false}) => _run(() async {
    final tokens = await auth.login(register: register);
    if (tokens != null) {
      _tokens = tokens;
      await _load();
    }
  });

  Future<SessionTokens> _refresh() async {
    final current = _tokens;
    if (current == null) {
      throw const AuthFailure('Reconnecte-toi pour continuer.', expired: true);
    }
    final generation = _generation;
    // Un seul renouvellement évite de réutiliser simultanément un refresh token à usage unique.
    final pending = _refreshing ??= auth.refresh(current);
    try {
      final renewed = await pending;
      if (generation != _generation || _disposed) {
        throw const AuthFailure('Session fermée.', expired: true);
      }
      _tokens = renewed;
      return renewed;
    } on AuthFailure catch (e) {
      if (e.expired && generation == _generation) _clear();
      rethrow;
    } finally {
      if (identical(_refreshing, pending)) _refreshing = null;
    }
  }

  Future<Object?> _request(
    String method,
    String path, {
    Map<String, Object>? body,
    Map<String, String>? query,
  }) async {
    var tokens = _tokens;
    if (tokens == null) {
      throw const AuthFailure('Reconnecte-toi pour continuer.', expired: true);
    }
    final generation = _generation;
    if (!tokens.expiresAt.isAfter(
      DateTime.now().add(const Duration(seconds: 30)),
    )) {
      tokens = await _refresh();
    }
    Object? result;
    try {
      result = await api.send(
        method,
        path,
        tokens.access,
        body: body,
        query: query,
      );
    } on ApiFailure catch (e) {
      if (e.status != 401) rethrow;
      tokens = await _refresh();
      try {
        result = await api.send(
          method,
          path,
          tokens.access,
          body: body,
          query: query,
        );
      } on ApiFailure catch (retryError) {
        if (retryError.status == 401 && generation == _generation) _clear();
        rethrow;
      }
    }
    // Une réponse de l'ancien compte ne peut pas réapparaître après sa déconnexion.
    if (_disposed || generation != _generation) {
      throw const AuthFailure('Session fermée.');
    }
    return result;
  }

  Future<Map<String, dynamic>> _object(
    String method,
    String path, {
    Map<String, Object>? body,
    Map<String, String>? query,
  }) async =>
      await _request(method, path, body: body, query: query)
          as Map<String, dynamic>;

  Future<List<CatalogueItem>> _page(
    String path, {
    String? q,
    int offset = 0,
  }) async {
    final data = await _object(
      'GET',
      path,
      query: {'limit': '$pageSize', 'offset': '$offset', 'q': ?q},
    );
    return (data['items'] as List)
        .map((item) => CatalogueItem.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<void> _load() async {
    profile = AccountProfile.fromJson(await _object('GET', '/v1/users/me'));
    // Recharge les abonnements par pages, au lieu de supposer qu'une page contient tout.
    final ids = <String>{};
    for (var offset = 0; ; offset += pageSize) {
      if (offset > 10000) {
        throw const ApiFailure(
          400,
          'La liste des abonnements dépasse la limite de consultation.',
        );
      }
      final page = await _page('/v1/hashtags/following', offset: offset);
      ids.addAll(page.map((item) => item.id));
      if (page.length < pageSize) break;
    }
    following
      ..clear()
      ..addAll(ids);
    skills = await _page('/v1/skills', q: skillQuery);
    moreSkills = skills.length == pageSize;
    hashtags = await _page('/v1/hashtags', q: hashtagQuery);
    moreHashtags = hashtags.length == pageSize;
  }

  Future<void> reload() => _run(_load);

  Future<void> saveProfile(String name, String biography) => _run(() async {
    if (name.trim().isEmpty ||
        name.trim().runes.length > 80 ||
        biography.runes.length > 500) {
      throw const ApiFailure(
        400,
        'Nom : 1 à 80 caractères ; biographie : 500 maximum.',
      );
    }
    profile = AccountProfile.fromJson(
      await _object(
        'PATCH',
        '/v1/users/me',
        body: {'displayName': name.trim(), 'biography': biography},
      ),
    );
  });

  Future<void> search({
    required bool tags,
    required String query,
    bool next = false,
  }) => _run(() async {
    final q = next ? (tags ? hashtagQuery : skillQuery) : query;
    if (q.runes.length > 80) {
      throw const ApiFailure(400, 'Recherche : 80 caractères maximum.');
    }
    final current = tags ? hashtags : skills;
    final offset = next ? current.length : 0;
    final page = await _page(
      tags ? '/v1/hashtags' : '/v1/skills',
      q: q,
      offset: offset,
    );
    if (tags) {
      hashtagQuery = q;
      hashtags = next ? [...current, ...page] : page;
      moreHashtags = page.length == pageSize && offset + pageSize <= 10000;
    } else {
      skillQuery = q;
      skills = next ? [...current, ...page] : page;
      moreSkills = page.length == pageSize && offset + pageSize <= 10000;
    }
  });

  Future<void> toggleHashtag(CatalogueItem tag) => _run(() async {
    final remove = following.contains(tag.id);
    await _request(
      remove ? 'DELETE' : 'PUT',
      '/v1/hashtags/${Uri.encodeComponent(tag.id)}/follow',
    );
    remove ? following.remove(tag.id) : following.add(tag.id);
  });

  Future<void> createAndFollow(String name) => _run(() async {
    if (name.trim().isEmpty) throw const ApiFailure(400, 'Indique un hashtag.');
    final tag = CatalogueItem.fromJson(
      await _object('POST', '/v1/hashtags', body: {'name': name.trim()}),
    );
    await _request('PUT', '/v1/hashtags/${Uri.encodeComponent(tag.id)}/follow');
    following.add(tag.id);
    hashtagQuery = '';
    hashtags = await _page('/v1/hashtags', q: '');
    moreHashtags = hashtags.length == pageSize;
  });

  void _clear() {
    _generation++;
    _tokens = null;
    profile = null;
    following.clear();
    skills = [];
    hashtags = [];
    skillQuery = '';
    hashtagQuery = '';
    moreSkills = false;
    moreHashtags = false;
    _notify();
  }

  Future<void> logout() => _run(() async {
    final tokens = _tokens;
    _clear();
    if (tokens != null) {
      try {
        await auth.logout(tokens);
      } catch (_) {
        throw const AuthFailure(
          'Session locale fermée. La déconnexion du serveur n’a pas pu être confirmée.',
        );
      }
    }
  });

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    _tokens = null;
    auth.dispose();
    api.dispose();
    super.dispose();
  }
}
