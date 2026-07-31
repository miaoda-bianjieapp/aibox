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
        'pickImages' => [
            {
              'name': 'reference-a.jpg',
              'mediaType': 'image/jpeg',
              'path': 'C:/cache/reference-a.jpg',
              'sizeBytes': 1024,
            },
            {
              'name': 'reference-b.png',
              'mediaType': 'image/png',
              'path': 'C:/cache/reference-b.png',
              'sizeBytes': 2048,
            },
          ],
        'pickDirectory' => 'content://downloads',
        'saveFileToDirectory' => {
            'name': 'report(1).pdf',
            'uri': 'content://downloads/report(1).pdf',
            'sizeBytes': 2048,
          },
        'saveFileFromPath' => {
            'name': 'report.pdf',
            'uri': 'content://picked/report.pdf',
            'sizeBytes': 2048,
          },
        'openFile' => true,
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

  test('opens the system photo picker with the requested image limit',
      () async {
    final files = await NativeFilePicker.pickImages(maxFiles: 2);

    expect(files.map((file) => file.name), [
      'reference-a.jpg',
      'reference-b.png',
    ]);
    expect(capturedCall?.method, 'pickImages');
    expect(capturedCall?.arguments, {'maxFiles': 2});
  });

  test('saves a temporary file path without transferring file bytes', () async {
    final saved = await NativeFilePicker.saveFileToDirectory(
      directoryUri: 'content://downloads',
      filePath: '/cache/report.pdf',
      fileName: 'report.pdf',
      mediaType: 'application/pdf',
    );

    expect(saved.name, 'report(1).pdf');
    expect(saved.uri, 'content://downloads/report(1).pdf');
    expect(saved.sizeBytes, 2048);
    expect(capturedCall?.method, 'saveFileToDirectory');
    expect(capturedCall?.arguments, {
      'directoryUri': 'content://downloads',
      'filePath': '/cache/report.pdf',
      'fileName': 'report.pdf',
      'mediaType': 'application/pdf',
    });
  });

  test('falls back to the system single-file destination picker', () async {
    final saved = await NativeFilePicker.saveFileFromPath(
      filePath: '/cache/report.pdf',
      fileName: 'report.pdf',
      mediaType: 'application/pdf',
    );

    expect(saved?.name, 'report.pdf');
    expect(saved?.sizeBytes, 2048);
    expect(capturedCall?.method, 'saveFileFromPath');
  });

  test('forwards cancellation to the native directory writer', () async {
    await NativeFilePicker.cancelDirectorySave();

    expect(capturedCall?.method, 'cancelSaveFileToDirectory');
  });

  test('opens a cached file with the Android system viewer', () async {
    await NativeFilePicker.openFile(
      filePath: '/cache/report.docx',
      fileName: 'report.docx',
      mediaType:
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    );

    expect(capturedCall?.method, 'openFile');
    expect(capturedCall?.arguments, {
      'filePath': '/cache/report.docx',
      'fileName': 'report.docx',
      'mediaType':
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    });
  });
}
