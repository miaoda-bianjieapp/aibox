import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/network/native_file_picker.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.aibox.yuanzuo_ai/file_picker');
  MethodCall? capturedCall;

  setUp(() {
    capturedCall = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return switch (call.method) {
        'pickDirectory' => 'content://downloads',
        'saveFileToDirectory' => 'report(1).pdf',
        'cancelSaveFileToDirectory' => null,
        _ => null,
      };
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('picks one destination directory for a download batch', () async {
    final directory = await NativeFilePicker.pickDirectory();

    expect(directory, 'content://downloads');
    expect(capturedCall?.method, 'pickDirectory');
  });

  test('saves a temporary file path without transferring file bytes', () async {
    final savedName = await NativeFilePicker.saveFileToDirectory(
      directoryUri: 'content://downloads',
      filePath: '/cache/report.pdf',
      fileName: 'report.pdf',
      mediaType: 'application/pdf',
    );

    expect(savedName, 'report(1).pdf');
    expect(capturedCall?.method, 'saveFileToDirectory');
    expect(capturedCall?.arguments, {
      'directoryUri': 'content://downloads',
      'filePath': '/cache/report.pdf',
      'fileName': 'report.pdf',
      'mediaType': 'application/pdf',
    });
  });

  test('forwards cancellation to the native directory writer', () async {
    await NativeFilePicker.cancelDirectorySave();

    expect(capturedCall?.method, 'cancelSaveFileToDirectory');
  });
}
