package com.aibox.platform.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PowerPointPreviewConverterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void canBeCreatedBySpringWithConfiguredProperties() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource(
                            "powerPointPreviewTest",
                            Map.of(
                                    "yuanzuo.asset.storage-path", tempDirectory.toString(),
                                    "yuanzuo.asset.powerpoint-preview.libreoffice-path", "",
                                    "yuanzuo.asset.powerpoint-preview.conversion-timeout-ms", "5000"
                            )
                    )
            );
            context.register(PowerPointPreviewConverter.class);

            context.refresh();

            assertThat(context.getBean(PowerPointPreviewConverter.class)).isNotNull();
        }
    }

    @Test
    void cachesConvertedPdfByAssetSha256() throws Exception {
        Path source = tempDirectory.resolve("slides.pptx");
        Files.writeString(source, "presentation");
        AtomicInteger invocations = new AtomicInteger();
        PowerPointPreviewConverter converter = new PowerPointPreviewConverter(
                tempDirectory,
                "soffice",
                Duration.ofSeconds(5),
                (command, workingDirectory, timeout) -> {
                    invocations.incrementAndGet();
                    Path output = workingDirectory.resolve("output").resolve("presentation.pdf");
                    Files.write(output, "%PDF-1.7\npreview".getBytes());
                    return true;
                }
        );
        String sha256 = "c".repeat(64);

        Path first = converter.convert(source, ".pptx", sha256).orElseThrow();
        Path second = converter.convert(source, ".pptx", sha256).orElseThrow();

        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(second)).startsWith("%PDF-");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void sharesAnInProgressConversionForTheSameSha256() throws Exception {
        Path source = tempDirectory.resolve("concurrent.pptx");
        Files.writeString(source, "presentation");
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch conversionStarted = new CountDownLatch(1);
        CountDownLatch releaseConversion = new CountDownLatch(1);
        PowerPointPreviewConverter converter = new PowerPointPreviewConverter(
                tempDirectory,
                "soffice",
                Duration.ofSeconds(5),
                (command, workingDirectory, timeout) -> {
                    invocations.incrementAndGet();
                    conversionStarted.countDown();
                    assertThat(releaseConversion.await(5, TimeUnit.SECONDS)).isTrue();
                    Path output = workingDirectory.resolve("output").resolve("presentation.pdf");
                    Files.write(output, "%PDF-1.7\npreview".getBytes());
                    return true;
                }
        );
        String sha256 = "d".repeat(64);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> converter.convert(source, ".pptx", sha256));
            assertThat(conversionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> converter.convert(source, ".pptx", sha256));
            releaseConversion.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(second.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            executor.shutdownNow();
        }
        assertThat(invocations).hasValue(1);
    }

    @Test
    void returnsEmptyWhenLibreOfficeRunnerFailsUnexpectedly() throws Exception {
        Path source = tempDirectory.resolve("failure.pptx");
        Files.writeString(source, "presentation");
        PowerPointPreviewConverter converter = new PowerPointPreviewConverter(
                tempDirectory,
                "soffice",
                Duration.ofSeconds(5),
                (command, workingDirectory, timeout) -> {
                    throw new IllegalStateException("conversion failed");
                }
        );

        assertThat(converter.convert(source, ".pptx", "e".repeat(64))).isEmpty();
    }
}
