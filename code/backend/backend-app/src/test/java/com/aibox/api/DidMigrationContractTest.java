package com.aibox.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DidMigrationContractTest {

    private static final Pattern FLYWAY_PLACEHOLDER_COLLISION = Pattern.compile("\\$[a-zA-Z_]+\\$\\{");

    @Test
    void jsonDollarQuotesDoNotLookLikeFlywayPlaceholders() throws Exception {
        String migration = Files.readString(findMigration()).replace("\r\n", "\n");

        assertThat(FLYWAY_PLACEHOLDER_COLLISION.matcher(migration).find()).isFalse();
        assertThat(migration)
                .contains("$input$\n{")
                .contains("$ui$\n{")
                .contains("$output$\n{")
                .contains("$config$\n{");
    }

    private static Path findMigration() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "backend-app/src/main/resources/db/migration/V20260807103000__did_official_talks_provider.sql"
            );
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("D-ID migration could not be found");
    }
}
