import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:youscout_mobile/app.dart';
import 'package:youscout_mobile/screens/connected_screen.dart';
import 'package:youscout_mobile/state/scout_store.dart';
import 'connected_session_test.dart' show FakeAuth, ApiFixture;

void main() {
  testWidgets(
    'edit profile, follow a hashtag and logout from the connected UI',
    (tester) async {
      final fixture = ApiFixture();
      final session = fixture.session(FakeAuth());
      final demo = ScoutStore();
      addTearDown(session.dispose);
      addTearDown(demo.dispose);
      await tester.pumpWidget(YouScoutApp(store: demo, session: session));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.byKey(const Key('login')), 200);
      await tester.tap(find.byKey(const Key('login')));
      await tester.pumpAndSettle();
      expect(find.text('Joueur réel'), findsNWidgets(2));
      await tester.enterText(
        find.byKey(const Key('account-name')),
        'Mon nom enregistré',
      );
      await tester.ensureVisible(find.byKey(const Key('save-account')));
      await tester.tap(find.byKey(const Key('save-account')));
      await tester.pumpAndSettle();
      expect(fixture.name, 'Mon nom enregistré');
      await tester.tap(find.text('Catalogue').last);
      await tester.pumpAndSettle();
      expect(find.text('Dribble 0'), findsOneWidget);
      await tester.tap(find.text('Hashtags'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const Key('follow-tag-real')));
      await tester.tap(find.byKey(const Key('follow-tag-real')));
      await tester.pumpAndSettle();
      expect(fixture.following, contains('tag-real'));
      await tester.tap(find.byTooltip('Se déconnecter'));
      await tester.pumpAndSettle();
      expect(session.profile, isNull);
      expect(find.text('Mon nom enregistré'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'closing the account needs the typed confirmation then signs out',
    (tester) async {
      final fixture = ApiFixture(), auth = FakeAuth();
      final session = fixture.session(auth);
      final demo = ScoutStore();
      addTearDown(session.dispose);
      addTearDown(demo.dispose);
      await tester.pumpWidget(YouScoutApp(store: demo, session: session));
      await tester.pumpAndSettle();
      await tester.runAsync(session.login);
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.byKey(const Key('close-account')),
        200,
        scrollable: find
            .descendant(
              of: find.byType(ProfileEditor),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.tap(find.byKey(const Key('close-account')));
      await tester.pumpAndSettle();
      final confirm = find.byKey(const Key('close-account-confirm'));
      expect(tester.widget<FilledButton>(confirm).onPressed, isNull);
      await tester.enterText(
        find.byKey(const Key('close-account-confirmation')),
        'fermer',
      );
      await tester.pump();
      expect(tester.widget<FilledButton>(confirm).onPressed, isNull);
      await tester.tap(find.text('Annuler'));
      await tester.pumpAndSettle();
      expect(fixture.closures, isEmpty);

      await tester.scrollUntilVisible(
        find.byKey(const Key('close-account')),
        200,
        scrollable: find
            .descendant(
              of: find.byType(ProfileEditor),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.tap(find.byKey(const Key('close-account')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('close-account-confirmation')),
        'FERMER',
      );
      await tester.pump();
      await tester.tap(confirm);
      await tester.pumpAndSettle();
      expect(fixture.closures, [
        {'confirmation': 'FERMER'},
      ]);
      expect(session.signedIn, isFalse);
      expect(auth.logouts, 0);
      expect(find.byKey(const Key('session-notice')), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'expired session returns to welcome and never displays demo profile',
    (tester) async {
      final fixture = ApiFixture(), auth = FakeAuth();
      final session = fixture.session(auth);
      final demo = ScoutStore();
      addTearDown(session.dispose);
      addTearDown(demo.dispose);
      await tester.pumpWidget(YouScoutApp(store: demo, session: session));
      await tester.pumpAndSettle();
      await tester.runAsync(session.login);
      await tester.pumpAndSettle();
      fixture.reject = 401;
      auth.expired = true;
      await tester.tap(find.byTooltip('Actualiser'));
      await tester.pumpAndSettle();
      expect(session.signedIn, isFalse);
      expect(find.text('Joueur réel'), findsNothing);
      await tester.scrollUntilVisible(find.text('Session expirée.'), -200);
      expect(find.text('Session expirée.'), findsOneWidget);
      expect(demo.inDemo, isFalse);
      expect(tester.takeException(), isNull);
    },
  );
}
