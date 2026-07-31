package com.aibox.platform.asset;

import com.aibox.platform.common.PlatformException;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AssetPreviewService {

    private static final int MAX_TEXT_PREVIEW_CHARACTERS = 2_000_000;

    private final AssetService assetService;
    private final OfficePreviewConverter officePreviewConverter;
    private final SpreadsheetPreviewReader spreadsheetPreviewReader;

    public AssetPreviewService(
            AssetService assetService,
            OfficePreviewConverter officePreviewConverter,
            SpreadsheetPreviewReader spreadsheetPreviewReader
    ) {
        this.assetService = assetService;
        this.officePreviewConverter = officePreviewConverter;
        this.spreadsheetPreviewReader = spreadsheetPreviewReader;
    }

    public PreviewDescriptor preview(UUID assetId) {
        AssetService.AssetStoredFile stored = assetService.openForPreview(assetId);
        AssetService.AssetView asset = stored.asset();
        String extension = extension(asset.name());
        String contentUrl = "/api/v1/assets/" + asset.id() + "/content";
        return switch (asset.category()) {
            case "IMAGE" -> new PreviewDescriptor(
                    "IMAGE", asset.mediaType(), contentUrl, null, false, false, null
            );
            case "VIDEO" -> new PreviewDescriptor(
                    "VIDEO", asset.mediaType(), contentUrl, null, false, false, null
            );
            case "AUDIO" -> new PreviewDescriptor(
                    "AUDIO", asset.mediaType(), contentUrl, null, false, false, null
            );
            case "DOCUMENT" -> documentPreview(asset, stored.path(), extension, contentUrl);
            default -> throw new PlatformException(
                    "ASSET_PREVIEW_UNSUPPORTED", "This file cannot be previewed"
            );
        };
    }

    public PreviewContent previewContent(UUID assetId) {
        AssetService.AssetStoredFile stored = assetService.openForPreview(assetId);
        AssetService.AssetView asset = stored.asset();
        String extension = extension(asset.name());
        if (!isOffice(extension)) {
            throw new PlatformException(
                    "ASSET_PREVIEW_CONTENT_UNSUPPORTED",
                    "This file does not have generated preview content"
            );
        }
        Path converted = officePreviewConverter
                .convert(stored.path(), extension, asset.sha256())
                .orElseThrow(() -> new PlatformException(
                        "ASSET_PREVIEW_CONTENT_UNAVAILABLE",
                        "The generated preview is unavailable"
                ));
        try {
            return new PreviewContent(
                    "application/pdf",
                    pdfName(asset.name()),
                    Files.size(converted),
                    new FileSystemResource(converted)
            );
        } catch (IOException exception) {
            throw new PlatformException(
                    "ASSET_PREVIEW_CONTENT_UNAVAILABLE",
                    "The generated preview is unavailable"
            );
        }
    }

    private PreviewDescriptor documentPreview(
            AssetService.AssetView asset,
            Path path,
            String extension,
            String contentUrl
    ) {
        if (".pdf".equals(extension) || "application/pdf".equalsIgnoreCase(asset.mediaType())) {
            return new PreviewDescriptor(
                    "PDF", "application/pdf", contentUrl, null, false, false, null
            );
        }
        if (isSpreadsheet(extension)) {
            return spreadsheetPreview(asset, path, extension);
        }
        if (isOffice(extension)) {
            return officePreview(asset, path, extension);
        }
        TextPreview text = readText(path);
        return new PreviewDescriptor(
                "TEXT", asset.mediaType(), null, text.content(), text.truncated(), false, null
        );
    }

    private PreviewDescriptor spreadsheetPreview(
            AssetService.AssetView asset,
            Path path,
            String extension
    ) {
        SpreadsheetPreviewReader.SpreadsheetPreview spreadsheet =
                spreadsheetPreviewReader.read(path, extension);
        String layoutUrl = isExcel(extension)
                ? "/api/v1/assets/" + asset.id() + "/preview/content"
                : null;
        return new PreviewDescriptor(
                "SPREADSHEET",
                asset.mediaType(),
                layoutUrl,
                null,
                false,
                false,
                spreadsheet
        );
    }

    private PreviewDescriptor officePreview(
            AssetService.AssetView asset,
            Path path,
            String extension
    ) {
        if (officePreviewConverter.convert(path, extension, asset.sha256()).isPresent()) {
            return new PreviewDescriptor(
                    "PDF",
                    "application/pdf",
                    "/api/v1/assets/" + asset.id() + "/preview/content",
                    null,
                    false,
                    false,
                    null
            );
        }
        TextPreview text = extractPoiText(path);
        return new PreviewDescriptor(
                "TEXT", "text/plain", null, text.content(), text.truncated(), true, null
        );
    }

    private TextPreview extractPoiText(Path path) {
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
        try {
            for (Charset charset : preferredTextCharsets(path)) {
                try {
                    return readText(path, charset);
                } catch (CharacterCodingException ignored) {
                    // Try the next supported text encoding.
                }
            }
        } catch (IOException exception) {
            throw new PlatformException("ASSET_PREVIEW_FAILED", "The text file could not be decoded");
        }
        throw new PlatformException("ASSET_PREVIEW_FAILED", "The text file could not be decoded");
    }

    private TextPreview readText(Path path, Charset charset) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(path, charset)) {
            char[] buffer = new char[8192];
            int count;
            while (result.length() <= MAX_TEXT_PREVIEW_CHARACTERS
                    && (count = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, count);
            }
        }
        String value = result.toString();
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            value = value.substring(1);
        }
        return truncate(value);
    }

    private static List<Charset> preferredTextCharsets(Path path) throws IOException {
        byte[] prefix = new byte[3];
        int count;
        try (InputStream input = Files.newInputStream(path)) {
            count = input.read(prefix);
        }
        Charset gb18030 = Charset.forName("GB18030");
        if (count >= 2
                && ((prefix[0] == (byte) 0xFF && prefix[1] == (byte) 0xFE)
                || (prefix[0] == (byte) 0xFE && prefix[1] == (byte) 0xFF))) {
            return List.of(StandardCharsets.UTF_16, StandardCharsets.UTF_8, gb18030);
        }
        return List.of(StandardCharsets.UTF_8, gb18030, StandardCharsets.UTF_16);
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

    private static boolean isSpreadsheet(String extension) {
        return isExcel(extension) || ".csv".equals(extension);
    }

    private static boolean isExcel(String extension) {
        return ".xls".equals(extension) || ".xlsx".equals(extension);
    }

    private static String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).toLowerCase(Locale.ROOT);
    }

    private static String pdfName(String name) {
        int index = name.lastIndexOf('.');
        String base = index <= 0 ? name : name.substring(0, index);
        return (base == null || base.isBlank() ? "preview" : base) + ".pdf";
    }

    public record PreviewDescriptor(
            String kind,
            String mediaType,
            String contentUrl,
            String text,
            boolean truncated,
            boolean fallback,
            SpreadsheetPreviewReader.SpreadsheetPreview spreadsheet
    ) {
    }

    public record PreviewContent(
            String mediaType,
            String fileName,
            long sizeBytes,
            Resource resource
    ) {
    }

    private record TextPreview(String content, boolean truncated) {
    }
}
