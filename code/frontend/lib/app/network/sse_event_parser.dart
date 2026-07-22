class SseEventFrame {
  const SseEventFrame({
    required this.id,
    required this.event,
    required this.data,
  });

  final String? id;
  final String event;
  final String data;
}

Stream<SseEventFrame> parseSseLines(Stream<String> lines) async* {
  String? id;
  var event = 'message';
  final data = StringBuffer();

  await for (final line in lines) {
    if (line.isEmpty) {
      if (data.isNotEmpty) {
        yield SseEventFrame(
          id: id,
          event: event,
          data: data.toString(),
        );
      }
      id = null;
      event = 'message';
      data.clear();
      continue;
    }
    if (line.startsWith(':')) continue;

    final separator = line.indexOf(':');
    final field = separator < 0 ? line : line.substring(0, separator);
    var value = separator < 0 ? '' : line.substring(separator + 1);
    if (value.startsWith(' ')) value = value.substring(1);
    switch (field) {
      case 'id':
        id = value;
      case 'event':
        event = value.isEmpty ? 'message' : value;
      case 'data':
        if (data.isNotEmpty) data.write('\n');
        data.write(value);
    }
  }

  if (data.isNotEmpty) {
    yield SseEventFrame(id: id, event: event, data: data.toString());
  }
}
