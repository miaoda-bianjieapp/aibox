package com.aibox.api;

import com.aibox.platform.asset.AssetLibraryService;
import com.aibox.platform.asset.AssetPreviewService;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.common.PlatformException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsAResourceRegionForByteRangeRequests() throws Exception {
        UUID assetId = UUID.randomUUID();
        Path file = tempDir.resolve("video.mp4");
        Files.write(file, new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        AssetService assetService = mock(AssetService.class);
        when(assetService.download(assetId)).thenReturn(download(assetId, file));
        AssetController controller = controller(assetService);

        ResponseEntity<?> response = controller.content(assetId, "bytes=2-5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(4);
        assertThat(response.getBody()).isInstanceOf(ResourceRegion.class);
        ResourceRegion region = (ResourceRegion) response.getBody();
        assertThat(region.getPosition()).isEqualTo(2);
        assertThat(region.getCount()).isEqualTo(4);
    }

    @Test
    void rejectsMultipleByteRanges() throws Exception {
        UUID assetId = UUID.randomUUID();
        Path file = tempDir.resolve("audio.mp3");
        Files.write(file, new byte[]{0, 1, 2, 3});
        AssetService assetService = mock(AssetService.class);
        when(assetService.download(assetId)).thenReturn(download(assetId, file));
        AssetController controller = controller(assetService);

        PlatformException exception = catchThrowableOfType(
                PlatformException.class,
                () -> controller.content(assetId, "bytes=0-1,2-3")
        );
        assertThat(exception.code()).isEqualTo("ASSET_RANGE_INVALID");
    }

    private static AssetController controller(AssetService assetService) {
        return new AssetController(
                assetService,
                mock(AssetLibraryService.class),
                mock(AssetPreviewService.class)
        );
    }

    private static AssetService.AssetDownload download(UUID assetId, Path file) {
        Resource resource = new FileSystemResource(file);
        return new AssetService.AssetDownload(
                new AssetService.AssetView(
                        assetId,
                        file.getFileName().toString(),
                        "video/mp4",
                        file.toFile().length(),
                        "sha256",
                        Instant.parse("2026-07-24T00:00:00Z")
                ),
                resource
        );
    }
}
