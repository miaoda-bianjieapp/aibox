package com.aibox.platform.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetStorageMaintenanceServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    void removesOnlyUnreferencedManagedFilesOlderThanTheGracePeriod() throws Exception {
        Instant now = Instant.parse("2026-07-24T04:00:00Z");
        String ownerPath = UUID.randomUUID() + "/" + UUID.randomUUID();
        String activeKey = ownerPath + "/" + UUID.randomUUID();
        String orphanKey = ownerPath + "/" + UUID.randomUUID();
        String recentKey = ownerPath + "/blobs/" + UUID.randomUUID();
        Path active = write(activeKey, now.minusSeconds(30 * 60 * 60));
        Path orphan = write(orphanKey, now.minusSeconds(30 * 60 * 60));
        Path recent = write(recentKey, now.minusSeconds(60 * 60));
        Path unrelated = storageRoot.resolve("keep-me.txt");
        Files.writeString(unrelated, "not managed storage");
        Files.setLastModifiedTime(unrelated, FileTime.from(now.minusSeconds(30 * 60 * 60)));

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of(activeKey));
        AssetStorageMaintenanceService service = new AssetStorageMaintenanceService(
                jdbcTemplate,
                Clock.fixed(now, ZoneOffset.UTC),
                storageRoot.toString()
        );

        AssetStorageMaintenanceService.CleanupReport report = service.cleanupNow();

        assertThat(report.deletedFileCount()).isEqualTo(1);
        assertThat(report.deletedBytes()).isEqualTo("orphan".getBytes().length);
        assertThat(active).exists();
        assertThat(orphan).doesNotExist();
        assertThat(recent).exists();
        assertThat(unrelated).exists();
    }

    private Path write(String storageKey, Instant modifiedAt) throws Exception {
        Path path = storageRoot.resolve(storageKey);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "orphan");
        Files.setLastModifiedTime(path, FileTime.from(modifiedAt));
        return path;
    }
}
