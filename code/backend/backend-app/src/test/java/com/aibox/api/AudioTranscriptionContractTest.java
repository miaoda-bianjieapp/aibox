package com.aibox.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AudioTranscriptionContractTest {

    @Test
    void textPostProcessModelsUseExpandableDropdown() throws Exception {
        Path contracts = findContracts();
        JsonNode uiSchema = new ObjectMapper().readTree(
                Files.readString(contracts.resolve("ui-schema.json"))
        );
        String feature = Files.readString(contracts.resolve("feature.yaml"));

        assertThat(feature).contains("version: 5");
        assertThat(
                uiSchema.path("modelSelectors")
                        .path("TEXT_GENERATION")
                        .path("widget")
                        .asText()
        ).isEqualTo("dropdown");
        assertThat(
                new ObjectMapper()
                        .readTree(Files.readString(contracts.resolve("input-schema.json")))
                        .path("properties")
                        .path("postProcess")
                        .path("description")
                        .asText()
        ).contains("明显不是会议");
    }

    private static Path findContracts() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "contracts/features/audio/transcription"
            );
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException(
                "audio.transcription contracts could not be found"
        );
    }
}
