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

class SavedLocalFile {
  const SavedLocalFile({
    required this.name,
    required this.uri,
    required this.sizeBytes,
  });

  factory SavedLocalFile.fromJson(Map<String, dynamic> json) {
    final name = json['name']?.toString() ?? '';
    final uri = json['uri']?.toString() ?? '';
    final sizeBytes = json['sizeBytes'] is num
        ? (json['sizeBytes'] as num).toInt()
        : int.tryParse('${json['sizeBytes']}') ?? -1;
    if (name.isEmpty || uri.isEmpty || sizeBytes < 0) {
      throw const FormatException('文件保存结果无效');
    }
    return SavedLocalFile(name: name, uri: uri, sizeBytes: sizeBytes);
  }

  final String name;
  final String uri;
  final int sizeBytes;
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

  static Future<List<PickedLocalFile>> pickImages({
    int maxFiles = 1,
  }) async {
    final result = await _channel.invokeListMethod<dynamic>(
      'pickImages',
      {'maxFiles': maxFiles.clamp(1, 10)},
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

  static Future<String?> pickDirectory() {
    return _channel.invokeMethod<String>('pickDirectory');
  }

  static Future<SavedLocalFile> saveFileToDirectory({
    required String directoryUri,
    required String filePath,
    required String fileName,
    required String mediaType,
  }) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'saveFileToDirectory',
      {
        'directoryUri': directoryUri,
        'filePath': filePath,
        'fileName': fileName,
        'mediaType': mediaType,
      },
    );
    if (result == null) {
      throw const FormatException('文件保存结果无效');
    }
    return SavedLocalFile.fromJson(result);
  }

  static Future<SavedLocalFile?> saveFileFromPath({
    required String filePath,
    required String fileName,
    required String mediaType,
  }) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'saveFileFromPath',
      {
        'filePath': filePath,
        'fileName': fileName,
        'mediaType': mediaType,
      },
    );
    return result == null ? null : SavedLocalFile.fromJson(result);
  }

  static Future<void> cancelDirectorySave() {
    return _channel.invokeMethod<void>('cancelSaveFileToDirectory');
  }
}
