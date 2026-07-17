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
}
