import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/run_output_models.dart';

void main() {
  test('accumulator orders deltas and ignores duplicate events', () {
    final accumulator = RunOutputAccumulator();

    accumulator.applyEvent(
      'run-1',
      const RunOutputEvent(
        eventId: 1,
        channel: 'main',
        sequence: 1,
        type: 'started',
        format: 'markdown',
        delta: null,
        content: '',
        status: 'STREAMING',
      ),
    );
    accumulator.applyEvent(
      'run-1',
      const RunOutputEvent(
        eventId: 2,
        channel: 'main',
        sequence: 2,
        type: 'append',
        format: null,
        delta: 'Hello',
        content: null,
        status: 'STREAMING',
      ),
    );
    accumulator.applyEvent(
      'run-1',
      const RunOutputEvent(
        eventId: 2,
        channel: 'main',
        sequence: 2,
        type: 'append',
        format: null,
        delta: ' duplicate',
        content: null,
        status: 'STREAMING',
      ),
    );

    expect(accumulator.main?.content, 'Hello');
    expect(accumulator.main?.format, 'markdown');
    expect(accumulator.main?.updateType, RunOutputUpdateType.append);
    expect(accumulator.lastEventId, 2);
  });

  test('a restarted stream clears partial content from a provider retry', () {
    final accumulator = RunOutputAccumulator();
    accumulator.applyEvent(
      'run-1',
      const RunOutputEvent(
        eventId: 1,
        channel: 'main',
        sequence: 1,
        type: 'append',
        format: 'markdown',
        delta: 'partial',
        content: null,
        status: 'STREAMING',
      ),
    );

    accumulator.applyEvent(
      'run-1',
      const RunOutputEvent(
        eventId: 2,
        channel: 'main',
        sequence: 2,
        type: 'started',
        format: 'markdown',
        delta: null,
        content: '',
        status: 'STREAMING',
      ),
    );

    expect(accumulator.main?.content, '');
    expect(accumulator.main?.updateType, RunOutputUpdateType.started);
  });

  test('newer snapshots recover missed SSE events', () {
    final accumulator = RunOutputAccumulator();
    final applied = accumulator.applySnapshot(RunOutputSnapshot(
      runId: 'run-1',
      channel: 'main',
      format: 'markdown',
      content: '# Recovered',
      status: 'STREAMING',
      lastSequence: 4,
      updatedAt: DateTime(2026, 7, 21),
    ));

    expect(applied?.content, '# Recovered');
    expect(applied?.updateType, RunOutputUpdateType.snapshot);
    expect(accumulator.main?.lastSequence, 4);
  });

  test('replace events remain distinguishable from appended content', () {
    final accumulator = RunOutputAccumulator();

    final snapshot = accumulator.applyEvent(
      'run-1',
      const RunOutputEvent(
        eventId: 1,
        channel: 'main',
        sequence: 1,
        type: 'replace',
        format: 'text',
        delta: null,
        content: '正在处理第 2 步',
        status: 'STREAMING',
      ),
    );

    expect(snapshot?.content, '正在处理第 2 步');
    expect(snapshot?.updateType, RunOutputUpdateType.replace);
  });

  test('accumulates deltas without assuming complete markdown tokens', () {
    final accumulator = RunOutputAccumulator();

    accumulator.applyEvent(
      'run-1',
      const RunOutputEvent(
        eventId: 1,
        channel: 'main',
        sequence: 1,
        type: 'append',
        format: 'markdown',
        delta: '**加',
        content: null,
        status: 'STREAMING',
      ),
    );
    accumulator.applyEvent(
      'run-1',
      const RunOutputEvent(
        eventId: 2,
        channel: 'main',
        sequence: 2,
        type: 'append',
        format: null,
        delta: '粗**',
        content: null,
        status: 'STREAMING',
      ),
    );

    expect(accumulator.main?.content, '**加粗**');
    expect(accumulator.main?.lastSequence, 2);
  });
}
