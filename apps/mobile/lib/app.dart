import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'core/theme.dart';
import 'state/scout_store.dart';
import 'screens/social_screens.dart';
import 'screens/upload_screen.dart';
import 'screens/messages_screen.dart';
import 'widgets/scout_widgets.dart';

class YouScoutApp extends StatefulWidget {
  const YouScoutApp({this.store, super.key});
  final ScoutStore? store;
  @override
  State<YouScoutApp> createState() => _YouScoutAppState();
}

class _YouScoutAppState extends State<YouScoutApp> {
  late final ScoutStore store = widget.store ?? ScoutStore();
  @override
  void dispose() {
    if (widget.store == null) store.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'YouScout',
    debugShowCheckedModeBanner: false,
    theme: scoutTheme(),
    locale: const Locale('fr'),
    supportedLocales: const [Locale('fr')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    builder: (context, child) => ColoredBox(
      color: const Color(0xFF060A08),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: child!,
        ),
      ),
    ),
    home: ListenableBuilder(
      listenable: store,
      builder: (context, _) =>
          store.inDemo ? ScoutShell(store: store) : WelcomeScreen(store: store),
    ),
  );
}

class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({required this.store, super.key});
  final ScoutStore store;
  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          const SizedBox(height: 20),
          Text(
            'YOUSCOUT',
            style: Theme.of(
              context,
            ).textTheme.headlineSmall?.copyWith(color: ScoutColors.brand),
          ),
          const SizedBox(height: 8),
          const Text(
            'TON TALENT MÉRITE LE TERRAIN.',
            style: TextStyle(
              color: ScoutColors.muted,
              fontSize: 12,
              letterSpacing: 1.2,
            ),
          ),
          const SizedBox(height: 20),
          const FootballArt(variant: 'upload', height: 275),
          const SizedBox(height: 24),
          Text(
            'Le prochain talent,\nc’est peut-être toi.',
            style: Theme.of(context).textTheme.headlineLarge,
          ),
          const SizedBox(height: 16),
          const Text(
            'Partage ton jeu. Inspire la communauté. Fais-toi découvrir.',
            style: TextStyle(color: ScoutColors.muted),
          ),
          const SizedBox(height: 28),
          ScoutButton(
            'Explorer la démo',
            key: const Key('enter-demo'),
            onPressed: store.enterDemo,
          ),
          const SizedBox(height: 12),
          ScoutButton(
            'À propos de cette version',
            secondary: true,
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => AlertDialog(
                title: const Text('Une première version à explorer'),
                content: const Text(
                  'Les profils et publications proposés sont fictifs. Tes interactions restent sur cet appareil pendant cette session. Aucun compte n’est créé et aucun fichier n’est envoyé.',
                ),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text('Compris'),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          const Text(
            'Mode démonstration · sans connexion à un serveur',
            textAlign: TextAlign.center,
            style: TextStyle(color: ScoutColors.muted, fontSize: 12),
          ),
        ],
      ),
    ),
  );
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
    bottomNavigationBar: NavigationBar(
      selectedIndex: tab,
      onDestinationSelected: go,
      destinations: const [
        NavigationDestination(
          icon: Icon(Icons.home_outlined),
          selectedIcon: Icon(Icons.home, color: ScoutColors.brand),
          label: 'Accueil',
        ),
        NavigationDestination(
          icon: Icon(Icons.explore_outlined),
          selectedIcon: Icon(Icons.explore, color: ScoutColors.brand),
          label: 'Explorer',
        ),
        NavigationDestination(
          icon: Icon(Icons.add_circle_outline),
          selectedIcon: Icon(Icons.add_circle, color: ScoutColors.brand),
          label: 'Publier',
        ),
        NavigationDestination(
          icon: Icon(Icons.chat_bubble_outline),
          selectedIcon: Icon(Icons.chat_bubble, color: ScoutColors.brand),
          label: 'Messages',
        ),
        NavigationDestination(
          icon: Icon(Icons.person_outline),
          selectedIcon: Icon(Icons.person, color: ScoutColors.brand),
          label: 'Profil',
        ),
      ],
    ),
  );
}
