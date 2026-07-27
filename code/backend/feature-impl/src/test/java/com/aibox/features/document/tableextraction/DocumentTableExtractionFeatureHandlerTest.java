package com.aibox.features.document.tableextraction;

import com.aibox.feature.spi.DocumentContentExtractor;
import com.aibox.feature.spi.DocumentExtractionResult;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.MultimodalTextGenerationRequest;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.features.support.RecordingFeatureOutputEmitter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTableExtractionFeatureHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void extractsImageTableWithSelectedVisionDeploymentAndCreatesExcelAsset() throws Exception {
        UUID assetId = UUID.randomUUID();
        DocumentTableExtractionFeatureHandler handler = handler();
        AtomicReference<MultimodalTextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                throw new AssertionError("Valid vision JSON must not require text repair");
            }

            @Override
            public TextGenerationResponse generateMultimodalText(
                    MultimodalTextGenerationRequest request
            ) {
                captured.set(request);
                return response("""
                        {
                          "tables":[{
                            "name":"销售明细",
                            "columns":["商品","数量"],
                            "rows":[["苹果","2"]],
                            "sourcePages":[1],
                            "confidence":0.96,
                            "warnings":[]
                          }],
                          "fields":[],
                          "warnings":[]
                        }
                        """);
            }
        };
        FeatureExecutionContext context = context(
                assetId,
                "receipt.png",
                "image/png",
                Map.of(
                        "document", assetId.toString(),
                        "extractionMode", "table",
                        "outputFormat", "xlsx"
                )
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(
                context,
                gateway,
                new RecordingFeatureOutputEmitter()
        );

        assertEquals("codex2api-gpt-5-6-sol-vision", captured.get().deploymentCode());
        assertEquals(List.of(assetId), captured.get().inputAssetIds());
        assertEquals("file", result.artifacts().get(0).kind());
        assertEquals(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                result.artifacts().get(0).mimeType()
        );
        assertEquals(1, result.artifacts().get(0).content().get("tableCount"));
        assertEquals(1, result.artifacts().get(0).outputAssets().size());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                result.artifacts().get(0).outputAssets().get(0).content()
        ))) {
            assertTrue(workbook.getSheetIndex("提取信息") >= 0);
            assertTrue(workbook.getSheetIndex("销售明细") >= 0);
            assertEquals(
                    "苹果",
                    workbook.getSheet("销售明细").getRow(1).getCell(0).getStringCellValue()
            );
        }
    }

    @Test
    void fillsMissingRequestedFieldsAndUsesSelectedTextModelForJsonRepair() throws Exception {
        UUID assetId = UUID.randomUUID();
        DocumentTableExtractionFeatureHandler handler = handler();
        AtomicReference<TextGenerationRequest> repair = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public TextGenerationResponse generateText(TextGenerationRequest request) {
                repair.set(request);
                return response("""
                        {
                          "tables":[],
                          "fields":[{
                            "name":"发票号码",
                            "value":"A-001",
                            "sourcePages":[1],
                            "confidence":0.91,
                            "warnings":[]
                          }],
                          "warnings":[]
                        }
                        """);
            }

            @Override
            public TextGenerationResponse generateMultimodalText(
                    MultimodalTextGenerationRequest request
            ) {
                return response("not-json");
            }
        };
        FeatureExecutionContext context = context(
                assetId,
                "invoice.jpg",
                "image/jpeg",
                Map.of(
                        "document", assetId.toString(),
                        "extractionMode", "fields",
                        "fieldNames", "发票号码\n金额",
                        "outputFormat", "json"
                )
        );

        FeatureExecutionResult result = handler.execute(
                context,
                gateway,
                new RecordingFeatureOutputEmitter()
        );

        assertEquals("codex2api-gpt-5-6-sol-text", repair.get().deploymentCode());
        JsonNode output = OBJECT_MAPPER.readTree(
                result.artifacts().get(0).outputAssets().get(0).content()
        );
        assertEquals(2, output.path("fields").size());
        assertEquals("A-001", output.path("fields").get(0).path("value").asText());
        assertEquals("无法识别", output.path("fields").get(1).path("value").asText());
        assertEquals(2, result.artifacts().get(0).metadata().get("providerInvocationCount"));
    }

    private static DocumentTableExtractionFeatureHandler handler() {
        DocumentContentExtractor extractor = (id, maximum) -> new DocumentExtractionResult(
                "",
                "pdf",
                1,
                0,
                List.of(),
                List.of()
        );
        return new DocumentTableExtractionFeatureHandler(extractor, OBJECT_MAPPER);
    }

    private static FeatureExecutionContext context(
            UUID assetId,
            String fileName,
            String mediaType,
            Map<String, Object> parameters
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentTableExtractionFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                List.of(assetId),
                List.of(new InputAssetReference(assetId, fileName, mediaType, 1024)),
                Map.of(
                        "TEXT_GENERATION", "codex2api-gpt-5-6-sol-text",
                        "VISION", "codex2api-gpt-5-6-sol-vision"
                ),
                null,
                null
        );
    }

    private static TextGenerationResponse response(String text) {
        return new TextGenerationResponse(
                text,
                "test-provider",
                "test-model",
                UUID.randomUUID().toString(),
                100,
                50
        );
    }
}
