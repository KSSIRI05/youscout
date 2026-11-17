import 'package:flutter_test/flutter_test.dart';
import 'package:youscout_mobile/screens/connected_feed_screen.dart';

Map<String, dynamic> comment(String id, [String? parentId]) => {
  'id': id,
  'parentId': parentId,
  'authorSubject': 'author-$id',
  'body': id,
};

void main() {
  test('replies follow their parent and orphans stay visible at the end', () {
    final threaded = threadComments([
      comment('a'),
      comment('b'),
      comment('orphan', 'deleted'),
      comment('a1', 'a'),
      comment('b1', 'b'),
      comment('a2', 'a'),
    ]);
    expect(threaded.map((entry) => entry.comment['id']), [
      'a',
      'a1',
      'a2',
      'b',
      'b1',
      'orphan',
    ]);
    expect(threaded.map((entry) => entry.reply), [
      false,
      true,
      true,
      false,
      true,
      true,
    ]);
  });

  test('short subjects are not truncated past their length', () {
    expect(shortSubject('bob'), 'bob');
    expect(shortSubject('0123456789abcdef'), '01234567');
  });
}
