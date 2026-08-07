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
        for (String fileName : new String[]{
                "V20260807103000__did_official_talks_provider.sql",
                "V20260807114000__did_expression_prompt_limits.sql"
        }) {
            String migration = Files.readString(findMigration(fileName)).replace("\r\n", "\n");

            assertThat(FLYWAY_PLACEHOLDER_COLLISION.matcher(migration).find()).isFalse();
            assertThat(migration)
                    .contains("$input$\n{")
                    .contains("$ui$\n{")
                    .contains("$output$\n{")
                    .contains("$config$\n{");
        }
    }

    @Test
    void structuredFacialPromptMigrationDefinesNaturalMotionDefaults() throws Exception {
        String migration = Files.readString(findMigration("V20260807150000__did_structured_facial_prompt.sql"))
                .replace("\r\n", "\n");

        assertThat(migration)
                .contains("\"stitch\": false")
                .contains("\"motionFactor\": 1.0")
                .contains("\"expressionTransitionFrames\": 24")
                .contains("\"performancePromptMode\": \"structured-facial-expression-plan\"")
                .contains("previous.version = 13")
                .contains("set current_version = 14");
    }

    private static Path findMigration(String fileName) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("backend-app/src/main/resources/db/migration/" + fileName);
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("D-ID migration could not be found: " + fileName);
    }
}
