import 'dart:io';

import 'package:flutter/services.dart';

class PickedLocalFile {
  const PickedLocalFile({
    required this.name,
    required this.mediaType,
    this.path,
    this.bytes,
    required this.sizeBytes,
    this.deleteAfterUse = false,
  }) : assert(path != null || bytes != null);

  final String name;
  final String mediaType;
  final String? path;
  final Uint8List? bytes;
  final int sizeBytes;
  final bool deleteAfterUse;

  Stream<List<int>> openRead() {
    final memory = bytes;
    if (memory != null) return Stream<List<int>>.value(memory);
    return File(path!).openRead();
  }

  Future<void> cleanup() async {
    if (!deleteAfterUse || path == null) return;
    try {
      final file = File(path!);
      if (await file.exists()) await file.delete();
    } on FileSystemException {
      // Cache cleanup is best effort and must not hide the upload result.
    }
  }
}

abstract final class NativeFilePicker {
  static const _channel = MethodChannel('com.aibox.yuanzuo_ai/file_picker');

  static Future<PickedLocalFile?> pick(
      {List<String> mimeTypes = const ['*/*']}) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'pickFile',
      {'mimeTypes': mimeTypes},
    );
    if (result == null) return null;
    return _fromResult(result);
  }

  static Future<List<PickedLocalFile>> pickMultiple({
    List<String> mimeTypes = const ['*/*'],
    int maxFiles = 5,
  }) async {
    final result = await _channel.invokeListMethod<dynamic>(
      'pickFiles',
      {
        'mimeTypes': mimeTypes,
        'maxFiles': maxFiles.clamp(1, 10),
      },
    );
    if (result == null) return const [];
    return result
        .whereType<Map>()
        .map((item) => _fromResult(Map<String, dynamic>.from(item)))
        .toList();
  }

  static PickedLocalFile _fromResult(Map<String, dynamic> result) {
    final path = result['path']?.toString();
    final sizeBytes = result['sizeBytes'] is num
        ? (result['sizeBytes'] as num).toInt()
        : int.tryParse('${result['sizeBytes']}') ?? 0;
    if (path == null || path.isEmpty || sizeBytes <= 0) {
      throw const FormatException('文件读取失败');
    }
    return PickedLocalFile(
      name: result['name']?.toString() ?? 'unnamed-file',
      mediaType: result['mediaType']?.toString() ?? 'application/octet-stream',
      path: path,
      sizeBytes: sizeBytes,
      deleteAfterUse: true,
    );
  }

  static Future<bool> save({
    required String fileName,
    required String mediaType,
    required Uint8List bytes,
  }) async {
    final result = await _channel.invokeMethod<bool>(
      'saveFile',
      {
        'fileName': fileName,
        'mediaType': mediaType,
        'bytes': bytes,
      },
    );
    return result == true;
  }
}
