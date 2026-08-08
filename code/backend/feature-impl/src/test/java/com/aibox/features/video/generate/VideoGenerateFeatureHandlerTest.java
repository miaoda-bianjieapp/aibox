package com.aibox.features.video.generate;

import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.GeneratedImage;
import com.aibox.feature.spi.GeneratedVideo;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.VideoGenerationRequest;
import com.aibox.feature.spi.VideoGenerationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VideoGenerateFeatureHandlerTest {

    private final VideoGenerateFeatureHandler handler =
            new VideoGenerateFeatureHandler(new ObjectMapper());

    @Test
    void breaksScriptIntoAnEditableStoryboardUsingSelectedTextDeployment() {
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                captured.set(request);
                return new TextGenerationResponse(
                        """
                        {"shots":[
                          {"id":"shot-1","startSecond":0,"endSecond":4,
                           "shotDescription":"清晨的房间","visualAction":"人物推开窗户"},
                          {"id":"shot-2","startSecond":4,"endSecond":8,
                           "shotDescription":"城市街道","visualAction":"人物走入阳光"}
                        ]}
                        """,
                        "test-provider", "storyboard-model", "request-1", 1, 2
                );
            }
        };

        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "expert",
                        "operation", VideoGenerateFeatureHandler.BREAKDOWN_SCRIPT,
                        "script", "一个人清晨走出房间，走向城市街道。",
                        "durationSeconds", 8,
                        "aspectRatio", "16:9",
                        "resolution", "720p"
                ),
                List.of(),
                Map.of("TEXT_GENERATION", "text-storyboard")
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertEquals("text-storyboard", captured.get().deploymentCode());
        assertEquals("video_storyboard", result.artifacts().get(0).kind());
        assertEquals(
                2,
                ((List<?>) result.artifacts().get(0).content().get("shots")).size()
        );
    }

    @Test
    void requiresStoryboardToStartAtZeroAndCoverTheFullDuration() {
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "expert",
                        "operation", VideoGenerateFeatureHandler.SAVE_STORYBOARD,
                        "script", "测试剧本",
                        "durationSeconds", 8,
                        "aspectRatio", "16:9",
                        "resolution", "720p",
                        "storyboard", List.of(Map.of(
                                "id", "shot-1",
                                "startSecond", 1,
                                "endSecond", 7,
                                "shotDescription", "画面",
                                "visualAction", "动作"
                        ))
                ),
                List.of(),
                Map.of()
        );

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void rejectsStoryboardTimelineGaps() {
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "expert",
                        "operation", VideoGenerateFeatureHandler.SAVE_STORYBOARD,
                        "script", "测试剧本",
                        "durationSeconds", 8,
                        "aspectRatio", "16:9",
                        "resolution", "720p",
                        "storyboard", List.of(
                                Map.of(
                                        "id", "shot-1",
                                        "startSecond", 0,
                                        "endSecond", 3,
                                        "shotDescription", "第一个画面",
                                        "visualAction", "角色转身"
                                ),
                                Map.of(
                                        "id", "shot-2",
                                        "startSecond", 4,
                                        "endSecond", 8,
                                        "shotDescription", "第二个画面",
                                        "visualAction", "角色向前走"
                                )
                        )
                ),
                List.of(),
                Map.of()
        );

        assertThrows(FeatureValidationException.class, () -> handler.validate(context));
    }

    @Test
    void createsCharacterPrimaryReferenceImageUsingSelectedDeployment() {
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new AssertionError("text generation is not expected");
            }

            @Override
            public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
                captured.set(request);
                return new ImageGenerationResponse(
                        List.of(new GeneratedImage(
                                null, "image/png", null, new byte[]{1, 2, 3}
                        )),
                        "test-provider", "image-model", "image-request", 1, 1
                );
            }
        };
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "expert",
                        "operation", VideoGenerateFeatureHandler.GENERATE_ASSET_PRIMARY,
                        "assetType", "CHARACTER",
                        "assetName", "小林",
                        "assetDescription", "短发、蓝色外套、黑色长裤",
                        "personality", "沉着、善于观察"
                ),
                List.of(),
                Map.of("IMAGE_GENERATION", "image-video-assets")
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertEquals("image-video-assets", captured.get().deploymentCode());
        assertEquals("9:16", captured.get().size());
        assertTrue(captured.get().prompt().contains("front-facing character"));
        assertTrue(captured.get().prompt().contains("No side view"));
        assertEquals("REFERENCE", result.artifacts().get(0).metadata().get("assetRole"));
        assertEquals(1, result.artifacts().get(0).outputAssets().size());
    }

    @Test
    void preservesFirstAndLastFrameOrderInVideoRequestMetadata() {
        UUID firstFrameId = UUID.randomUUID();
        UUID lastFrameId = UUID.randomUUID();
        AtomicReference<VideoGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new AssertionError("text generation is not expected");
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                captured.set(request);
                return new VideoGenerationResponse(
                        List.of(new GeneratedVideo(
                                null, "video.mp4", "video/mp4", new byte[]{4, 5, 6}
                        )),
                        "test-provider", "video-model", "video-request", 1, 1
                );
            }
        };
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "simple",
                        "operation", VideoGenerateFeatureHandler.SIMPLE_GENERATE,
                        "prompt", "人物从门口走到窗边",
                        "durationSeconds", 8,
                        "aspectRatio", "16:9",
                        "resolution", "720p",
                        "firstFrameAssetId", firstFrameId.toString(),
                        "lastFrameAssetId", lastFrameId.toString()
                ),
                List.of(firstFrameId, lastFrameId),
                Map.of("VIDEO_GENERATION", "video-model"),
                List.of(
                        new InputAssetReference(firstFrameId, "first.png", "image/png", 3),
                        new InputAssetReference(lastFrameId, "last.png", "image/png", 3)
                )
        );

        handler.validate(context);
        handler.execute(context, gateway);

        assertEquals(List.of(firstFrameId, lastFrameId), captured.get().inputAssetIds());
        assertEquals(firstFrameId.toString(), captured.get().metadata().get("firstFrameAssetId"));
        assertEquals(lastFrameId.toString(), captured.get().metadata().get("lastFrameAssetId"));
        assertEquals("FIRST_LAST", captured.get().metadata().get("frameInputMode"));
        assertTrue(captured.get().prompt().contains("exact opening frame"));
        assertTrue(captured.get().prompt().contains("exact final frame"));
    }

    @Test
    void createsCharacterThreeViewFromThePrimaryReferenceImage() {
        UUID heroAssetId = UUID.randomUUID();
        AtomicReference<ImageGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new AssertionError("text generation is not expected");
            }

            @Override
            public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
                captured.set(request);
                return new ImageGenerationResponse(
                        List.of(new GeneratedImage(
                                null, "image/png", null, new byte[]{1, 2, 3}
                        )),
                        "test-provider", "image-model", "image-request", 1, 1
                );
            }
        };
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "expert",
                        "operation", VideoGenerateFeatureHandler.GENERATE_CHARACTER_THREE_VIEW,
                        "assetType", "CHARACTER",
                        "assetName", "小林",
                        "assetDescription", "短发、蓝色外套、黑色长裤",
                        "personality", "沉着、善于观察"
                ),
                List.of(heroAssetId),
                Map.of("IMAGE_GENERATION", "image-video-assets"),
                List.of(new InputAssetReference(
                        heroAssetId, "hero.png", "image/png", 3
                ))
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertEquals(List.of(heroAssetId), captured.get().inputAssetIds());
        assertTrue(captured.get().prompt().contains("正面"));
        assertTrue(captured.get().prompt().contains("侧面"));
        assertTrue(captured.get().prompt().contains("背面"));
        assertEquals("THREE_VIEW", result.artifacts().get(0).metadata().get("assetRole"));
        assertEquals(1, result.artifacts().get(0).outputAssets().size());
    }

    @Test
    void sendsTheCompleteStoryboardAndReferencedAssetsInOneVideoRequest() {
        UUID characterAssetId = UUID.randomUUID();
        UUID sceneAssetId = UUID.randomUUID();
        AtomicReference<VideoGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new AssertionError("text generation is not expected");
            }

            @Override
            public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
                captured.set(request);
                return new VideoGenerationResponse(
                        List.of(new GeneratedVideo(
                                null, "video.mp4", "video/mp4", new byte[]{4, 5, 6}
                        )),
                        "test-provider", "video-model", "video-request", 1, 1
                );
            }
        };
        List<Map<String, Object>> storyboard = List.of(
                Map.of(
                        "id", "shot-1",
                        "startSecond", 0,
                        "endSecond", 4,
                        "shotDescription", "室内中景",
                        "visualAction", "角色转身",
                        "assetRefs", List.of("character-1")
                ),
                Map.of(
                        "id", "shot-2",
                        "startSecond", 4,
                        "endSecond", 8,
                        "shotDescription", "街道远景",
                        "visualAction", "角色向前走",
                        "assetRefs", List.of("scene-1")
                )
        );
        FeatureExecutionContext context = context(
                Map.of(
                        "mode", "expert",
                        "operation", VideoGenerateFeatureHandler.GENERATE_VIDEO,
                        "durationSeconds", 8,
                        "aspectRatio", "16:9",
                        "resolution", "720p",
                        "storyboard", storyboard,
                        "assetCatalog", List.of(
                                Map.of("id", "character-1", "name", "角色"),
                                Map.of("id", "scene-1", "name", "街道")
                        )
                ),
                List.of(characterAssetId, sceneAssetId),
                Map.of("VIDEO_GENERATION", "video-model"),
                List.of(
                        new InputAssetReference(
                                characterAssetId, "character.png", "image/png", 3
                        ),
                        new InputAssetReference(
                                sceneAssetId, "scene.png", "image/png", 3
                        )
                )
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway);

        assertEquals(
                List.of(characterAssetId, sceneAssetId),
                captured.get().inputAssetIds()
        );
        assertTrue(captured.get().prompt().contains("\"shot-1\""));
        assertTrue(captured.get().prompt().contains("\"shot-2\""));
        assertEquals("video", result.artifacts().get(0).kind());
        assertEquals(1, result.artifacts().get(0).outputAssets().size());
    }

    private static FeatureExecutionContext context(
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            Map<String, String> selectedModels
    ) {
        return context(parameters, inputAssetIds, selectedModels, List.of());
    }

    private static FeatureExecutionContext context(
            Map<String, Object> parameters,
            List<UUID> inputAssetIds,
            Map<String, String> selectedModels,
            List<InputAssetReference> inputAssets
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                VideoGenerateFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                inputAssetIds,
                inputAssets,
                selectedModels,
                null,
                null
        );
    }
}
