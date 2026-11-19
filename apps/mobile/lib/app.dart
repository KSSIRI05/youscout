import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'core/theme.dart';
import 'state/scout_store.dart';
import 'screens/social_screens.dart';
import 'screens/upload_screen.dart';
import 'screens/messages_screen.dart';
import 'widgets/scout_widgets.dart';
import 'widgets/terrain.dart';
import 'backend/api.dart';
import 'backend/auth_driver.dart';
import 'backend/config.dart';
import 'backend/connected_session.dart';
import 'screens/connected_screen.dart';

class YouScoutApp extends StatefulWidget {
  const YouScoutApp({this.store, this.session, super.key});
  final ScoutStore? store;
  final ConnectedSession? session;
  @override
  State<YouScoutApp> createState() => _YouScoutAppState();
}

class _YouScoutAppState extends State<YouScoutApp> {
  late final ScoutStore store = widget.store ?? ScoutStore();
  late final ConnectedSession? session =
      widget.session ??
      (widget.store == null
          ? ConnectedSession(
              auth: createAuthDriver(const BackendConfig()),
              api: BackendApi(const BackendConfig()),
            )
          : null);
  @override
  void initState() {
    super.initState();
    if (session != null) unawaited(session!.initialize());
  }

  @override
  void dispose() {
    if (widget.store == null) store.dispose();
    if (widget.session == null) session?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'YouScout',
    initialRoute: '/',
    debugShowCheckedModeBanner: false,
    theme: scoutTheme(),
    darkTheme: scoutTheme(Brightness.dark),
    locale: const Locale('fr'),
    supportedLocales: const [Locale('fr')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    // Sur un grand écran, la colonne de l'application est posée sur la pelouse.
    builder: (context, child) => PitchSurface(
      marks: PitchMarks.none,
      stripe: 64,
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: child!,
        ),
      ),
    ),
    home: ListenableBuilder(
      listenable: Listenable.merge([store, ?session]),
      builder: (context, _) => store.inDemo
          ? ScoutShell(store: store)
          : session?.signedIn == true
          ? ConnectedScreen(session: session!)
          : WelcomeScreen(store: store, session: session),
    ),
  );
}

class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({required this.store, this.session, super.key});
  final ScoutStore store;
  final ConnectedSession? session;
  @override
  Widget build(BuildContext context) {
    final p = context.scout;
    final busy = session?.busy == true;
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 28),
          children: [
            if (session?.notice != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: ScoutNotice(
                  session!.notice!,
                  key: const Key('session-notice'),
                ),
              ),
            if (session?.error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: ScoutNotice(session!.error!, error: true),
              ),
            SizedBox(
              height: 300,
              child: PitchSurface(
                radius: 16,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(22, 0, 22, 24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      Text('YouScout', style: scoutDisplay(64, p.surPelouse)),
                      const SizedBox(height: 6),
                      Text(
                        'Ton talent mérite le terrain.',
                        style: TextStyle(color: p.surPelouse, fontSize: 16),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(height: 28),
            Text(
              'Le prochain talent, c’est peut-être toi.',
              style: Theme.of(context).textTheme.headlineLarge,
            ),
            const SizedBox(height: 12),
            Text(
              'Partage ton jeu. Inspire la communauté. Fais-toi découvrir.',
              style: TextStyle(color: p.gradin, fontSize: 16),
            ),
            const SizedBox(height: 28),
            if (session != null) ...[
              if (busy)
                const Padding(
                  padding: EdgeInsets.only(bottom: 12),
                  child: LinearProgressIndicator(),
                ),
              ScoutButton(
                'Se connecter',
                key: const Key('login'),
                onPressed: busy ? null : () => session!.login(),
              ),
              const SizedBox(height: 10),
              ScoutButton(
                'Créer mon compte',
                secondary: true,
                onPressed: busy ? null : () => session!.login(register: true),
              ),
              const SizedBox(height: 24),
              Divider(height: 1, color: p.ligne),
              const SizedBox(height: 20),
            ],
            Text(
              session != null
                  ? 'Pas encore prêt ? La démo montre des talents fictifs, sans compte et sans rien envoyer.'
                  : 'La démo montre des talents fictifs, sans compte et sans rien envoyer.',
              style: TextStyle(color: p.gradin),
            ),
            const SizedBox(height: 12),
            ScoutButton(
              'Explorer la démo',
              key: const Key('enter-demo'),
              secondary: session != null,
              onPressed: busy ? null : store.enterDemo,
            ),
            const SizedBox(height: 4),
            TextButton(
              onPressed: () => showDialog<void>(
                context: context,
                builder: (_) => AlertDialog(
                  title: const Text('Choisis ton expérience'),
                  content: const Text(
                    'Le mode connecté enregistre ton profil et tes abonnements dans ton compte. La démo propose des talents fictifs et des interactions locales. Aucun fichier vidéo n’est envoyé.',
                  ),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('Compris'),
                    ),
                  ],
                ),
              ),
              child: const Text('À propos de cette version'),
            ),
          ],
        ),
      ),
    );
  }
}

class ScoutShell extends StatefulWidget {
  const ScoutShell({required this.store, super.key});
  final ScoutStore store;
  @override
  State<ScoutShell> createState() => _ScoutShellState();
}

class _ScoutShellState extends State<ScoutShell> {
  int tab = 0;
  void go(int index) => setState(() => tab = index);
  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: IndexedStack(
        index: tab,
        children: [
          FeedScreen(store: widget.store, onExplore: () => go(1)),
          DiscoverScreen(store: widget.store),
          UploadScreen(store: widget.store, onPublished: () => go(0)),
          InboxScreen(store: widget.store),
          ProfileScreen(store: widget.store, onUpload: () => go(2)),
        ],
      ),
    ),
    bottomNavigationBar: ScoutTabs(selected: tab, onSelected: go),
  );
}
