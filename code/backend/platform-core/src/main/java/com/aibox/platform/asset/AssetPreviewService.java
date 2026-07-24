package com.aibox.platform.asset;

import com.aibox.platform.common.PlatformException;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

@Service
public class AssetPreviewService {

    private static final int MAX_TEXT_PREVIEW_CHARACTERS = 2_000_000;

    private final AssetService assetService;

    public AssetPreviewService(AssetService assetService) {
        this.assetService = assetService;
    }

    @Transactional(readOnly = true)
    public PreviewDescriptor preview(UUID assetId) {
        AssetService.AssetStoredFile stored = assetService.openForPreview(assetId);
        AssetService.AssetView asset = stored.asset();
        String extension = extension(asset.name());
        String contentUrl = "/api/v1/assets/" + asset.id() + "/content";
        return switch (asset.category()) {
            case "IMAGE" -> new PreviewDescriptor("IMAGE", asset.mediaType(), contentUrl, null, false);
            case "VIDEO" -> new PreviewDescriptor("VIDEO", asset.mediaType(), contentUrl, null, false);
            case "AUDIO" -> new PreviewDescriptor("AUDIO", asset.mediaType(), contentUrl, null, false);
            case "DOCUMENT" -> documentPreview(asset, stored.path(), extension, contentUrl);
            default -> throw new PlatformException(
                    "ASSET_PREVIEW_UNSUPPORTED", "This file cannot be previewed"
            );
        };
    }

    private PreviewDescriptor documentPreview(
            AssetService.AssetView asset,
            Path path,
            String extension,
            String contentUrl
    ) {
        if (".pdf".equals(extension) || "application/pdf".equalsIgnoreCase(asset.mediaType())) {
            return new PreviewDescriptor("PDF", "application/pdf", contentUrl, null, false);
        }
        if (isOffice(extension)) {
            TextPreview text = extractOffice(path);
            return new PreviewDescriptor("TEXT", "text/plain", null, text.content(), text.truncated());
        }
        TextPreview text = readText(path);
        return new PreviewDescriptor("TEXT", asset.mediaType(), null, text.content(), text.truncated());
    }

    private TextPreview extractOffice(Path path) {
        try (POITextExtractor extractor = ExtractorFactory.createExtractor(path.toFile())) {
            return truncate(extractor.getText());
        } catch (Exception exception) {
            throw new PlatformException(
                    "ASSET_PREVIEW_FAILED",
                    "The document is damaged or cannot be decoded"
            );
        }
    }

    private TextPreview readText(Path path) {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int count;
            while (result.length() <= MAX_TEXT_PREVIEW_CHARACTERS
                    && (count = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, count);
            }
        } catch (IOException exception) {
            throw new PlatformException("ASSET_PREVIEW_FAILED", "The text file could not be decoded");
        }
        return truncate(result.toString());
    }

    private static TextPreview truncate(String value) {
        String normalized = value == null ? "" : value;
        if (normalized.length() <= MAX_TEXT_PREVIEW_CHARACTERS) {
            return new TextPreview(normalized, false);
        }
        return new TextPreview(normalized.substring(0, MAX_TEXT_PREVIEW_CHARACTERS), true);
    }

    private static boolean isOffice(String extension) {
        return switch (extension) {
            case ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx" -> true;
            default -> false;
        };
    }

    private static String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }

    public record PreviewDescriptor(
            String kind,
            String mediaType,
            String contentUrl,
            String text,
            boolean truncated
    ) {
    }

    private record TextPreview(String content, boolean truncated) {
    }
}
