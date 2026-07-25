package com.aibox.platform.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetPreviewServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void previewsGb18030TextFiles() throws Exception {
        UUID assetId = UUID.randomUUID();
        String expected = "微观尽头\r\n这是一份中文文本。";
        Path file = tempDirectory.resolve("novel.txt");
        Files.write(file, expected.getBytes(Charset.forName("GB18030")));

        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = new AssetService.AssetView(
                assetId,
                "novel.txt",
                "text/plain",
                Files.size(file),
                "sha256",
                Instant.now(),
                AssetOrigin.USER_UPLOAD.name(),
                AssetMediaCategory.DOCUMENT.name(),
                "READY",
                true,
                0,
                null
        );
        when(assetService.openForPreview(assetId))
                .thenReturn(new AssetService.AssetStoredFile(asset, file));

        AssetPreviewService.PreviewDescriptor preview =
                new AssetPreviewService(assetService).preview(assetId);

        assertThat(preview.kind()).isEqualTo("TEXT");
        assertThat(preview.text()).isEqualTo(expected);
        assertThat(preview.truncated()).isFalse();
    }
}
