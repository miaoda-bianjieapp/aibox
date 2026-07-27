package com.aibox.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentMigrationSafetyTest {

    private static final Pattern MODEL_DEPLOYMENT_INSERT = Pattern.compile(
            "insert\\s+into\\s+model_deployment\\s*\\(.*?\\)\\s*values.*?;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern CONFLICT_SAFE_BY_CODE = Pattern.compile(
            "on\\s+conflict\\s*\\(\\s*code\\s*\\)\\s+do\\s+nothing",
            Pattern.CASE_INSENSITIVE
    );
    private static final List<String> SHARED_DEPLOYMENT_CODES = List.of(
            "codex2api-gpt-5-4-mini-vision",
            "codex2api-gpt-5-6-sol-text",
            "codex2api-gpt-5-6-sol-vision"
    );

    @Test
    void keepsDocumentSummaryDeploymentSeedsConflictSafe() throws IOException {
        String documentQa = migration("V20260725112037__document_qa.sql");
        String documentSummary = migration("V20260725113615__document_summary.sql");

        for (String code : SHARED_DEPLOYMENT_CODES) {
            assertTrue(documentQa.contains("'" + code + "'"),
                    () -> "Document QA migration must seed shared deployment " + code);
            assertTrue(documentSummary.contains("'" + code + "'"),
                    () -> "Document summary migration must seed shared deployment " + code);
        }

        Matcher deploymentInsert = MODEL_DEPLOYMENT_INSERT.matcher(documentSummary);
        assertTrue(deploymentInsert.find(),
                "Document summary migration must contain its model deployment seed");
        assertTrue(CONFLICT_SAFE_BY_CODE.matcher(deploymentInsert.group()).find(),
                "Repeated document model deployments must use ON CONFLICT (code) DO NOTHING");
    }

    private String migration(String fileName) throws IOException {
        String resource = "db/migration/" + fileName;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, () -> "Missing migration resource " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
