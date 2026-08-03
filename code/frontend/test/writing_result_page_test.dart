import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/pages/writing_result_page.dart';

void main() {
  testWidgets('plain text artifacts preserve markdown-like source characters',
      (tester) async {
    const translatedText =
        '# Heading-like text\n\n#include <stdio.h>\nhttps://example.com';
    final artifact = ArtifactView(
      id: 'artifact-1',
      taskId: 'task-1',
      runId: 'run-1',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'rich_text',
      title: '翻译结果',
      mimeType: 'text/plain',
      content: const {
        'format': 'plain_text',
        'text': translatedText,
      },
      metadata: const {},
      createdAt: DateTime(2026, 7, 17, 14, 24),
    );

    await tester.pumpWidget(MaterialApp(
      home: ArtifactResultPage(
        artifact: artifact,
        rendererKey: 'rich_text_editor',
      ),
    ));

    expect(find.text(translatedText), findsOneWidget);
    expect(find.text('Heading-like text'), findsNothing);
  });

  testWidgets('image artifacts render on a transparency backdrop',
      (tester) async {
    final artifact = ArtifactView(
      id: 'artifact-image',
      taskId: 'task-image',
      runId: 'run-image',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'image',
      title: '透明背景抠图',
      mimeType: 'image/png',
      content: const {
        'base64':
            'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAF/gL+XH0hWQAAAABJRU5ErkJggg==',
      },
      metadata: const {},
      createdAt: DateTime(2026, 7, 17, 15, 34),
    );

    await tester.pumpWidget(MaterialApp(
      home: ArtifactResultPage(
        artifact: artifact,
        rendererKey: 'image',
      ),
    ));

    expect(
      find.byKey(const ValueKey('image-transparency-backdrop')),
      findsOneWidget,
    );
  });

  testWidgets('file artifacts expose the generic save action', (tester) async {
    final artifact = ArtifactView(
      id: 'artifact-file',
      taskId: 'task-file',
      runId: 'run-file',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'file',
      title: '合同译文',
      mimeType:
          'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      content: const {
        'assetId': 'translated-file',
        'name': 'contract-en.docx',
        'sourceAssetId': 'source-file',
      },
      metadata: const {},
      assets: [
        AssetView(
          id: 'translated-file',
          name: 'contract-en.docx',
          mediaType:
              'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
          sizeBytes: 2048,
          createdAt: DateTime(2026, 7, 27, 11),
          origin: 'MODEL_OUTPUT',
          category: 'DOCUMENT',
        ),
      ],
      createdAt: DateTime(2026, 7, 27, 11),
    );

    await tester.pumpWidget(MaterialApp(
      home: ArtifactResultPage(
        artifact: artifact,
        rendererKey: 'file',
      ),
    ));

    expect(find.byTooltip('下载文件'), findsOneWidget);
    expect(find.text('contract-en.docx'), findsOneWidget);
  });

  testWidgets('transcript artifacts show speakers timestamps and supplement',
      (tester) async {
    final artifact = ArtifactView(
      id: 'artifact-transcript',
      taskId: 'task-transcript',
      runId: 'run-transcript',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'transcript',
      title: '产品会议转写',
      mimeType: 'application/vnd.yuanzuo.transcript+json',
      content: const {
        'text': '欢迎参加会议。',
        'timestampMode': 'segment',
        'speakerDiarization': true,
        'postProcess': 'summary',
        'segments': [
          {
            'startMs': 61000,
            'endMs': 65000,
            'speaker': 'A',
            'text': '欢迎参加会议。',
          }
        ],
        'supplement': {
          'type': 'summary',
          'status': 'SUCCEEDED',
          'format': 'markdown',
          'text': '核心摘要内容',
        },
      },
      metadata: const {},
      createdAt: DateTime(2026, 7, 31, 17),
    );

    await tester.pumpWidget(MaterialApp(
      home: ArtifactResultPage(
        artifact: artifact,
        rendererKey: 'transcript',
      ),
    ));

    expect(find.text('01:01'), findsOneWidget);
    expect(find.text('说话人 A'), findsOneWidget);
    expect(find.text('逐字稿'), findsOneWidget);
    expect(find.text('摘要'), findsOneWidget);

    await tester.tap(find.text('摘要'));
    await tester.pumpAndSettle();

    expect(find.text('核心摘要内容'), findsOneWidget);
  });

  testWidgets('audio artifacts use the generic preview and download actions',
      (tester) async {
    final artifact = ArtifactView(
      id: 'artifact-audio',
      taskId: 'task-audio',
      runId: 'run-audio',
      parentArtifactId: null,
      versionNumber: 1,
      kind: 'audio',
      title: '采访录音 人声降噪',
      mimeType: 'audio/mpeg',
      content: const {
        'assetId': 'enhanced-audio',
        'name': 'interview-enhanced.mp3',
      },
      metadata: const {},
      assets: [
        AssetView(
          id: 'enhanced-audio',
          name: 'interview-enhanced.mp3',
          mediaType: 'audio/mpeg',
          sizeBytes: 4096,
          createdAt: DateTime(2026, 8, 3, 9, 48),
          origin: 'MODEL_OUTPUT',
          category: 'AUDIO',
        ),
      ],
      createdAt: DateTime(2026, 8, 3, 9, 48),
    );

    await tester.pumpWidget(MaterialApp(
      home: ArtifactResultPage(
        artifact: artifact,
        rendererKey: 'audio',
      ),
    ));

    expect(find.text('音频成果'), findsOneWidget);
    expect(find.text('interview-enhanced.mp3'), findsOneWidget);
    expect(find.text('打开预览'), findsOneWidget);
    expect(find.text('下载'), findsOneWidget);
  });
}
