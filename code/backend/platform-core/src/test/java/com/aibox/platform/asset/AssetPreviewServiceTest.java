package com.aibox.platform.asset;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
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
                new AssetPreviewService(
                        assetService,
                        mock(PowerPointPreviewConverter.class)
                ).preview(assetId);

        assertThat(preview.kind()).isEqualTo("TEXT");
        assertThat(preview.text()).isEqualTo(expected);
        assertThat(preview.truncated()).isFalse();
    }

    @Test
    void returnsGeneratedPdfDescriptorForPowerPointFiles() throws Exception {
        UUID assetId = UUID.randomUUID();
        String sha256 = "a".repeat(64);
        Path source = tempDirectory.resolve("slides.pptx");
        Files.writeString(source, "presentation");
        Path converted = tempDirectory.resolve("slides.pdf");
        Files.write(converted, new byte[]{1, 2, 3});

        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = documentAsset(assetId, "slides.pptx", sha256, source);
        when(assetService.openForPreview(assetId))
                .thenReturn(new AssetService.AssetStoredFile(asset, source));
        PowerPointPreviewConverter converter = mock(PowerPointPreviewConverter.class);
        when(converter.convert(source, ".pptx", sha256)).thenReturn(Optional.of(converted));

        AssetPreviewService.PreviewDescriptor preview =
                new AssetPreviewService(assetService, converter).preview(assetId);

        assertThat(preview.kind()).isEqualTo("PDF");
        assertThat(preview.mediaType()).isEqualTo("application/pdf");
        assertThat(preview.contentUrl())
                .isEqualTo("/api/v1/assets/" + assetId + "/preview/content");
        assertThat(preview.text()).isNull();
    }

    @Test
    void fallsBackToExtractedTextWhenPowerPointConversionFails() throws Exception {
        UUID assetId = UUID.randomUUID();
        String sha256 = "b".repeat(64);
        Path source = tempDirectory.resolve("fallback.pptx");
        try (XMLSlideShow slideshow = new XMLSlideShow()) {
            XSLFTextBox textBox = slideshow.createSlide().createTextBox();
            textBox.setText("Fallback slide text");
            try (var output = Files.newOutputStream(source)) {
                slideshow.write(output);
            }
        }

        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = documentAsset(assetId, "fallback.pptx", sha256, source);
        when(assetService.openForPreview(assetId))
                .thenReturn(new AssetService.AssetStoredFile(asset, source));
        PowerPointPreviewConverter converter = mock(PowerPointPreviewConverter.class);
        when(converter.convert(source, ".pptx", sha256)).thenReturn(Optional.empty());

        AssetPreviewService.PreviewDescriptor preview =
                new AssetPreviewService(assetService, converter).preview(assetId);

        assertThat(preview.kind()).isEqualTo("TEXT");
        assertThat(preview.text()).contains("Fallback slide text");
    }

    private static AssetService.AssetView documentAsset(
            UUID assetId,
            String name,
            String sha256,
            Path file
    ) throws Exception {
        return new AssetService.AssetView(
                assetId,
                name,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                Files.size(file),
                sha256,
                Instant.now(),
                AssetOrigin.USER_UPLOAD.name(),
                AssetMediaCategory.DOCUMENT.name(),
                "READY",
                true,
                0,
                null
        );
    }
}
