import 'package:flutter_test/flutter_test.dart';
import 'package:youscout_mobile/state/scout_store.dart';

void main() {
  late ScoutStore store;
  setUp(() => store = ScoutStore());
  tearDown(() => store.dispose());
  test('unfollowing removes author from subscription feed', () {
    expect(store.followingPosts, isEmpty);
    store.toggleFollow('ines');
    expect(store.followingPosts.map((p) => p.authorId), ['ines']);
    store.toggleFollow('ines');
    expect(store.followingPosts, isEmpty);
  });
  test('block hides posts and prevents messaging, unblock restores access', () {
    store.toggleFollow('ines');
    store.block('ines');
    expect(store.posts.any((p) => p.authorId == 'ines'), isFalse);
    expect(store.followed, isNot(contains('ines')));
    expect(store.send('ines', 'Bonjour'), isFalse);
    store.unblock('ines');
    expect(store.send('ines', 'Bonjour'), isTrue);
  });
  test('comment ownership and cascading reply deletion', () {
    final other = store.posts.firstWhere((p) => p.authorId == 'ines');
    store.addComment(other, 'Bien joué');
    final mine = other.comments.last;
    store.addComment(other, 'Merci', parentId: mine.id);
    store.deleteComment(other, mine);
    expect(other.comments, isEmpty);
    final own = store.posts.first;
    final foreignComment = own.comments.first;
    expect(() => store.deleteComment(other, foreignComment), throwsStateError);
    store.deleteComment(own, foreignComment);
    expect(own.comments, isNot(contains(foreignComment)));
  });
  test('rating edits replace prior value and validate skills', () {
    final post = store.posts.first;
    store.rate(post, 'Dribble', 3);
    store.rate(post, 'Dribble', 5);
    expect(store.ratings, {'p1/Dribble': 5});
    expect(
      () => store.rate(post, 'Compétence inconnue', 4),
      throwsArgumentError,
    );
    expect(() => store.rate(post, 'Dribble', 6), throwsArgumentError);
  });
  test('publication normalizes tags, enforces fields and ownership', () {
    expect(
      () => store.publish(description: ' ', skills: {'Dribble'}, hashtags: ''),
      throwsArgumentError,
    );
    final post = store.publish(
      description: ' Mon clip ',
      skills: {'Dribble'},
      hashtags: '#Foot #Foot, #Dribble',
    );
    expect(post.description, 'Mon clip');
    expect(post.hashtags, ['Foot', 'Dribble']);
    expect(store.posts.first, same(post));
    store.deletePost(post);
    expect(store.posts, isNot(contains(post)));
    expect(
      () =>
          store.deletePost(store.posts.firstWhere((p) => p.authorId == 'ines')),
      throwsStateError,
    );
  });
  test('profile changes preserve author identity', () {
    store.editProfile('Nouveau Joueur', 'Ma nouvelle bio');
    expect(store.me.id, 'me');
    expect(store.talent(store.posts.first.authorId).name, 'Nouveau Joueur');
    expect(store.me.initials, 'NJ');
  });
}
