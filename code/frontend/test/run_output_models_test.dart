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
    expect(accumulator.main?.lastSequence, 4);
  });
}
