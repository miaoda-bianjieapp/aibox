class VideoStoryboardEditing {
  static const int maxShots = 20;

  static List<Map<String, Object?>> addAfter(
    List<Map<String, Object?>> source,
    int index,
    double totalDuration,
  ) {
    final shots = _copy(source);
    if (shots.length >= maxShots) {
      throw const FormatException('分镜数量不能超过 20 个');
    }
    if (shots.isEmpty) {
      return [
        _emptyShot(
          id: _nextId(shots),
          start: 0,
          end: totalDuration,
        ),
      ];
    }
    final targetIndex = index.clamp(0, shots.length - 1);
    final current = shots[targetIndex];
    final start = _number(current['startSecond']);
    final end = _number(current['endSecond']);
    if (end - start <= 0.002) {
      shots.insert(
        targetIndex + 1,
        _emptyShot(id: _nextId(shots), start: start, end: end),
      );
      return redistribute(shots, totalDuration);
    }
    final midpoint = _round((start + end) / 2);
    current['endSecond'] = midpoint;
    shots.insert(
      targetIndex + 1,
      _emptyShot(
        id: _nextId(shots),
        start: midpoint,
        end: end,
      ),
    );
    return shots;
  }

  static List<Map<String, Object?>> deleteAt(
    List<Map<String, Object?>> source,
    int index,
    double totalDuration,
  ) {
    final shots = _copy(source);
    if (shots.length <= 1) {
      throw const FormatException('至少保留一个分镜');
    }
    final targetIndex = index.clamp(0, shots.length - 1);
    final removed = shots.removeAt(targetIndex);
    final removedEnd = _number(removed['endSecond']);
    if (targetIndex == 0) {
      shots.first['startSecond'] = 0.0;
    } else {
      shots[targetIndex - 1]['endSecond'] =
          targetIndex < shots.length ? removedEnd : totalDuration;
    }
    return shots;
  }

  static List<Map<String, Object?>> move(
    List<Map<String, Object?>> source,
    int from,
    int to,
    double totalDuration,
  ) {
    final shots = _copy(source);
    if (from < 0 ||
        from >= shots.length ||
        to < 0 ||
        to >= shots.length ||
        from == to) {
      return shots;
    }
    final shot = shots.removeAt(from);
    shots.insert(to, shot);
    return redistribute(shots, totalDuration);
  }

  static List<Map<String, Object?>> redistribute(
    List<Map<String, Object?>> source,
    double totalDuration,
  ) {
    final shots = _copy(source);
    if (shots.isEmpty) return shots;
    final segment = totalDuration / shots.length;
    for (var index = 0; index < shots.length; index++) {
      shots[index]['startSecond'] = _round(segment * index);
      shots[index]['endSecond'] = index == shots.length - 1
          ? totalDuration
          : _round(segment * (index + 1));
    }
    return shots;
  }

  static List<Map<String, Object?>> _copy(
    List<Map<String, Object?>> source,
  ) =>
      source
          .map(
            (shot) => <String, Object?>{
              ...shot,
              'assetRefs': List<String>.from(
                (shot['assetRefs'] as List? ?? const []).map(
                  (item) => item.toString(),
                ),
              ),
            },
          )
          .toList();

  static Map<String, Object?> _emptyShot({
    required String id,
    required double start,
    required double end,
  }) =>
      {
        'id': id,
        'startSecond': start,
        'endSecond': end,
        'shotDescription': '',
        'visualAction': '',
        'shotSize': '',
        'cameraMovement': '',
        'environment': '',
        'continuity': '',
        'assetRefs': <String>[],
      };

  static String _nextId(List<Map<String, Object?>> shots) {
    final used = shots.map((shot) => shot['id']?.toString()).toSet();
    var index = 1;
    while (used.contains('shot-$index')) {
      index++;
    }
    return 'shot-$index';
  }

  static double _number(Object? value) =>
      value is num ? value.toDouble() : double.tryParse('$value') ?? 0;

  static double _round(double value) => (value * 1000).roundToDouble() / 1000;
}
