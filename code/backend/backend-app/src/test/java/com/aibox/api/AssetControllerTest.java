package com.aibox.api;

import com.aibox.platform.asset.AssetLibraryService;
import com.aibox.platform.asset.AssetPreviewService;
import com.aibox.platform.asset.AssetService;
import com.aibox.platform.asset.CreativeAssetService;
import com.aibox.platform.common.PlatformException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes 2-5/8");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(4);
        assertThat(response.getBody()).isInstanceOf(Resource.class);
        Resource region = (Resource) response.getBody();
        try (var input = region.getInputStream()) {
            assertThat(input.readAllBytes()).containsExactly(2, 3, 4, 5);
        }
    }

    @Test
    void writesByteRangeResponsesThroughSpringMvc() throws Exception {
        UUID assetId = UUID.randomUUID();
        Path file = tempDir.resolve("mvc-video.mp4");
        Files.write(file, new byte[]{0, 1, 2, 3, 4, 5, 6, 7});
        AssetService assetService = mock(AssetService.class);
        when(assetService.download(assetId)).thenReturn(download(assetId, file));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(controller(assetService))
                .build();

        mockMvc.perform(get("/api/v1/assets/{assetId}/content", assetId)
                        .header(HttpHeaders.RANGE, "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/8"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 4))
                .andExpect(content().bytes(new byte[]{2, 3, 4, 5}));
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

    @Test
    void returnsGeneratedPreviewContentAsInlinePdf() throws Exception {
        UUID assetId = UUID.randomUUID();
        Path file = tempDir.resolve("slides.pdf");
        Files.write(file, "%PDF-1.7\npreview".getBytes());
        AssetPreviewService previewService = mock(AssetPreviewService.class);
        when(previewService.previewContent(assetId)).thenReturn(
                new AssetPreviewService.PreviewContent(
                        "application/pdf",
                        "slides.pdf",
                        Files.size(file),
                        new FileSystemResource(file)
                )
        );
        AssetController controller = new AssetController(
                mock(AssetService.class),
                mock(AssetLibraryService.class),
                previewService,
                mock(CreativeAssetService.class)
        );

        ResponseEntity<?> response = controller.previewContent(assetId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo("inline");
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("slides.pdf");
        assertThat(response.getBody()).isInstanceOf(FileSystemResource.class);
    }

    private static AssetController controller(AssetService assetService) {
        return new AssetController(
                assetService,
                mock(AssetLibraryService.class),
                mock(AssetPreviewService.class),
                mock(CreativeAssetService.class)
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
