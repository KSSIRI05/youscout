import 'package:flutter/material.dart';
import '../backend/api.dart';
import '../backend/connected_session.dart';
import '../core/theme.dart';

/// Texte de chaque notification ; le nom de l'auteur est lu auprès de User.
String notificationText(String type, String actor, int occurrences) =>
    switch (type) {
      'FOLLOWED' => '$actor te suit désormais.',
      'VIDEO_PUBLISHED' => '$actor a publié une nouvelle vidéo.',
      'VIDEO_LIKED' => '$actor a aimé ta vidéo.',
      'COMMENTED' => '$actor a commenté ta vidéo.',
      'REPLIED' => '$actor a répondu à ton commentaire.',
      'MESSAGE' =>
        occurrences > 1
            ? '$actor t’a envoyé $occurrences messages.'
            : '$actor t’a envoyé un message.',
      _ => 'Nouvelle activité de $actor.',
    };

/// Boîte de notifications. Ouvrir une notification de message renvoie
/// l'expéditeur à l'appelant, qui ouvre la conversation.
class ConnectedNotificationsScreen extends StatefulWidget {
  const ConnectedNotificationsScreen({required this.session, super.key});
  final ConnectedSession session;
  @override
  State<ConnectedNotificationsScreen> createState() =>
      _ConnectedNotificationsScreenState();
}

class _ConnectedNotificationsScreenState
    extends State<ConnectedNotificationsScreen> {
  final items = <Map<String, dynamic>>[];
  final names = <String, String>{};
  bool loading = true, more = false;
  String? error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool next = false}) async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final page = await widget.session.notifications(
        before: next && items.isNotEmpty ? items.last['id'] as String : null,
      );
      for (final actor in page.map((n) => n['actorSubject'] as String)) {
        if (names.containsKey(actor)) continue;
        try {
          names[actor] =
              (await widget.session.publicProfile(actor))['displayName']
                  as String;
        } on ApiFailure {
          // Compte fermé, désactivé ou bloqué : la notification reste lisible.
          names[actor] = 'Un joueur';
        }
      }
      if (!mounted) return;
      setState(() {
        if (!next) items.clear();
        items.addAll(page);
        more = page.length == 20;
      });
    } catch (e) {
      if (mounted) {
        setState(
          () => error = e is ApiFailure
              ? e.message
              : 'Notifications indisponibles. Réessaie plus tard.',
        );
      }
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _open(Map<String, dynamic> item) async {
    if (item['read'] != true) {
      try {
        await widget.session.markNotificationRead(item['id'] as String);
        setState(() => item['read'] = true);
      } catch (_) {
        // L'état « lu » sera repris au prochain chargement.
      }
    }
    if (item['type'] == 'MESSAGE' && mounted) {
      Navigator.pop(context, item['actorSubject'] as String);
    }
  }

  Future<void> _readAll() async {
    try {
      await widget.session.markAllNotificationsRead();
      setState(() {
        for (final item in items) {
          item['read'] = true;
        }
      });
    } catch (_) {
      setState(() => error = 'Impossible de tout marquer comme lu.');
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Notifications'),
      actions: [
        TextButton(
          key: const Key('notifications-read-all'),
          onPressed: items.any((n) => n['read'] != true) ? _readAll : null,
          child: const Text('Tout marquer comme lu'),
        ),
      ],
    ),
    body: RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        children: [
          if (loading) const LinearProgressIndicator(),
          if (error != null)
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                error!,
                style: const TextStyle(color: Colors.orangeAccent),
              ),
            ),
          if (!loading && items.isEmpty && error == null)
            const Padding(
              padding: EdgeInsets.all(32),
              child: Center(child: Text('Aucune notification pour l’instant.')),
            ),
          for (final item in items)
            ListTile(
              key: ValueKey(item['id']),
              leading: Icon(
                item['read'] == true
                    ? Icons.notifications_none
                    : Icons.notifications_active,
                color: item['read'] == true
                    ? ScoutColors.muted
                    : ScoutColors.brand,
              ),
              title: Text(
                notificationText(
                  item['type'] as String,
                  names[item['actorSubject']] ?? 'Un joueur',
                  (item['occurrences'] as num).toInt(),
                ),
                style: TextStyle(
                  fontWeight: item['read'] == true
                      ? FontWeight.normal
                      : FontWeight.bold,
                ),
              ),
              subtitle: Text(
                MaterialLocalizations.of(context).formatShortDate(
                  DateTime.parse(item['updatedAt'] as String).toLocal(),
                ),
              ),
              onTap: () => _open(item),
            ),
          if (more && !loading)
            Center(
              child: TextButton(
                onPressed: () => _load(next: true),
                child: const Text('Plus anciennes'),
              ),
            ),
        ],
      ),
    ),
  );
}
