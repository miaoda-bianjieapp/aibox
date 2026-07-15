package com.aibox.api;

import com.aibox.platform.asset.AssetService;
import com.aibox.platform.common.PlatformException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AssetService.AssetView upload(@RequestPart("file") MultipartFile file) {
        try {
            return assetService.upload(
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), file.getInputStream()
            );
        } catch (IOException exception) {
            throw new PlatformException("ASSET_UPLOAD_FAILED", "Uploaded file could not be read");
        }
    }

    @GetMapping
    public List<AssetService.AssetView> list() {
        return assetService.list();
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<org.springframework.core.io.Resource> content(@PathVariable UUID assetId) {
        AssetService.AssetDownload download = assetService.download(assetId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(download.asset().mediaType());
        } catch (IllegalArgumentException ignored) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(download.asset().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(download.asset().name(), StandardCharsets.UTF_8)
                        .build().toString())
                .body(download.resource());
    }

    @DeleteMapping("/{assetId}")
    public ResponseEntity<Void> delete(@PathVariable UUID assetId) {
        assetService.delete(assetId);
        return ResponseEntity.noContent().build();
    }
}
