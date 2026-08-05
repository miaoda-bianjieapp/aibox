package com.aibox.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TextToSpeechContractTest {

    @Test
    void exposesTheDynamicFormAndFixedAudioContract() throws Exception {
        Path contracts = findContracts();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.readTree(
                Files.readString(contracts.resolve("input-schema.json"))
        );
        JsonNode ui = mapper.readTree(
                Files.readString(contracts.resolve("ui-schema.json"))
        );
        JsonNode output = mapper.readTree(
                Files.readString(contracts.resolve("output-schema.json"))
        );
        String feature = Files.readString(contracts.resolve("feature.yaml"));

        assertThat(feature)
                .contains("featureCode: audio.text_to_speech")
                .contains("version: 2")
                .contains("status: INTERNAL")
                .contains("resultType: audio")
                .contains("rendererKey: audio");
        assertThat(input.path("required").toString())
                .contains("text", "voice", "speed", "emotion");
        assertThat(input.path("properties").path("text").path("maxLength").asInt())
                .isEqualTo(500);
        JsonNode speed = input.path("properties").path("speed");
        assertThat(speed.path("type").asText()).isEqualTo("number");
        assertThat(speed.path("minimum").asDouble()).isEqualTo(0.5);
        assertThat(speed.path("maximum").asDouble()).isEqualTo(2.0);
        assertThat(speed.path("multipleOf").asDouble()).isEqualTo(0.05);
        assertThat(speed.path("default").asDouble()).isEqualTo(1.0);
        assertThat(ui.path("widgets").path("text").asText()).isEqualTo("textarea");
        assertThat(ui.path("widgets").path("voice").asText()).isEqualTo("dropdown");
        assertThat(ui.path("widgets").path("speed").asText()).isEqualTo("slider");
        assertThat(output.path("required").toString()).contains("assetId", "name");
    }

    @Test
    void exposesThreeSelectableTtsDeployments() throws Exception {
        String migration = Files.readString(findModelMigration());
        String baseMigration = Files.readString(findBaseMigration());
        String compatibilityMigration = Files.readString(findVoiceCompatibilityMigration());
        String defaultMigration = Files.readString(findDefaultModelMigration());
        String speedSliderMigration = Files.readString(findSpeedSliderMigration()).replace("\r\n", "\n");

        assertThat(baseMigration).contains("openai2api-gpt-sovits-v2-tts");

        assertThat(migration)
                .contains("openai2api-index-tts2-tts")
                .contains("openai2api-omnivoice-tts")
                .contains("'index-tts2'")
                .contains("'omnivoice'")
                .contains("allow_user_selection = true");

        assertThat(compatibilityMigration)
                .contains("'{parameterOptions}'")
                .contains("'{\"voice\":[\"gentle_female\"]}'::jsonb")
                .contains("'{\"voice\":[\"science_female\",\"gentle_female\"]}'::jsonb")
                .contains("where code = 'openai2api-gpt-sovits-v2-tts'")
                .contains("'openai2api-index-tts2-tts'")
                .contains("'openai2api-omnivoice-tts'");

        assertThat(defaultMigration)
                .contains("default_deployment_code = 'openai2api-omnivoice-tts'")
                .contains("feature_code = 'audio.text_to_speech'")
                .contains("capability = 'TEXT_TO_SPEECH'");

        assertThat(speedSliderMigration)
                .contains("version,\n    input_schema_json")
                .contains("\"speed\": \"slider\"")
                .contains("current_version = 2")
                .contains("fv.version = 1");
    }

    private static Path findSpeedSliderMigration() {
        return findMigration(
                "V20260804153120__audio_tts_speed_slider.sql",
                "audio.text_to_speech speed slider migration could not be found"
        );
    }

    private static Path findDefaultModelMigration() {
        return findMigration(
                "V20260804145316__audio_tts_default_omnivoice.sql",
                "audio.text_to_speech default model migration could not be found"
        );
    }

    private static Path findVoiceCompatibilityMigration() {
        return findMigration(
                "V20260804131500__audio_tts_voice_compatibility.sql",
                "audio.text_to_speech voice compatibility migration could not be found"
        );
    }

    private static Path findModelMigration() {
        return findMigration(
                "V20260804112200__audio_text_to_speech_models.sql",
                "audio.text_to_speech model migration could not be found"
        );
    }

    private static Path findBaseMigration() {
        return findMigration(
                "V20260803170100__audio_text_to_speech.sql",
                "audio.text_to_speech base migration could not be found"
        );
    }

    private static Path findMigration(String fileName, String errorMessage) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "backend-app/src/main/resources/db/migration/" + fileName
            );
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException(errorMessage);
    }

    private static Path findContracts() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "contracts/features/audio/text_to_speech"
            );
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException(
                "audio.text_to_speech contracts could not be found"
        );
    }
}
