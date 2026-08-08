import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yuanzuo_ai/app/models/feature_models.dart';
import 'package:yuanzuo_ai/app/pages/video_generate_page.dart';

void main() {
  testWidgets('simple mode exposes the video model selector', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: VideoModelSelectionSheet(
          feature: _feature(),
          selectedModels: const {
            'TEXT_GENERATION': 'text-fast',
            'IMAGE_GENERATION': 'image-quality',
            'VIDEO_GENERATION': 'video-grok',
          },
          capabilities: const ['VIDEO_GENERATION'],
        ),
      ),
    ));

    expect(
      find.byKey(const ValueKey<String>('video-model-VIDEO_GENERATION')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('video-model-TEXT_GENERATION')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey<String>('video-model-IMAGE_GENERATION')),
      findsNothing,
    );
  });

  testWidgets('expert mode exposes text image and video model selectors',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: VideoModelSelectionSheet(
          feature: _feature(),
          selectedModels: const {
            'TEXT_GENERATION': 'text-fast',
            'IMAGE_GENERATION': 'image-quality',
            'VIDEO_GENERATION': 'video-grok',
          },
          capabilities: const [
            'TEXT_GENERATION',
            'IMAGE_GENERATION',
            'VIDEO_GENERATION',
          ],
        ),
      ),
    ));

    expect(
      find.byKey(const ValueKey<String>('video-model-TEXT_GENERATION')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('video-model-IMAGE_GENERATION')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey<String>('video-model-VIDEO_GENERATION')),
      findsOneWidget,
    );
  });
}

FeatureDetail _feature() => const FeatureDetail(
      id: 'video.generate',
      title: 'AI视频生成',
      description: '',
      version: 1,
      resultType: 'video',
      rendererKey: 'video_generate',
      executionMode: 'ASYNC',
      inputSchema: {},
      uiSchema: {},
      outputSchema: {},
      config: {},
      modelPolicies: [
        ModelPolicy(
          capability: 'TEXT_GENERATION',
          defaultModelCode: 'text-fast',
          allowUserSelection: true,
          options: [
            ModelOption(
              code: 'text-fast',
              displayName: '快速分镜',
              description: '速度优先',
              isDefault: true,
              sourceType: 'RELAY',
              sourceName: 'Test',
              maxReferenceImages: null,
            ),
            ModelOption(
              code: 'text-quality',
              displayName: '高质量分镜',
              description: '质量优先',
              isDefault: false,
              sourceType: 'RELAY',
              sourceName: 'Test',
              maxReferenceImages: null,
            ),
          ],
        ),
        ModelPolicy(
          capability: 'IMAGE_GENERATION',
          defaultModelCode: 'image-quality',
          allowUserSelection: true,
          options: [
            ModelOption(
              code: 'image-quality',
              displayName: '资产图片模型',
              description: '支持参考图',
              isDefault: true,
              sourceType: 'RELAY',
              sourceName: 'Test',
              maxReferenceImages: 4,
            ),
          ],
        ),
        ModelPolicy(
          capability: 'VIDEO_GENERATION',
          defaultModelCode: 'video-grok',
          allowUserSelection: true,
          options: [
            ModelOption(
              code: 'video-grok',
              displayName: 'Grok Video',
              description: '多参考图',
              isDefault: true,
              sourceType: 'RELAY',
              sourceName: 'Test',
              maxReferenceImages: 7,
            ),
            ModelOption(
              code: 'video-sora',
              displayName: 'Sora',
              description: '单参考图',
              isDefault: false,
              sourceType: 'RELAY',
              sourceName: 'Test',
              maxReferenceImages: 1,
            ),
          ],
        ),
      ],
    );
