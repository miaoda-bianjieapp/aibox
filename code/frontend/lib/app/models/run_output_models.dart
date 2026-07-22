class RunOutputSnapshot {
  const RunOutputSnapshot({
    required this.runId,
    required this.channel,
    required this.format,
    required this.content,
    required this.status,
    required this.lastSequence,
    required this.updatedAt,
  });

  factory RunOutputSnapshot.fromJson(Map<String, dynamic> json) {
    return RunOutputSnapshot(
      runId: json['runId']?.toString() ?? '',
      channel: json['channel']?.toString() ?? 'main',
      format: json['format']?.toString() ?? 'text',
      content: json['content']?.toString() ?? '',
      status: json['status']?.toString() ?? 'STREAMING',
      lastSequence: _integer(json['lastSequence']),
      updatedAt:
          DateTime.tryParse(json['updatedAt']?.toString() ?? '')?.toLocal() ??
              DateTime.fromMillisecondsSinceEpoch(0),
    );
  }

  final String runId;
  final String channel;
  final String format;
  final String content;
  final String status;
  final int lastSequence;
  final DateTime updatedAt;

  bool get isTerminal =>
      status == 'COMPLETED' || status == 'FAILED' || status == 'PARTIAL';
}

class RunOutputEvent {
  const RunOutputEvent({
    required this.eventId,
    required this.channel,
    required this.sequence,
    required this.type,
    required this.format,
    required this.delta,
    required this.content,
    required this.status,
  });

  factory RunOutputEvent.fromJson(Map<String, dynamic> json) {
    return RunOutputEvent(
      eventId: _integer(json['eventId']),
      channel: json['channel']?.toString() ?? 'main',
      sequence: _integer(json['sequence']),
      type: json['type']?.toString() ?? '',
      format: json['format']?.toString(),
      delta: json['delta']?.toString(),
      content: json['content']?.toString(),
      status: json['status']?.toString(),
    );
  }

  final int eventId;
  final String channel;
  final int sequence;
  final String type;
  final String? format;
  final String? delta;
  final String? content;
  final String? status;
}

class RunOutputAccumulator {
  final Map<String, RunOutputSnapshot> _snapshots = {};
  int _lastEventId = 0;

  int get lastEventId => _lastEventId;

  RunOutputSnapshot? get main => _snapshots['main'];

  Iterable<RunOutputSnapshot> get snapshots => _snapshots.values;

  RunOutputSnapshot? applyEvent(String runId, RunOutputEvent event) {
    if (event.eventId > 0 && event.eventId <= _lastEventId) return null;
    final current = _snapshots[event.channel];
    if (current != null && event.sequence <= current.lastSequence) {
      if (event.eventId > _lastEventId) _lastEventId = event.eventId;
      return null;
    }

    var content = current?.content ?? '';
    var format = event.format ?? current?.format ?? 'text';
    var status = event.status ?? current?.status ?? 'STREAMING';
    switch (event.type) {
      case 'started':
        format = event.format ?? format;
        content = event.content ?? '';
        status = 'STREAMING';
      case 'append':
        content += event.delta ?? '';
        status = 'STREAMING';
      case 'replace':
        content = event.content ?? '';
        status = 'STREAMING';
      case 'completed':
        status = 'COMPLETED';
      case 'failed':
        status = 'FAILED';
      case 'partial':
        status = 'PARTIAL';
    }
    final snapshot = RunOutputSnapshot(
      runId: runId,
      channel: event.channel,
      format: format,
      content: content,
      status: status,
      lastSequence: event.sequence,
      updatedAt: DateTime.now(),
    );
    _snapshots[event.channel] = snapshot;
    if (event.eventId > _lastEventId) _lastEventId = event.eventId;
    return snapshot;
  }

  RunOutputSnapshot? applySnapshot(RunOutputSnapshot snapshot) {
    final current = _snapshots[snapshot.channel];
    if (current != null && snapshot.lastSequence < current.lastSequence) {
      return null;
    }
    if (current != null &&
        snapshot.lastSequence == current.lastSequence &&
        snapshot.content == current.content &&
        snapshot.status == current.status) {
      return null;
    }
    _snapshots[snapshot.channel] = snapshot;
    return snapshot;
  }
}

int _integer(Object? value) =>
    value is num ? value.toInt() : int.tryParse('$value') ?? 0;
