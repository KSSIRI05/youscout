import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:youscout_mobile/app.dart';
import 'package:youscout_mobile/state/scout_store.dart';

Future<ScoutStore> openDemo(
  WidgetTester tester, {
  Size size = const Size(390, 844),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final store = ScoutStore();
  await tester.pumpWidget(YouScoutApp(store: store));
  await tester.scrollUntilVisible(find.byKey(const Key('enter-demo')), 200);
  await tester.tap(find.byKey(const Key('enter-demo')));
  await tester.pumpAndSettle();
  return store;
}

void main() {
  testWidgets('like a post and preserve state across tabs', (tester) async {
    final store = await openDemo(tester);
    final like = find.byKey(const Key('like-p1'));
    await tester.ensureVisible(like);
    await tester.tap(like);
    await tester.pumpAndSettle();
    expect(store.liked, contains('p1'));
    await tester.tap(find.text('Explorer').last);
    await tester.pumpAndSettle();
    expect(find.text('Le talent est partout.'), findsOneWidget);
    await tester.tap(find.text('Accueil').last);
    await tester.pumpAndSettle();
    expect(store.likeCount(store.posts.first), 1201);
    expect(tester.takeException(), isNull);
  });
  testWidgets('discovery search and empty result', (tester) async {
    await openDemo(tester);
    await tester.tap(find.text('Explorer').last);
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('talent-search')),
      'personne-inconnue',
    );
    await tester.pumpAndSettle();
    expect(find.text('Aucun talent trouvé.'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('talent-search')), 'Inès');
    await tester.pumpAndSettle();
    expect(find.text('Inès Laurent'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('publish validates fields then adds a local post', (
    tester,
  ) async {
    final store = await openDemo(tester);
    await tester.tap(find.text('Publier').last);
    await tester.pumpAndSettle();
    final demo = find.text('Essayer avec le contenu de démo');
    await tester.ensureVisible(demo);
    await tester.tap(demo);
    await tester.pumpAndSettle();
    final submit = find.text('Ajouter à mon feed local');
    await tester.ensureVisible(submit);
    await tester.tap(submit);
    await tester.pumpAndSettle();
    expect(find.text('Ajoute une description.'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('post-description')),
      'Mon nouveau clip de test',
    );
    final skill = find.widgetWithText(FilterChip, 'Dribble');
    await tester.ensureVisible(skill);
    await tester.tap(skill);
    await tester.ensureVisible(submit);
    await tester.tap(submit);
    await tester.pumpAndSettle();
    expect(store.posts.first.description, 'Mon nouveau clip de test');
    expect(store.posts.first.isDemo, isTrue);
    expect(find.text('YouScout'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('chat stores messages locally without claiming delivery', (
    tester,
  ) async {
    final store = await openDemo(tester);
    await tester.tap(find.text('Messages').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Inès Laurent'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('message-input')),
      'Bonjour Inès !',
    );
    await tester.tap(find.byTooltip('Envoyer le message'));
    await tester.pumpAndSettle();
    expect(store.conversations['ines']!.last.text, 'Bonjour Inès !');
    expect(find.text('Bonjour Inès !'), findsOneWidget);
    expect(find.textContaining('Livré'), findsNothing);
    expect(tester.takeException(), isNull);
  });
  testWidgets('small viewport navigation has no render overflow', (
    tester,
  ) async {
    await openDemo(tester, size: const Size(320, 640));
    for (final label in [
      'Explorer',
      'Publier',
      'Messages',
      'Profil',
      'Accueil',
    ]) {
      await tester.tap(find.text(label).last);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull, reason: label);
    }
  });
}
