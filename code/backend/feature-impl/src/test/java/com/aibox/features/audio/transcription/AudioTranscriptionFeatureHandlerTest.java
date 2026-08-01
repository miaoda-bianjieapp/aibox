package com.aibox.features.audio.transcription;

import com.aibox.feature.spi.AudioTranscriptSegment;
import com.aibox.feature.spi.AudioTranscriptionRequest;
import com.aibox.feature.spi.AudioTranscriptionResponse;
import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelCapability;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.ModelProviderException;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioTranscriptionFeatureHandlerTest {

    @Test
    void createsOneStructuredTranscriptArtifact() {
        UUID assetId = UUID.randomUUID();
        CapturingGateway gateway = new CapturingGateway();
        gateway.transcription = new AudioTranscriptionResponse(
                "欢迎参加会议。",
                List.of(new AudioTranscriptSegment(0, 2_400, "欢迎参加会议。", "A", 0.97)),
                "zh",
                2.4,
                "assemblyai-official",
                "universal-3-5-pro",
                "transcript-1",
                3,
                null
        );
        FeatureExecutionContext context = context(
                assetId,
                Map.of(
                        "audioFile", assetId.toString(),
                        "language", "auto",
                        "speakerDiarization", true,
                        "timestampMode", "segment",
                        "postProcess", "none",
                        "professionalTerms", "元作 AI"
                )
        );

        FeatureExecutionResult result = new AudioTranscriptionFeatureHandler().execute(context, gateway);

        assertEquals(1, result.artifacts().size());
        var artifact = result.artifacts().get(0);
        assertEquals("transcript", artifact.kind());
        assertEquals("application/vnd.yuanzuo.transcript+json", artifact.mimeType());
        assertEquals("欢迎参加会议。", artifact.content().get("text"));
        assertEquals("zh", artifact.content().get("detectedLanguage"));
        assertEquals("none", artifact.content().get("postProcess"));
        assertFalse(artifact.content().containsKey("supplement"));
        assertEquals(true, gateway.audioRequest.speakerDiarization());
        assertEquals(
                "assemblyai-universal-3-5-pro-audio",
                gateway.audioRequest.deploymentCode()
        );
    }

    @Test
    void preservesTranscriptWhenSummaryGenerationFails() {
        UUID assetId = UUID.randomUUID();
        CapturingGateway gateway = new CapturingGateway();
        gateway.transcription = transcription();
        gateway.textFailure = new ModelProviderException(
                "PROVIDER_RATE_LIMITED",
                "busy",
                true
        );
        FeatureExecutionContext context = context(
                assetId,
                Map.of(
                        "audioFile", assetId.toString(),
                        "language", "auto",
                        "speakerDiarization", false,
                        "timestampMode", "segment",
                        "postProcess", "summary"
                )
        );

        var artifact = new AudioTranscriptionFeatureHandler()
                .execute(context, gateway)
                .artifacts()
                .get(0);

        assertEquals("欢迎参加会议。", artifact.content().get("text"));
        var supplement = Map.class.cast(artifact.content().get("supplement"));
        assertEquals("summary", supplement.get("type"));
        assertEquals("FAILED", supplement.get("status"));
        assertEquals("PROVIDER_RATE_LIMITED", supplement.get("errorCode"));
    }

    @Test
    void reusesCompatibleBaseTranscriptForMeetingMinutes() {
        UUID assetId = UUID.randomUUID();
        CapturingGateway gateway = new CapturingGateway();
        gateway.textResponse = new TextGenerationResponse(
                "# 会议纪要\n\n## 核心结论\n\n继续推进。",
                "zhipu-bigmodel",
                "glm-4.5-air",
                "text-1",
                100,
                50
        );
        ArtifactReference base = new ArtifactReference(
                UUID.randomUUID(),
                1,
                "transcript",
                "application/vnd.yuanzuo.transcript+json",
                Map.of(
                        "text", "欢迎参加会议。",
                        "segments", List.of(Map.of(
                                "startMs", 0,
                                "endMs", 2_400,
                                "text", "欢迎参加会议。"
                        )),
                        "detectedLanguage", "zh"
                ),
                Map.of(
                        "sourceAssetId", assetId.toString(),
                        "speakerDiarization", false,
                        "timestampMode", "segment",
                        "professionalTermsFingerprint",
                        "e3b0c44298fc1c149afbf4c8996fb924"
                                + "27ae41e4649b934ca495991b7852b855"
                )
        );
        FeatureExecutionContext context = context(
                assetId,
                Map.of(
                        "audioFile", assetId.toString(),
                        "language", "auto",
                        "speakerDiarization", false,
                        "timestampMode", "segment",
                        "postProcess", "meeting_minutes"
                ),
                base
        );

        var artifact = new AudioTranscriptionFeatureHandler()
                .execute(context, gateway)
                .artifacts()
                .get(0);

        assertNull(gateway.audioRequest);
        assertEquals(true, artifact.metadata().get("transcriptReused"));
        assertEquals(base.id().toString(), artifact.metadata().get("basedOnArtifactId"));
        var supplement = Map.class.cast(artifact.content().get("supplement"));
        assertEquals("SUCCEEDED", supplement.get("status"));
        assertEquals(
                "# 会议纪要\n\n## 核心结论\n\n继续推进。",
                supplement.get("text")
        );
    }

    @Test
    void meetingMinutesPromptRequiresDecisionEvidenceAndCompleteTopicCoverage() {
        UUID assetId = UUID.randomUUID();
        CapturingGateway gateway = new CapturingGateway();
        gateway.transcription = transcription();
        gateway.textResponse = new TextGenerationResponse(
                "<!-- yuanzuo-output:meeting_minutes -->\n# \u4f1a\u8bae\u7eaa\u8981",
                "codex2api-relay",
                "gpt-5.4-mini",
                "text-2",
                100,
                50
        );
        FeatureExecutionContext context = context(
                assetId,
                Map.of(
                        "audioFile", assetId.toString(),
                        "language", "auto",
                        "speakerDiarization", false,
                        "timestampMode", "segment",
                        "postProcess", "meeting_minutes"
                )
        );

        new AudioTranscriptionFeatureHandler().execute(context, gateway);

        String systemPrompt = gateway.textRequest.systemPrompt();
        assertTrue(systemPrompt.contains("\u53ea\u6709\u9010\u5b57\u7a3f\u660e\u786e\u786e\u8ba4\u7684\u4e8b\u9879"));
        assertTrue(systemPrompt.contains("\u5efa\u8bae\u3001\u53ef\u4ee5\u3001\u8003\u8651"));
        assertTrue(systemPrompt.contains("\u4e0d\u5f97\u5199\u5165\u660e\u786e\u51b3\u7b56"));
        assertTrue(systemPrompt.contains("\u9010\u4e2a\u8986\u76d6\u6bcf\u4e2a\u8bae\u9898"));
        assertTrue(systemPrompt.contains("\u5b89\u5168\u3001\u5f02\u5e38\u3001\u98ce\u9669\u548c\u804c\u8d23"));
        assertTrue(systemPrompt.contains("\u8ba8\u8bba\u672a\u5b8c\u6210"));
        assertTrue(systemPrompt.contains("明显不是会议"));
    }

    @Test
    void meetingMinutesFallsBackToSummaryForClearlyNonMeetingContent() {
        UUID assetId = UUID.randomUUID();
        CapturingGateway gateway = new CapturingGateway();
        gateway.transcription = transcription();
        gateway.textResponse = new TextGenerationResponse(
                "<!-- yuanzuo-output:summary -->\n# 摘要\n\n这是一段单人知识讲解。",
                "zhipu-bigmodel",
                "glm-4.5-air",
                "text-summary",
                100,
                50
        );
        FeatureExecutionContext context = context(
                assetId,
                Map.of(
                        "audioFile", assetId.toString(),
                        "language", "auto",
                        "speakerDiarization", false,
                        "timestampMode", "segment",
                        "postProcess", "meeting_minutes"
                )
        );

        var artifact = new AudioTranscriptionFeatureHandler()
                .execute(context, gateway)
                .artifacts()
                .get(0);

        var supplement = Map.class.cast(artifact.content().get("supplement"));
        assertEquals("summary", supplement.get("type"));
        assertEquals("# 摘要\n\n这是一段单人知识讲解。", supplement.get("text"));
        assertTrue(gateway.textRequest.systemPrompt().contains("明显不是会议"));
        assertTrue(gateway.textRequest.userPrompt().contains("yuanzuo-output:summary"));
    }

    private static FeatureExecutionContext context(UUID assetId, Map<String, Object> parameters) {
        return context(assetId, parameters, null);
    }

    private static FeatureExecutionContext context(
            UUID assetId,
            Map<String, Object> parameters,
            ArtifactReference base
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                AudioTranscriptionFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                List.of(assetId),
                List.of(new InputAssetReference(
                        assetId,
                        "meeting.m4a",
                        "audio/mp4",
                        1_024
                )),
                Map.of(
                        ModelCapability.AUDIO_TRANSCRIPTION.name(),
                        "assemblyai-universal-3-5-pro-audio",
                        ModelCapability.TEXT_GENERATION.name(),
                        "zhipu-glm-4-5-air-text"
                ),
                null,
                base
        );
    }

    private static AudioTranscriptionResponse transcription() {
        return new AudioTranscriptionResponse(
                "欢迎参加会议。",
                List.of(new AudioTranscriptSegment(0, 2_400, "欢迎参加会议。", null, 0.97)),
                "zh",
                2.4,
                "assemblyai-official",
                "universal-3-5-pro",
                "transcript-1",
                3,
                null
        );
    }

    private static final class CapturingGateway implements ModelGateway {
        private AudioTranscriptionResponse transcription;
        private AudioTranscriptionRequest audioRequest;
        private TextGenerationRequest textRequest;
        private TextGenerationResponse textResponse;
        private ModelProviderException textFailure;

        @Override
        public TextGenerationResponse generateText(TextGenerationRequest request) {
            textRequest = request;
            if (textFailure != null) throw textFailure;
            if (textResponse != null) return textResponse;
            throw new AssertionError("Text generation was not expected");
        }

        @Override
        public AudioTranscriptionResponse transcribeAudio(AudioTranscriptionRequest request) {
            audioRequest = request;
            return transcription;
        }
    }
}
