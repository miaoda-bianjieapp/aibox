import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/pages/video_storyboard_editing.dart';

void main() {
  test('adding a shot splits the selected time segment', () {
    final result = VideoStoryboardEditing.addAfter(
      [
        _shot('shot-1', 0, 8),
      ],
      0,
      8,
    );

    expect(result, hasLength(2));
    expect(result[0]['endSecond'], 4.0);
    expect(result[1]['startSecond'], 4.0);
    expect(result[1]['endSecond'], 8.0);
  });

  test('deleting a middle shot merges its time into the previous shot', () {
    final result = VideoStoryboardEditing.deleteAt(
      [
        _shot('shot-1', 0, 3),
        _shot('shot-2', 3, 5),
        _shot('shot-3', 5, 8),
      ],
      1,
      8,
    );

    expect(result, hasLength(2));
    expect(result[0]['endSecond'], 5.0);
    expect(result[1]['startSecond'], 5.0);
  });

  test('moving a shot redistributes a continuous timeline', () {
    final result = VideoStoryboardEditing.move(
      [
        _shot('shot-1', 0, 2),
        _shot('shot-2', 2, 5),
        _shot('shot-3', 5, 8),
      ],
      2,
      0,
      8,
    );

    expect(result.map((shot) => shot['id']), ['shot-3', 'shot-1', 'shot-2']);
    expect(result.first['startSecond'], 0.0);
    expect(result.last['endSecond'], 8.0);
    expect(result[0]['endSecond'], result[1]['startSecond']);
    expect(result[1]['endSecond'], result[2]['startSecond']);
  });

  test('the final shot cannot be deleted', () {
    expect(
      () => VideoStoryboardEditing.deleteAt(
        [_shot('shot-1', 0, 8)],
        0,
        8,
      ),
      throwsFormatException,
    );
  });
}

Map<String, Object?> _shot(String id, double start, double end) => {
      'id': id,
      'startSecond': start,
      'endSecond': end,
      'shotDescription': 'description',
      'visualAction': 'action',
      'assetRefs': <String>[],
    };
