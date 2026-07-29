package com.aibox.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentCompareContractTest {

    private static final List<String> CSV_MEDIA_TYPES = List.of(
            "text/csv",
            "text/plain",
            "application/csv",
            "text/comma-separated-values",
            "application/vnd.ms-excel",
            "application/octet-stream"
    );

    @Test
    void documentFieldsAcceptCommonCsvMediaTypes() throws Exception {
        Path schemaPath = findSchema();
        assertThat(Files.isRegularFile(schemaPath)).isTrue();

        JsonNode fieldOptions = new ObjectMapper()
                .readTree(Files.readString(schemaPath))
                .path("fieldOptions");
        for (String field : List.of(
                "baselineDocument",
                "comparisonDocuments"
        )) {
            List<String> accepted = new ObjectMapper().convertValue(
                    fieldOptions.path(field).path("acceptedMimeTypes"),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {
                    }
            );
            assertThat(accepted)
                    .as("%s CSV media types", field)
                    .containsAll(CSV_MEDIA_TYPES);
        }
    }

    private static Path findSchema() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "contracts/features/document/compare/ui-schema.json"
            );
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException(
                "document.compare UI schema could not be found"
        );
    }
}
