package com.aibox.features.video.generate;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.ArtifactDrafts;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureHandler;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.ImageGenerationRequest;
import com.aibox.feature.spi.ImageGenerationResponse;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.feature.spi.VideoGenerationRequest;
import com.aibox.feature.spi.VideoGenerationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public final class VideoGenerateFeatureHandler implements FeatureHandler {

    public static final String FEATURE_CODE = "video.generate";

    static final String TEXT_MODEL_ALIAS = "text.video-storyboard";
    static final String IMAGE_MODEL_ALIAS = "image.video-asset";
    static final String VIDEO_MODEL_ALIAS = "video.default";

    static final String SIMPLE_GENERATE = "SIMPLE_GENERATE";
    static final String BREAKDOWN_SCRIPT = "BREAKDOWN_SCRIPT";
    static final String SAVE_STORYBOARD = "SAVE_STORYBOARD";
    static final String GENERATE_ASSET_PRIMARY = "GENERATE_ASSET_PRIMARY";
    static final String GENERATE_CHARACTER_THREE_VIEW = "GENERATE_CHARACTER_THREE_VIEW";
    static final String GENERATE_VIDEO = "GENERATE_VIDEO";

    private static final int MAX_PROMPT_LENGTH = 4_000;
    private static final int MAX_SCRIPT_LENGTH = 20_000;
    private static final int MAX_SHOTS = 20;
    private static final int MAX_INPUT_IMAGES = 20;
    private static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;
    private static final Set<String> IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp");

    private final ObjectMapper objectMapper;

    public VideoGenerateFeatureHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String featureCode() {
        return FEATURE_CODE;
    }

    @Override
    public void validate(FeatureExecutionContext context) {
        switch (operation(context)) {
            case SIMPLE_GENERATE -> validateSimple(context);
            case BREAKDOWN_SCRIPT -> {
                requiredText(context, "script", MAX_SCRIPT_LENGTH, "请输入剧本");
                videoSettings(context);
            }
            case SAVE_STORYBOARD -> {
                requiredText(context, "script", MAX_SCRIPT_LENGTH, "请输入剧本");
                validateStoryboard(context.parameters().get("storyboard"), duration(context));
            }
            case GENERATE_ASSET_PRIMARY -> validatePrimaryAsset(context);
            case GENERATE_CHARACTER_THREE_VIEW -> validateThreeView(context);
            case GENERATE_VIDEO -> {
                videoSettings(context);
                validateStoryboard(context.parameters().get("storyboard"), duration(context));
                validateImageInputs(context, MAX_INPUT_IMAGES);
                validateFrameInputs(context);
            }
            default -> throw new FeatureValidationException("operation", "视频操作类型无效");
        }
    }

    @Override
    public FeatureExecutionResult execute(
            FeatureExecutionContext context,
            ModelGateway modelGateway
    ) {
        return switch (operation(context)) {
            case SIMPLE_GENERATE -> simpleVideo(context, modelGateway);
            case BREAKDOWN_SCRIPT -> breakdownScript(context, modelGateway);
            case SAVE_STORYBOARD -> saveStoryboard(context);
            case GENERATE_ASSET_PRIMARY -> generatePrimaryAsset(context, modelGateway);
            case GENERATE_CHARACTER_THREE_VIEW -> generateThreeView(context, modelGateway);
            case GENERATE_VIDEO -> expertVideo(context, modelGateway);
            default -> throw new FeatureValidationException("operation", "视频操作类型无效");
        };
    }

    private FeatureExecutionResult simpleVideo(
            FeatureExecutionContext context,
            ModelGateway gateway
    ) {
        VideoSettings settings = videoSettings(context);
        Map<String, Object> requestMetadata = videoRequestMetadata(context, "simple");
        String prompt = withFrameInstructions(
                context,
                requiredText(context, "prompt", MAX_PROMPT_LENGTH, "请输入视频描述")
        );
        VideoGenerationResponse response = gateway.generateVideo(new VideoGenerationRequest(
                context.tenantId(),
                context.runId(),
                VIDEO_MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.VIDEO_GENERATION),
                prompt,
                context.inputAssetIds(),
                settings.durationSeconds(),
                settings.aspectRatio(),
                settings.resolution(),
                1,
                requestMetadata
        ));
        requireSingleVideo(response);
        return FeatureExecutionResult.of(ArtifactDrafts.generatedVideos(
                "AI视频",
                response,
                generationMetadata(context, settings, "simple")
        ));
    }

    private FeatureExecutionResult breakdownScript(
            FeatureExecutionContext context,
            ModelGateway gateway
    ) {
        String script = requiredText(context, "script", MAX_SCRIPT_LENGTH, "请输入剧本");
        VideoSettings settings = videoSettings(context);
        TextGenerationResponse response = gateway.generateText(new TextGenerationRequest(
                context.tenantId(),
                context.runId(),
                TEXT_MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.TEXT_GENERATION),
                storyboardSystemPrompt(),
                """
                Target video duration: %d seconds.

                Script:
                %s
                """.formatted(settings.durationSeconds(), script),
                4_000,
                0.2,
                Map.of("featureCode", FEATURE_CODE, "operation", BREAKDOWN_SCRIPT)
        ));
        List<Map<String, Object>> shots = parseStoryboardResponse(
                response.text(), settings.durationSeconds()
        );
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", "video_storyboard");
        content.put("script", script);
        content.put("durationSeconds", settings.durationSeconds());
        content.put("shots", shots);
        Map<String, Object> metadata = providerMetadata(response);
        metadata.put("sourceType", "model");
        metadata.put("aspectRatio", settings.aspectRatio());
        metadata.put("resolution", settings.resolution());
        return FeatureExecutionResult.of(new ArtifactDraft(
                "video_storyboard",
                "视频分镜",
                "application/vnd.yuanzuo.video-storyboard+json",
                Map.copyOf(content),
                Map.copyOf(metadata)
        ));
    }

    private FeatureExecutionResult saveStoryboard(FeatureExecutionContext context) {
        int duration = duration(context);
        List<Map<String, Object>> shots =
                validateStoryboard(context.parameters().get("storyboard"), duration);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", "video_storyboard");
        content.put("script", requiredText(context, "script", MAX_SCRIPT_LENGTH, "请输入剧本"));
        content.put("durationSeconds", duration);
        content.put("shots", shots);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceType", "user");
        basedOnMetadata(context, metadata);
        return FeatureExecutionResult.of(new ArtifactDraft(
                "video_storyboard",
                "已编辑视频分镜",
                "application/vnd.yuanzuo.video-storyboard+json",
                Map.copyOf(content),
                Map.copyOf(metadata)
        ));
    }

    private FeatureExecutionResult generatePrimaryAsset(
            FeatureExecutionContext context,
            ModelGateway gateway
    ) {
        String type = assetType(context);
        String name = requiredText(context, "assetName", 120, "请输入资产名称");
        String description =
                requiredText(context, "assetDescription", 2_000, "请输入资产描述");
        String personality = text(context, "personality");
        ImageGenerationResponse response = gateway.generateImage(new ImageGenerationRequest(
                context.tenantId(),
                context.runId(),
                IMAGE_MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.IMAGE_GENERATION),
                primaryAssetPrompt(type, name, description, personality),
                List.of(),
                primaryAssetRatio(type),
                1,
                Map.of(
                        "featureCode", FEATURE_CODE,
                        "operation", GENERATE_ASSET_PRIMARY,
                        "assetType", type
                )
        ));
        requireSingleImage(response);
        Map<String, Object> metadata = assetMetadata(context, type, name, "REFERENCE");
        metadata.put("description", description);
        if ("CHARACTER".equals(type)) metadata.put("personality", personality);
        String title = "CHARACTER".equals(type) ? name + "角色参考图" : name;
        return FeatureExecutionResult.of(
                ArtifactDrafts.generatedImages(title, response, Map.copyOf(metadata))
        );
    }

    private FeatureExecutionResult generateThreeView(
            FeatureExecutionContext context,
            ModelGateway gateway
    ) {
        String name = requiredText(context, "assetName", 120, "请输入角色名称");
        String description =
                requiredText(context, "assetDescription", 2_000, "请输入角色描述");
        String personality =
                requiredText(context, "personality", 1_000, "请输入角色性格");
        UUID heroAssetId = context.inputAssetIds().get(0);
        ImageGenerationResponse response = gateway.generateImage(new ImageGenerationRequest(
                context.tenantId(),
                context.runId(),
                IMAGE_MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.IMAGE_GENERATION),
                threeViewPrompt(name, description, personality),
                List.of(heroAssetId),
                "16:9",
                1,
                Map.of(
                        "featureCode", FEATURE_CODE,
                        "operation", GENERATE_CHARACTER_THREE_VIEW,
                        "referenceImageCount", 1
                )
        ));
        requireSingleImage(response);
        Map<String, Object> metadata =
                assetMetadata(context, "CHARACTER", name, "THREE_VIEW");
        metadata.put("description", description);
        metadata.put("personality", personality);
        metadata.put("viewLayout", "FRONT_SIDE_BACK_LABELED");
        metadata.put("heroReferenceAssetId", heroAssetId.toString());
        return FeatureExecutionResult.of(ArtifactDrafts.generatedImages(
                name + "角色三视图",
                response,
                Map.copyOf(metadata)
        ));
    }

    private FeatureExecutionResult expertVideo(
            FeatureExecutionContext context,
            ModelGateway gateway
    ) {
        VideoSettings settings = videoSettings(context);
        List<Map<String, Object>> shots =
                validateStoryboard(context.parameters().get("storyboard"), settings.durationSeconds());
        String prompt = withFrameInstructions(context, compileVideoPrompt(
                shots,
                context.parameters().get("assetCatalog"),
                settings
        ));
        Map<String, Object> requestMetadata = videoRequestMetadata(context, "expert");
        requestMetadata.put("shotCount", shots.size());
        VideoGenerationResponse response = gateway.generateVideo(new VideoGenerationRequest(
                context.tenantId(),
                context.runId(),
                VIDEO_MODEL_ALIAS,
                context.selectedModelCode(ModelCapability.VIDEO_GENERATION),
                prompt,
                context.inputAssetIds(),
                settings.durationSeconds(),
                settings.aspectRatio(),
                settings.resolution(),
                1,
                Map.copyOf(requestMetadata)
        ));
        requireSingleVideo(response);
        Map<String, Object> metadata = generationMetadata(context, settings, "expert");
        metadata.put("shotCount", shots.size());
        metadata.put("referenceImageCount", context.inputAssetIds().size());
        metadata.put("storyboard", shots);
        return FeatureExecutionResult.of(ArtifactDrafts.generatedVideos(
                "AI视频",
                response,
                Map.copyOf(metadata)
        ));
    }

    private void validateSimple(FeatureExecutionContext context) {
        requiredText(context, "prompt", MAX_PROMPT_LENGTH, "请输入视频描述");
        videoSettings(context);
        validateImageInputs(context, 2);
        validateFrameInputs(context);
    }

    private void validatePrimaryAsset(FeatureExecutionContext context) {
        String type = assetType(context);
        requiredText(context, "assetName", 120, "请输入资产名称");
        requiredText(context, "assetDescription", 2_000, "请输入资产描述");
        if ("CHARACTER".equals(type)) {
            requiredText(context, "personality", 1_000, "请输入角色性格");
        }
        if (!context.inputAssetIds().isEmpty()) {
            throw new FeatureValidationException("inputAssetIds", "参考图生成不接收附件");
        }
    }

    private void validateThreeView(FeatureExecutionContext context) {
        if (!"CHARACTER".equals(assetType(context))) {
            throw new FeatureValidationException("assetType", "三视图仅支持角色资产");
        }
        requiredText(context, "assetName", 120, "请输入角色名称");
        requiredText(context, "assetDescription", 2_000, "请输入角色描述");
        requiredText(context, "personality", 1_000, "请输入角色性格");
        validateImageInputs(context, 1);
        if (context.inputAssetIds().size() != 1) {
            throw new FeatureValidationException(
                    "inputAssetIds",
                    "生成角色三视图需要一张角色参考图"
            );
        }
    }

    private void validateImageInputs(FeatureExecutionContext context, int maximum) {
        if (context.inputAssetIds().size() > maximum) {
            throw new FeatureValidationException(
                    "inputAssetIds",
                    "当前步骤最多使用 " + maximum + " 张图片"
            );
        }
        if (context.inputAssets().size() != context.inputAssetIds().size()) {
            throw new FeatureValidationException("inputAssetIds", "图片附件信息不完整");
        }
        for (InputAssetReference asset : context.inputAssets()) {
            String mediaType = asset.mediaType() == null
                    ? ""
                    : asset.mediaType().toLowerCase(Locale.ROOT);
            if (!IMAGE_TYPES.contains(mediaType)) {
                throw new FeatureValidationException(
                        "inputAssetIds",
                        "仅支持 PNG、JPG、JPEG 和 WebP 图片"
                );
            }
            if (asset.sizeBytes() <= 0 || asset.sizeBytes() > MAX_IMAGE_BYTES) {
                throw new FeatureValidationException(
                        "inputAssetIds",
                        "单张图片不能超过 20 MB"
                );
            }
        }
    }

    private static void validateFrameInputs(FeatureExecutionContext context) {
        UUID firstFrame = optionalUuid(context, "firstFrameAssetId");
        UUID lastFrame = optionalUuid(context, "lastFrameAssetId");
        if (firstFrame != null && firstFrame.equals(lastFrame)) {
            throw new FeatureValidationException(
                    "lastFrameAssetId",
                    "首帧和尾帧不能使用同一张图片"
            );
        }
        int index = 0;
        if (firstFrame != null) {
            requireFrameAt(context, firstFrame, index++, "firstFrameAssetId", "首帧");
        }
        if (lastFrame != null) {
            requireFrameAt(context, lastFrame, index, "lastFrameAssetId", "尾帧");
        }
    }

    private static void requireFrameAt(
            FeatureExecutionContext context,
            UUID expected,
            int index,
            String field,
            String label
    ) {
        if (context.inputAssetIds().size() <= index
                || !expected.equals(context.inputAssetIds().get(index))) {
            throw new FeatureValidationException(
                    field,
                    label + "图片与附件顺序不一致，请重新选择"
            );
        }
    }

    private static UUID optionalUuid(FeatureExecutionContext context, String field) {
        String value = text(context, field);
        if (value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new FeatureValidationException(field, field + " 格式无效");
        }
    }

    private List<Map<String, Object>> parseStoryboardResponse(
            String response,
            int durationSeconds
    ) {
        String json = jsonObject(response);
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject() || !root.has("shots")) {
                throw invalidModelResponse("分镜模型必须返回包含 shots 的 JSON 对象");
            }
            return validateStoryboard(objectMapper.convertValue(root.get("shots"), List.class), durationSeconds);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            if (exception instanceof ModelProviderException providerException) {
                throw providerException;
            }
            throw invalidModelResponse("分镜模型没有返回有效 JSON");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> validateStoryboard(Object raw, int durationSeconds) {
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > MAX_SHOTS) {
            throw new FeatureValidationException(
                    "storyboard",
                    "分镜数量必须在 1 到 " + MAX_SHOTS + " 之间"
            );
        }
        List<Map<String, Object>> shots = new ArrayList<>();
        double previousEnd = 0.0;
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            if (!(values.get(index) instanceof Map<?, ?> source)) {
                throw new FeatureValidationException("storyboard", "分镜条目格式无效");
            }
            Map<String, Object> shot = new LinkedHashMap<>();
            String id = mapText(source, "id");
            if (id.isBlank()) id = "shot-" + (index + 1);
            if (!ids.add(id)) {
                throw new FeatureValidationException("storyboard", "分镜标识不能重复");
            }
            double start = mapNumber(source, "startSecond");
            double end = mapNumber(source, "endSecond");
            if (start < 0
                    || end <= start
                    || end > durationSeconds
                    || Math.abs(start - previousEnd) > 0.001
                    || (index == 0 && start != 0.0)) {
                throw new FeatureValidationException(
                        "storyboard",
                        "分镜时间必须递增、不可重叠且不能超过总时长"
                );
            }
            String description = mapRequiredText(source, "shotDescription", 1_000);
            String action = mapRequiredText(source, "visualAction", 500);
            String shotSize = mapOptionalText(source, "shotSize", 80);
            String cameraMovement = mapOptionalText(source, "cameraMovement", 120);
            String environment = mapOptionalText(source, "environment", 500);
            String continuity = mapOptionalText(source, "continuity", 500);
            List<String> assetRefs = stringList(source.get("assetRefs"), 20);

            shot.put("id", id);
            shot.put("startSecond", start);
            shot.put("endSecond", end);
            shot.put("shotDescription", description);
            shot.put("visualAction", action);
            shot.put("shotSize", shotSize);
            shot.put("cameraMovement", cameraMovement);
            shot.put("environment", environment);
            shot.put("continuity", continuity);
            shot.put("assetRefs", assetRefs);
            shots.add(Map.copyOf(shot));
            previousEnd = end;
        }
        if (Math.abs(previousEnd - durationSeconds) > 0.001) {
            throw new FeatureValidationException(
                    "storyboard",
                    "最后一个分镜必须结束在视频总时长"
            );
        }
        return List.copyOf(shots);
    }

    private String compileVideoPrompt(
            List<Map<String, Object>> shots,
            Object assetCatalog,
            VideoSettings settings
    ) {
        try {
            return """
                    Create one coherent short video from the complete storyboard below.
                    Respect the time order, keep character identity and wardrobe consistent,
                    preserve referenced scenes and props, and avoid captions, watermarks or split screens.

                    Duration: %d seconds
                    Aspect ratio: %s
                    Resolution: %s

                    Assets:
                    %s

                    Storyboard:
                    %s
                    """.formatted(
                    settings.durationSeconds(),
                    settings.aspectRatio(),
                    settings.resolution(),
                    objectMapper.writeValueAsString(
                            assetCatalog instanceof List<?> ? assetCatalog : List.of()
                    ),
                    objectMapper.writeValueAsString(shots)
            );
        } catch (JsonProcessingException exception) {
            throw new FeatureValidationException("storyboard", "无法序列化视频分镜");
        }
    }

    private static String storyboardSystemPrompt() {
        return """
                You are a professional short-video storyboard planner.
                Split the user script into chronological shots that fit exactly within the target duration.
                Each shot must contain a concrete visual description and visible subject action.
                Return JSON only, without Markdown fences or extra fields:
                {
                  "shots":[
                    {
                      "id":"shot-1",
                      "startSecond":0,
                      "endSecond":3,
                      "shotDescription":"visible composition and subjects",
                      "visualAction":"visible action",
                      "shotSize":"wide|full|medium|close|extreme_close",
                      "cameraMovement":"static|push|pull|pan|track|orbit",
                      "environment":"setting and lighting",
                      "continuity":"continuity with the previous shot",
                      "assetRefs":[]
                    }
                  ]
                }
                The first shot must start at 0. Shots must not overlap and the final shot must end
                at the target duration. Use at most 20 shots.
                """;
    }

    private static String withFrameInstructions(
            FeatureExecutionContext context,
            String prompt
    ) {
        boolean hasFirstFrame = !text(context, "firstFrameAssetId").isBlank();
        boolean hasLastFrame = !text(context, "lastFrameAssetId").isBlank();
        if (hasFirstFrame && hasLastFrame) {
            return """
                    Treat the first supplied image as the exact opening frame and the second supplied
                    image as the exact final frame. Build a coherent motion transition between them.

                    %s
                    """.formatted(prompt);
        }
        if (hasFirstFrame) {
            return """
                    Use the supplied image as the exact opening frame and continue naturally from it.

                    %s
                    """.formatted(prompt);
        }
        if (hasLastFrame) {
            return """
                    Use the supplied image as the exact final frame and build the preceding motion
                    so the video ends on it.

                    %s
                    """.formatted(prompt);
        }
        return prompt;
    }

    private static String primaryAssetPrompt(
            String type,
            String name,
            String description,
            String personality
    ) {
        return switch (type) {
            case "CHARACTER" -> """
                    Create a production-ready video character reference image.
                    Show exactly one front-facing character only, full-body or medium full-body,
                    looking straight toward the camera in a neutral natural pose and expression.
                    Use even natural lighting and a clean plain background. No side view, no back view,
                    no turnaround sheet, no extra panels, no text, no labels, no collage,
                    no split screen and no watermark.
                    Character name: %s
                    Appearance: %s
                    Personality expressed subtly through posture and styling: %s
                    """.formatted(name, description, personality);
            case "SCENE" -> """
                    Create one clean cinematic environment reference image for video generation.
                    No characters unless explicitly requested, no text, no labels, no collage.
                    Scene name: %s
                    Description: %s
                    """.formatted(name, description);
            case "PROP" -> """
                    Create one clean production reference image of a single prop.
                    Show the full object clearly on a simple background, no text, no labels, no collage.
                    Prop name: %s
                    Description: %s
                    """.formatted(name, description);
            default -> """
                    Create one clean visual reference image for video production.
                    No text, labels, collage or watermark.
                    Asset name: %s
                    Description: %s
                    """.formatted(name, description);
        };
    }

    private static String threeViewPrompt(
            String name,
            String description,
            String personality
    ) {
        return """
                Using the supplied character image as the strict identity and wardrobe reference,
                create one horizontal character turnaround sheet divided into exactly three equal panels.
                Left panel: front view with the Chinese label "正面".
                Center panel: side view with the Chinese label "侧面".
                Right panel: back view with the Chinese label "背面".
                Show the same full-body character, proportions, face, hair, clothing, accessories and colors
                in every panel. Neutral standing pose, even studio lighting, clean light background.
                No extra characters, no extra panels, no watermark.
                Character name: %s
                Description: %s
                Personality: %s
                """.formatted(name, description, personality);
    }

    private static String primaryAssetRatio(String type) {
        return switch (type) {
            case "CHARACTER" -> "9:16";
            case "SCENE" -> "16:9";
            default -> "1:1";
        };
    }

    private static Map<String, Object> generationMetadata(
            FeatureExecutionContext context,
            VideoSettings settings,
            String mode
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", mode);
        metadata.put("durationSeconds", settings.durationSeconds());
        metadata.put("aspectRatio", settings.aspectRatio());
        metadata.put("resolution", settings.resolution());
        addFrameMetadata(context, metadata);
        basedOnMetadata(context, metadata);
        return metadata;
    }

    private static Map<String, Object> videoRequestMetadata(
            FeatureExecutionContext context,
            String mode
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("featureCode", FEATURE_CODE);
        metadata.put("mode", mode);
        addFrameMetadata(context, metadata);
        return metadata;
    }

    private static void addFrameMetadata(
            FeatureExecutionContext context,
            Map<String, Object> metadata
    ) {
        String firstFrameAssetId = text(context, "firstFrameAssetId");
        String lastFrameAssetId = text(context, "lastFrameAssetId");
        if (!firstFrameAssetId.isBlank()) {
            metadata.put("firstFrameAssetId", firstFrameAssetId);
        }
        if (!lastFrameAssetId.isBlank()) {
            metadata.put("lastFrameAssetId", lastFrameAssetId);
        }
        int frameCount = (firstFrameAssetId.isBlank() ? 0 : 1)
                + (lastFrameAssetId.isBlank() ? 0 : 1);
        if (frameCount > 0) {
            metadata.put("frameInputCount", frameCount);
            metadata.put("frameInputMode", frameCount == 2 ? "FIRST_LAST" : "SINGLE_FRAME");
        }
    }

    private static Map<String, Object> assetMetadata(
            FeatureExecutionContext context,
            String type,
            String name,
            String role
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("assetType", type);
        metadata.put("assetName", name);
        metadata.put("assetRole", role);
        Object creativeAssetId = context.parameters().get("creativeAssetId");
        if (creativeAssetId != null && !creativeAssetId.toString().isBlank()) {
            metadata.put("creativeAssetId", creativeAssetId.toString());
        }
        basedOnMetadata(context, metadata);
        return metadata;
    }

    private static Map<String, Object> providerMetadata(TextGenerationResponse response) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (response.provider() != null) metadata.put("provider", response.provider());
        if (response.model() != null) metadata.put("model", response.model());
        if (response.providerRequestId() != null) {
            metadata.put("providerRequestId", response.providerRequestId());
        }
        return metadata;
    }

    private static void basedOnMetadata(
            FeatureExecutionContext context,
            Map<String, Object> metadata
    ) {
        if (context.baseArtifact() == null) return;
        metadata.put("basedOnArtifactId", context.baseArtifact().id().toString());
        metadata.put("basedOnVersion", context.baseArtifact().versionNumber());
    }

    private static VideoSettings videoSettings(FeatureExecutionContext context) {
        return new VideoSettings(
                duration(context),
                requiredText(context, "aspectRatio", 40, "请选择视频比例"),
                requiredText(context, "resolution", 40, "请选择视频分辨率")
        );
    }

    private static int duration(FeatureExecutionContext context) {
        Object value = context.parameters().get("durationSeconds");
        int duration;
        if (value instanceof Number number) {
            duration = number.intValue();
        } else {
            try {
                duration = Integer.parseInt(value == null ? "" : value.toString());
            } catch (NumberFormatException exception) {
                throw new FeatureValidationException("durationSeconds", "请选择视频时长");
            }
        }
        if (duration < 1 || duration > 30) {
            throw new FeatureValidationException("durationSeconds", "视频时长必须在 1 到 30 秒之间");
        }
        return duration;
    }

    private static String operation(FeatureExecutionContext context) {
        return text(context, "operation").toUpperCase(Locale.ROOT);
    }

    private static String assetType(FeatureExecutionContext context) {
        String type = text(context, "assetType").toUpperCase(Locale.ROOT);
        if (!Set.of("CHARACTER", "SCENE", "PROP", "UNCLASSIFIED").contains(type)) {
            throw new FeatureValidationException("assetType", "资产类型无效");
        }
        return type;
    }

    private static String requiredText(
            FeatureExecutionContext context,
            String field,
            int maximum,
            String emptyMessage
    ) {
        String value = text(context, field);
        if (value.isBlank()) throw new FeatureValidationException(field, emptyMessage);
        if (value.length() > maximum) {
            throw new FeatureValidationException(field, field + " 超过长度限制");
        }
        return value;
    }

    private static String text(FeatureExecutionContext context, String field) {
        Object value = context.parameters().get(field);
        return value == null ? "" : value.toString().trim();
    }

    private static String mapText(Map<?, ?> map, String field) {
        Object value = map.get(field);
        return value == null ? "" : value.toString().trim();
    }

    private static String mapRequiredText(
            Map<?, ?> map,
            String field,
            int maximum
    ) {
        String value = mapText(map, field);
        if (value.isBlank() || value.length() > maximum) {
            throw new FeatureValidationException("storyboard", "分镜字段 " + field + " 无效");
        }
        return value;
    }

    private static String mapOptionalText(
            Map<?, ?> map,
            String field,
            int maximum
    ) {
        String value = mapText(map, field);
        if (value.length() > maximum) {
            throw new FeatureValidationException("storyboard", "分镜字段 " + field + " 过长");
        }
        return value;
    }

    private static double mapNumber(Map<?, ?> map, String field) {
        Object value = map.get(field);
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(value == null ? "" : value.toString());
        } catch (NumberFormatException exception) {
            throw new FeatureValidationException("storyboard", "分镜时间字段无效");
        }
    }

    private static List<String> stringList(Object raw, int maximum) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values) || values.size() > maximum) {
            throw new FeatureValidationException("storyboard", "分镜资产引用数量无效");
        }
        return values.stream()
                .filter(value -> value != null && !value.toString().isBlank())
                .map(value -> value.toString().trim())
                .distinct()
                .toList();
    }

    private static String jsonObject(String response) {
        String value = response == null ? "" : response.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw invalidModelResponse("分镜模型响应中没有 JSON 对象");
        }
        return value.substring(start, end + 1);
    }

    private static void requireSingleImage(ImageGenerationResponse response) {
        if (response.images().size() != 1) {
            throw new ModelProviderException(
                    "PROVIDER_INVALID_RESPONSE",
                    "图片模型必须且仅返回一张图片",
                    false
            );
        }
    }

    private static void requireSingleVideo(VideoGenerationResponse response) {
        if (response.videos().size() != 1) {
            throw new ModelProviderException(
                    "PROVIDER_INVALID_RESPONSE",
                    "视频模型必须且仅返回一个视频",
                    false
            );
        }
    }

    private static ModelProviderException invalidModelResponse(String message) {
        return new ModelProviderException("MODEL_INVALID_STRUCTURED_RESPONSE", message, false);
    }

    private record VideoSettings(
            int durationSeconds,
            String aspectRatio,
            String resolution
    ) {
    }
}
