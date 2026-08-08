package com.aibox.api;

import com.aibox.platform.asset.AssetService;
import com.aibox.platform.asset.AssetLibraryService;
import com.aibox.platform.asset.AssetOrigin;
import com.aibox.platform.asset.AssetPreviewService;
import com.aibox.platform.asset.CreativeAssetService;
import com.aibox.platform.common.PlatformException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService assetService;
    private final AssetLibraryService libraryService;
    private final AssetPreviewService previewService;
    private final CreativeAssetService creativeAssetService;

    public AssetController(
            AssetService assetService,
            AssetLibraryService libraryService,
            AssetPreviewService previewService,
            CreativeAssetService creativeAssetService
    ) {
        this.assetService = assetService;
        this.libraryService = libraryService;
        this.previewService = previewService;
        this.creativeAssetService = creativeAssetService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AssetService.AssetView upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "origin", defaultValue = "USER_UPLOAD") String origin
    ) {
        try {
            return assetService.upload(
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    file.getInputStream(),
                    parseOrigin(origin)
            );
        } catch (IOException exception) {
            throw new PlatformException("ASSET_UPLOAD_FAILED", "Uploaded file could not be read");
        }
    }

    @GetMapping
    public List<AssetService.AssetView> list() {
        return assetService.list();
    }

    @GetMapping("/library")
    public AssetLibraryService.AssetPage library(
            @RequestParam(value = "libraryType", defaultValue = "USER_FILE") String libraryType,
            @RequestParam(value = "category", defaultValue = "ALL") String category,
            @RequestParam(value = "query", defaultValue = "") String query,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        return libraryService.list(libraryType, category, query, cursor, pageSize);
    }

    @GetMapping("/creative")
    public List<CreativeAssetService.CreativeAssetView> creativeAssets(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "projectId", required = false) UUID projectId,
            @RequestParam(value = "assetType", required = false) String assetType
    ) {
        return creativeAssetService.list(scope, projectId, assetType);
    }

    @PostMapping("/creative")
    public CreativeAssetService.CreativeAssetView createCreativeAsset(
            @RequestBody CreativeAssetService.CreateCreativeAsset request
    ) {
        return creativeAssetService.create(request);
    }

    @PatchMapping("/creative/{creativeAssetId}")
    public CreativeAssetService.CreativeAssetView updateCreativeAsset(
            @PathVariable UUID creativeAssetId,
            @RequestBody CreativeAssetService.UpdateCreativeAsset request
    ) {
        return creativeAssetService.update(creativeAssetId, request);
    }

    @DeleteMapping("/creative/{creativeAssetId}")
    public ResponseEntity<Void> deleteCreativeAsset(@PathVariable UUID creativeAssetId) {
        creativeAssetService.delete(creativeAssetId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{assetId}")
    public AssetService.AssetView get(@PathVariable UUID assetId) {
        return assetService.get(assetId);
    }

    @GetMapping("/{assetId}/preview")
    public AssetPreviewService.PreviewDescriptor preview(@PathVariable UUID assetId) {
        return previewService.preview(assetId);
    }

    @GetMapping("/{assetId}/preview/content")
    public ResponseEntity<?> previewContent(@PathVariable UUID assetId) {
        AssetPreviewService.PreviewContent content = previewService.previewContent(assetId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(content.mediaType()));
        headers.setContentLength(content.sizeBytes());
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(content.fileName(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(content.resource());
    }

    @PostMapping("/delete-impact")
    public AssetLibraryService.DeleteImpact deleteImpact(@RequestBody AssetSelection request) {
        return libraryService.impact(request.assetIds());
    }

    @PostMapping("/batch-delete")
    public AssetLibraryService.DeleteResult batchDelete(@RequestBody AssetSelection request) {
        return libraryService.delete(request.assetIds());
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<?> content(
            @PathVariable UUID assetId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        AssetService.AssetDownload download = assetService.download(assetId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.asset().mediaType());
        } catch (IllegalArgumentException ignored) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.add(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(download.asset().name(), StandardCharsets.UTF_8)
                .build());
        if (rangeHeader == null || rangeHeader.isBlank()) {
            headers.setContentLength(download.asset().sizeBytes());
            return ResponseEntity.ok().headers(headers).body(download.resource());
        }
        try {
            List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
            if (ranges.size() != 1) {
                throw new IllegalArgumentException("Only one byte range is supported");
            }
            HttpRange range = ranges.get(0);
            long totalLength = download.asset().sizeBytes();
            long start = range.getRangeStart(totalLength);
            long end = range.getRangeEnd(totalLength);
            long count = end - start + 1;
            headers.setContentLength(count);
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + totalLength);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .headers(headers)
                    .body(new ByteRangeResource(download.resource(), start, count));
        } catch (IllegalArgumentException exception) {
            throw new PlatformException("ASSET_RANGE_INVALID", "Requested file range is invalid");
        }
    }

    @DeleteMapping("/{assetId}")
    public ResponseEntity<Void> delete(@PathVariable UUID assetId) {
        assetService.delete(assetId);
        return ResponseEntity.noContent().build();
    }

    private static AssetOrigin parseOrigin(String value) {
        try {
            return AssetOrigin.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformException("ASSET_ORIGIN_INVALID", "Unknown asset origin");
        }
    }

    public record AssetSelection(List<UUID> assetIds) {
        public AssetSelection {
            assetIds = assetIds == null ? List.of() : List.copyOf(assetIds);
        }
    }

    private static final class ByteRangeResource extends AbstractResource {

        private final Resource source;
        private final long position;
        private final long count;

        private ByteRangeResource(Resource source, long position, long count) {
            this.source = source;
            this.position = position;
            this.count = count;
        }

        @Override
        public String getDescription() {
            return source.getDescription() + " byte range " + position + "+" + count;
        }

        @Override
        public String getFilename() {
            return source.getFilename();
        }

        @Override
        public long contentLength() {
            return count;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream input = source.getInputStream();
            try {
                input.skipNBytes(position);
                return new LimitedInputStream(input, count);
            } catch (IOException | RuntimeException exception) {
                input.close();
                throw exception;
            }
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        private LimitedInputStream(InputStream input, long count) {
            super(input);
            this.remaining = count;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int allowed = (int) Math.min(length, remaining);
            int read = super.read(buffer, offset, allowed);
            if (read > 0) remaining -= read;
            return read;
        }
    }
}
