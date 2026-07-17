import 'package:flutter/services.dart';

class PickedLocalFile {
  const PickedLocalFile(
      {required this.name, required this.mediaType, required this.bytes});

  final String name;
  final String mediaType;
  final Uint8List bytes;
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
    final bytes = result['bytes'];
    if (bytes is! Uint8List) throw const FormatException('文件内容读取失败');
    return PickedLocalFile(
      name: result['name']?.toString() ?? 'unnamed-file',
      mediaType: result['mediaType']?.toString() ?? 'application/octet-stream',
      bytes: bytes,
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
