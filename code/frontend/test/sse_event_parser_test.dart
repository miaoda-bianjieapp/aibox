import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/network/sse_event_parser.dart';

void main() {
  test('parses event id, type and multiline data', () async {
    final frames = await parseSseLines(Stream<String>.fromIterable([
      'id: 12',
      'event: output',
      'data: {"delta":"hello"',
      'data: ,"sequence":2}',
      '',
    ])).toList();

    expect(frames, hasLength(1));
    expect(frames.single.id, '12');
    expect(frames.single.event, 'output');
    expect(frames.single.data, '{"delta":"hello"\n,"sequence":2}');
  });

  test('ignores comments and emits a final event without trailing blank line',
      () async {
    final frames = await parseSseLines(Stream<String>.fromIterable([
      ': keep-alive',
      'event: connected',
      'data: {"status":"RUNNING"}',
    ])).toList();

    expect(frames.single.event, 'connected');
  });
}
