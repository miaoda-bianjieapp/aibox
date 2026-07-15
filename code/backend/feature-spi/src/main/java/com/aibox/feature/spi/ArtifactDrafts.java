package com.aibox.feature.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public final class ArtifactDrafts {

    private ArtifactDrafts() {
    }

    public static ArtifactDraft richText(String title, String markdown, Map<String, Object> metadata) {
        return new ArtifactDraft(
                "rich_text", title, "text/markdown",
                Map.of("format", "markdown", "text", markdown), metadata
        );
    }

    public static ArtifactDraft transcript(
            String title,
            String text,
            List<Map<String, Object>> segments,
            Map<String, Object> metadata
    ) {
        return new ArtifactDraft(
                "transcript", title, "application/vnd.yuanzuo.transcript+json",
                Map.of("text", text, "segments", segments == null ? List.of() : segments), metadata
        );
    }

    public static ArtifactDraft image(
            String title,
            UUID assetId,
            String url,
            String base64,
            Map<String, Object> metadata
    ) {
        return new ArtifactDraft(
                "image", title, "image/png", compact(Map.of(
                        "assetId", assetId == null ? "" : assetId.toString(),
                        "url", url == null ? "" : url,
                        "base64", base64 == null ? "" : base64
                )), metadata
        );
    }

    public static ArtifactDraft generatedImage(
            String title,
            String fileName,
            String mediaType,
            byte[] content,
            Map<String, Object> metadata
    ) {
        return new ArtifactDraft(
                "image", title, mediaType, Map.of(), metadata,
                List.of(new OutputAssetDraft("assetId", fileName, mediaType, content))
        );
    }

    public static ArtifactDraft generatedImages(
            String title,
            ImageGenerationResponse response,
            Map<String, Object> metadata
    ) {
        if (response.images().isEmpty()) {
            throw new IllegalArgumentException("image generation response has no images");
        }
        String field = response.images().size() == 1 ? "assetId" : "assetIds";
        List<OutputAssetDraft> outputs = new ArrayList<>();
        List<String> revisedPrompts = new ArrayList<>();
        for (int index = 0; index < response.images().size(); index++) {
            GeneratedImage image = response.images().get(index);
            String mediaType = image.mediaType() == null || image.mediaType().isBlank()
                    ? "image/png"
                    : image.mediaType();
            outputs.add(new OutputAssetDraft(
                    field,
                    "generated-" + (index + 1) + extension(mediaType),
                    mediaType,
                    image.content()
            ));
            if (image.revisedPrompt() != null && !image.revisedPrompt().isBlank()) {
                revisedPrompts.add(image.revisedPrompt());
            }
        }
        Map<String, Object> content = revisedPrompts.isEmpty()
                ? Map.of()
                : Map.of("revisedPrompts", List.copyOf(revisedPrompts));
        Map<String, Object> fullMetadata = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        fullMetadata.put("provider", response.provider());
        fullMetadata.put("model", response.model());
        if (response.providerRequestId() != null) {
            fullMetadata.put("providerRequestId", response.providerRequestId());
        }
        return new ArtifactDraft(
                "image", title, outputs.get(0).mediaType(), content,
                Map.copyOf(fullMetadata), outputs
        );
    }

    public static ArtifactDraft generatedMedia(
            String kind,
            String title,
            String fileName,
            String mediaType,
            byte[] content,
            Map<String, Object> metadata
    ) {
        return new ArtifactDraft(
                kind, title, mediaType, Map.of("name", fileName), metadata,
                List.of(new OutputAssetDraft("assetId", fileName, mediaType, content))
        );
    }

    public static ArtifactDraft media(
            String kind,
            String title,
            String mimeType,
            UUID assetId,
            String name,
            Map<String, Object> metadata
    ) {
        return new ArtifactDraft(
                kind, title, mimeType,
                Map.of("assetId", assetId.toString(), "name", name == null ? title : name), metadata
        );
    }

    private static Map<String, Object> compact(Map<String, Object> source) {
        return source.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().toString().isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String extension(String mediaType) {
        return switch (mediaType) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }
}
