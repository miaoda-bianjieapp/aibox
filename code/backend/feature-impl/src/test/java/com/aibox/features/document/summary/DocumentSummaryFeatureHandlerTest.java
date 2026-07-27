package com.aibox.features.document.summary;

import com.aibox.feature.spi.ArtifactReference;
import com.aibox.feature.spi.DocumentContentExtractor;
import com.aibox.feature.spi.DocumentExtractionResult;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelAsset;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.MultimodalTextGenerationRequest;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.features.support.RecordingFeatureOutputEmitter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentSummaryFeatureHandlerTest {

    @Test
    void summarizesLocallyExtractedTextWithSelectedTextDeployment() {
        UUID assetId = UUID.randomUUID();
        DocumentContentExtractor extractor = (id, maximum) -> new DocumentExtractionResult(
                "第一章\n项目已经完成。\n第二章\n需要安排验收。",
                "docx",
                0,
                0,
                List.of(),
                List.of()
        );
        DocumentSummaryFeatureHandler handler = new DocumentSummaryFeatureHandler(extractor);
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return response();
        };
        RecordingFeatureOutputEmitter emitter = new RecordingFeatureOutputEmitter();
        FeatureExecutionContext context = context(assetId, "report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway, emitter);

        assertEquals("codex2api-gpt-5-6-sol-text", captured.get().deploymentCode());
        assertTrue(captured.get().userPrompt().contains("项目已经完成"));
        assertEquals("markdown", emitter.format());
        assertEquals(response().text(), emitter.content());
        assertEquals("rich_text", result.artifacts().get(0).kind());
        assertEquals(response().text(), result.artifacts().get(0).content().get("text"));
        assertEquals(false, result.artifacts().get(0).metadata().get("ocrApplied"));
        assertEquals(false, result.artifacts().get(0).metadata().get("visualAnalysisApplied"));
        assertEquals(false, result.artifacts().get(0).metadata().get("presentationRendered"));
    }

    @Test
    void summarizesScannedPdfWithSelectedVisionDeploymentAndInlinePages() {
        UUID assetId = UUID.randomUUID();
        ModelAsset page = new ModelAsset(
                UUID.randomUUID(),
                "page-0001.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3}
        );
        DocumentContentExtractor extractor = (id, maximum) -> new DocumentExtractionResult(
                "",
                "pdf",
                1,
                0,
                List.of(page),
                List.of(1)
        );
        DocumentSummaryFeatureHandler handler = new DocumentSummaryFeatureHandler(extractor);
        AtomicReference<MultimodalTextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new AssertionError("Scanned PDF must use the vision path");
            }

            @Override
            public TextGenerationResponse generateMultimodalText(
                    MultimodalTextGenerationRequest request
            ) {
                captured.set(request);
                return response();
            }
        };
        FeatureExecutionContext context = context(assetId, "scan.pdf", "application/pdf");

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(
                context,
                gateway,
                new RecordingFeatureOutputEmitter()
        );

        assertEquals("codex2api-gpt-5-6-sol-vision", captured.get().deploymentCode());
        assertEquals(List.of(page), captured.get().inlineInputAssets());
        assertTrue(captured.get().userPrompt().contains("第 [1] 页"));
        assertEquals(true, result.artifacts().get(0).metadata().get("ocrApplied"));
        assertEquals(true, result.artifacts().get(0).metadata().get("visualAnalysisApplied"));
        assertEquals(1, result.artifacts().get(0).metadata().get("visualPageCount"));
        assertEquals(false, result.artifacts().get(0).metadata().get("presentationRendered"));
    }

    @Test
    void summarizesImageBasedPresentationWithSelectedVisionDeployment() {
        UUID assetId = UUID.randomUUID();
        ModelAsset slide = new ModelAsset(
                UUID.randomUUID(),
                "slide-0001.jpg",
                "image/jpeg",
                new byte[]{4, 5, 6}
        );
        DocumentContentExtractor extractor = (id, maximum) -> new DocumentExtractionResult(
                "",
                "pptx",
                1,
                0,
                List.of(slide),
                List.of(1)
        );
        DocumentSummaryFeatureHandler handler = new DocumentSummaryFeatureHandler(extractor);
        AtomicReference<MultimodalTextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new AssertionError("Image-based presentation must use the vision path");
            }

            @Override
            public TextGenerationResponse generateMultimodalText(
                    MultimodalTextGenerationRequest request
            ) {
                captured.set(request);
                return response();
            }
        };
        FeatureExecutionContext context = context(
                assetId,
                "slides.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        );

        FeatureExecutionResult result = handler.execute(
                context,
                gateway,
                new RecordingFeatureOutputEmitter()
        );

        assertEquals("codex2api-gpt-5-6-sol-vision", captured.get().deploymentCode());
        assertEquals(List.of(slide), captured.get().inlineInputAssets());
        assertTrue(captured.get().userPrompt().contains("PowerPoint"));
        assertTrue(captured.get().userPrompt().contains("[1]"));
        assertEquals(true, result.artifacts().get(0).metadata().get("visualAnalysisApplied"));
        assertEquals(1, result.artifacts().get(0).metadata().get("visualPageCount"));
        assertEquals(true, result.artifacts().get(0).metadata().get("presentationRendered"));
        assertEquals(false, result.artifacts().get(0).metadata().get("ocrApplied"));
    }

    @Test
    void includesBaseArtifactWithoutOverwritingIt() {
        UUID assetId = UUID.randomUUID();
        DocumentSummaryFeatureHandler handler = new DocumentSummaryFeatureHandler(
                (id, maximum) -> new DocumentExtractionResult(
                        "更新后的原始正文",
                        "pdf",
                        1,
                        0,
                        List.of(),
                        List.of()
                )
        );
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return response();
        };
        ArtifactReference base = new ArtifactReference(
                UUID.randomUUID(),
                2,
                "rich_text",
                "text/markdown",
                Map.of("text", "# 旧摘要"),
                Map.of()
        );
        FeatureExecutionContext context = context(
                assetId,
                "report.pdf",
                "application/pdf",
                base
        );

        FeatureExecutionResult result = handler.execute(
                context,
                gateway,
                new RecordingFeatureOutputEmitter()
        );

        assertTrue(captured.get().userPrompt().contains("# 旧摘要"));
        assertEquals(
                base.id().toString(),
                result.artifacts().get(0).metadata().get("basedOnArtifactId")
        );
        assertFalse(result.artifacts().get(0).metadata().containsKey("focus"));
    }

    @Test
    void rejectsMoreThanOneDocumentAndOversizedFiles() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        DocumentSummaryFeatureHandler handler = new DocumentSummaryFeatureHandler(
                (id, maximum) -> {
                    throw new AssertionError("Validation should fail first");
                }
        );
        FeatureExecutionContext multiple = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentSummaryFeatureHandler.FEATURE_CODE,
                1,
                Map.of("document", first.toString(), "summaryDepth", "standard"),
                List.of(first, second),
                List.of(
                        new InputAssetReference(first, "one.pdf", "application/pdf", 100),
                        new InputAssetReference(second, "two.pdf", "application/pdf", 100)
                ),
                models(),
                null,
                null
        );
        FeatureExecutionContext oversized = new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentSummaryFeatureHandler.FEATURE_CODE,
                1,
                Map.of("document", first.toString(), "summaryDepth", "standard"),
                List.of(first),
                List.of(new InputAssetReference(
                        first,
                        "large.pdf",
                        "application/pdf",
                        50L * 1024 * 1024 + 1
                )),
                models(),
                null,
                null
        );

        assertThrows(FeatureValidationException.class, () -> handler.validate(multiple));
        assertThrows(FeatureValidationException.class, () -> handler.validate(oversized));
    }

    @Test
    void acceptsTheAdditionalDocumentFormats() {
        DocumentSummaryFeatureHandler handler = new DocumentSummaryFeatureHandler(
                (id, maximum) -> {
                    throw new AssertionError("Validation should not extract content");
                }
        );
        List<String[]> formats = List.of(
                new String[]{"notes.md", "text/markdown"},
                new String[]{"notes.txt", "text/plain"},
                new String[]{"data.json", "application/json"},
                new String[]{"slides.ppt", "application/vnd.ms-powerpoint"},
                new String[]{
                        "slides.pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                }
        );

        for (String[] format : formats) {
            UUID assetId = UUID.randomUUID();
            handler.validate(context(assetId, format[0], format[1]));
        }
    }

    private static FeatureExecutionContext context(
            UUID assetId,
            String fileName,
            String mediaType
    ) {
        return context(assetId, fileName, mediaType, null);
    }

    private static FeatureExecutionContext context(
            UUID assetId,
            String fileName,
            String mediaType,
            ArtifactReference baseArtifact
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentSummaryFeatureHandler.FEATURE_CODE,
                3,
                Map.of(
                        "document", assetId.toString(),
                        "summaryDepth", "standard",
                        "focus", "重点关注结论"
                ),
                List.of(assetId),
                List.of(new InputAssetReference(assetId, fileName, mediaType, 1024)),
                models(),
                null,
                baseArtifact
        );
    }

    private static Map<String, String> models() {
        return Map.of(
                "TEXT_GENERATION", "codex2api-gpt-5-6-sol-text",
                "VISION", "codex2api-gpt-5-6-sol-vision"
        );
    }

    private static TextGenerationResponse response() {
        return new TextGenerationResponse(
                "# 摘要\n内容\n# 章节要点\n- 要点\n# 结论\n结论\n# 行动项\n- 验收",
                "test-provider",
                "test-model",
                "request-1",
                100,
                50
        );
    }
}
