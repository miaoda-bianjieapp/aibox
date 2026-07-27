package com.aibox.platform.asset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class PowerPointPreviewConverter {

    private static final Logger log = LoggerFactory.getLogger(PowerPointPreviewConverter.class);
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final byte[] PDF_HEADER = {'%', 'P', 'D', 'F', '-'};

    private final Path cacheRoot;
    private final String executable;
    private final Duration timeout;
    private final ProcessRunner processRunner;
    private final ConcurrentHashMap<String, CompletableFuture<Optional<Path>>> conversions =
            new ConcurrentHashMap<>();

    @Autowired
    public PowerPointPreviewConverter(
            @Value("${yuanzuo.asset.storage-path}") String storagePath,
            @Value("${yuanzuo.asset.powerpoint-preview.libreoffice-path:}") String libreOfficePath,
            @Value("${yuanzuo.asset.powerpoint-preview.conversion-timeout-ms:120000}")
            long conversionTimeoutMillis
    ) {
        this(
                Path.of(storagePath),
                resolveExecutable(libreOfficePath),
                Duration.ofMillis(Math.max(1, conversionTimeoutMillis)),
                new SystemProcessRunner()
        );
    }

    PowerPointPreviewConverter(
            Path storageRoot,
            String executable,
            Duration timeout,
            ProcessRunner processRunner
    ) {
        this.cacheRoot = storageRoot.toAbsolutePath().normalize()
                .resolve(".preview-cache")
                .resolve("powerpoint-pdf");
        this.executable = executable;
        this.timeout = timeout;
        this.processRunner = processRunner;
    }

    public Optional<Path> convert(Path source, String extension, String sha256) {
        if (!isPowerPointExtension(extension) || !isSha256(sha256)) {
            return Optional.empty();
        }
        String cacheKey = sha256.toLowerCase(Locale.ROOT);
        Path cached = cacheRoot.resolve(cacheKey + ".pdf").normalize();
        if (!cached.startsWith(cacheRoot)) return Optional.empty();
        if (isPdf(cached)) return Optional.of(cached);

        CompletableFuture<Optional<Path>> conversion = new CompletableFuture<>();
        CompletableFuture<Optional<Path>> active = conversions.putIfAbsent(cacheKey, conversion);
        if (active != null) {
            try {
                return active.join();
            } catch (CompletionException exception) {
                return Optional.empty();
            }
        }
        try {
            Optional<Path> result = isPdf(cached)
                    ? Optional.of(cached)
                    : convertAndCache(source, extension, cached, cacheKey);
            conversion.complete(result);
            return result;
        } catch (RuntimeException exception) {
            conversion.completeExceptionally(exception);
            throw exception;
        } finally {
            conversions.remove(cacheKey, conversion);
        }
    }

    private Optional<Path> convertAndCache(
            Path source,
            String extension,
            Path cached,
            String cacheKey
    ) {
        Path workspace = null;
        Path staged = null;
        try {
            Files.createDirectories(cacheRoot);
            workspace = Files.createTempDirectory(cacheRoot, ".convert-");
            Path input = workspace.resolve("presentation" + extension);
            Path outputDirectory = workspace.resolve("output");
            Path profileDirectory = workspace.resolve("profile");
            Files.createDirectories(outputDirectory);
            Files.createDirectories(profileDirectory);
            Files.copy(source, input, StandardCopyOption.REPLACE_EXISTING);

            List<String> command = List.of(
                    executable,
                    "--headless",
                    "--nologo",
                    "--nodefault",
                    "--nolockcheck",
                    "--nofirststartwizard",
                    "-env:UserInstallation=" + profileDirectory.toUri(),
                    "--convert-to",
                    "pdf:impress_pdf_Export",
                    "--outdir",
                    outputDirectory.toString(),
                    input.toString()
            );
            if (!processRunner.run(command, workspace, timeout)) {
                return Optional.empty();
            }

            Path generated = outputDirectory.resolve("presentation.pdf");
            if (!isPdf(generated)) return Optional.empty();
            staged = Files.createTempFile(cacheRoot, cacheKey + "-", ".pdf.tmp");
            Files.copy(generated, staged, StandardCopyOption.REPLACE_EXISTING);
            moveIntoCache(staged, cached);
            return isPdf(cached) ? Optional.of(cached) : Optional.empty();
        } catch (IOException exception) {
            log.warn("PowerPoint preview conversion is unavailable");
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("PowerPoint preview conversion failed");
            return Optional.empty();
        } finally {
            deleteFile(staged);
            deleteRecursively(workspace);
        }
    }

    private static void moveIntoCache(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isPdf(Path path) {
        if (!Files.isRegularFile(path)) return false;
        try {
            if (Files.size(path) < PDF_HEADER.length) return false;
            byte[] header = new byte[PDF_HEADER.length];
            try (var input = Files.newInputStream(path)) {
                if (input.read(header) != PDF_HEADER.length) return false;
            }
            return java.util.Arrays.equals(header, PDF_HEADER);
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean isPowerPointExtension(String extension) {
        return ".ppt".equals(extension) || ".pptx".equals(extension);
    }

    private static boolean isSha256(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }

    private static String resolveExecutable(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return "soffice";
        }
        for (String candidate : List.of(
                "C:/Program Files/LibreOffice/program/soffice.exe",
                "C:/Program Files (x86)/LibreOffice/program/soffice.exe"
        )) {
            if (Files.isRegularFile(Path.of(candidate))) return candidate;
        }
        return "soffice.exe";
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void deleteFile(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        boolean run(List<String> command, Path workingDirectory, Duration timeout)
                throws IOException, InterruptedException;
    }

    private static final class SystemProcessRunner implements ProcessRunner {
        @Override
        public boolean run(List<String> command, Path workingDirectory, Duration timeout)
                throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return false;
            }
            return process.exitValue() == 0;
        }
    }
}
