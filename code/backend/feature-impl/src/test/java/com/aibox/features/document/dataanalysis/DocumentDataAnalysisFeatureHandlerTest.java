package com.aibox.features.document.dataanalysis;

import com.aibox.feature.spi.ArtifactDraft;
import com.aibox.feature.spi.FeatureExecutionContext;
import com.aibox.feature.spi.FeatureExecutionResult;
import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.InputAssetReference;
import com.aibox.feature.spi.ModelGateway;
import com.aibox.feature.spi.OutputAssetDraft;
import com.aibox.feature.spi.TabularAnalysisDataset;
import com.aibox.feature.spi.TabularAnalysisProcessor;
import com.aibox.feature.spi.TextGenerationRequest;
import com.aibox.feature.spi.TextGenerationResponse;
import com.aibox.features.support.RecordingFeatureOutputEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentDataAnalysisFeatureHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void createsCompositeArtifactWithChartsAndXlsxReport() throws Exception {
        UUID assetId = UUID.randomUUID();
        TabularAnalysisProcessor processor = (id, limits) -> dataset();
        DocumentDataAnalysisFeatureHandler handler =
                new DocumentDataAnalysisFeatureHandler(processor, OBJECT_MAPPER);
        AtomicReference<TextGenerationRequest> captured = new AtomicReference<>();
        ModelGateway gateway = request -> {
            captured.set(request);
            return response("""
                    {
                      "summaryMarkdown":"销售数据整体稳定。",
                      "conclusions":[{
                        "title":"华东销售领先",
                        "detail":"华东销售额高于华南。",
                        "evidence":["工作表：销售；图表候选：chart-1"]
                      }],
                      "anomalyNotes":[{
                        "anomalyId":"A1",
                        "interpretation":"区域字段存在缺失。",
                        "suggestion":"补齐区域后重新分析。"
                      }],
                      "selectedChartIds":["chart-1"],
                      "warnings":[]
                    }
                    """);
        };
        RecordingFeatureOutputEmitter emitter = new RecordingFeatureOutputEmitter();
        FeatureExecutionContext context = context(
                assetId,
                Map.of(
                        "dataFile", assetId.toString(),
                        "focus", "关注区域销售差异"
                )
        );

        handler.validate(context);
        FeatureExecutionResult result = handler.execute(context, gateway, emitter);

        assertEquals("text.document-data-analysis", captured.get().modelAlias());
        assertEquals("codex2api-gpt-5-6-sol-text", captured.get().deploymentCode());
        assertTrue(captured.get().userPrompt().contains("关注区域销售差异"));
        assertEquals("数据分析完成", emitter.content());

        ArtifactDraft artifact = result.artifacts().get(0);
        assertEquals("data_analysis", artifact.kind());
        assertEquals("application/vnd.yuanzuo.data-analysis+json", artifact.mimeType());
        assertEquals("data_analysis", artifact.content().get("format"));
        assertEquals(false, artifact.content().get("partial"));
        assertEquals(1, ((List<?>) artifact.content().get("charts")).size());
        assertEquals(1, ((List<?>) artifact.content().get("anomalies")).size());
        assertEquals(2, artifact.outputAssets().size());
        assertEquals(
                List.of("chartAssetIds", "reportAssetId"),
                artifact.outputAssets().stream().map(OutputAssetDraft::contentField).toList()
        );
        assertEquals("image/png", artifact.outputAssets().get(0).mediaType());
        assertTrue(artifact.outputAssets().get(0).content().length > 100);

        OutputAssetDraft report = artifact.outputAssets().get(1);
        assertEquals(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                report.mediaType()
        );
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(report.content())
        )) {
            assertTrue(workbook.getSheetIndex("分析概览") >= 0);
            assertTrue(workbook.getSheetIndex("字段统计") >= 0);
            assertTrue(workbook.getSheetIndex("异常明细") >= 0);
            assertTrue(workbook.getSheetIndex("图表") >= 0);
        }
    }

    @Test
    void rejectsFocusLongerThanFiveHundredCharacters() {
        UUID assetId = UUID.randomUUID();
        DocumentDataAnalysisFeatureHandler handler =
                new DocumentDataAnalysisFeatureHandler((id, limits) -> dataset(), OBJECT_MAPPER);
        FeatureExecutionContext context = context(
                assetId,
                Map.of(
                        "dataFile", assetId.toString(),
                        "focus", "数".repeat(501)
                )
        );

        FeatureValidationException exception = assertThrows(
                FeatureValidationException.class,
                () -> handler.validate(context)
        );

        assertEquals("focus", exception.field());
    }

    private static FeatureExecutionContext context(
            UUID assetId,
            Map<String, Object> parameters
    ) {
        return new FeatureExecutionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                DocumentDataAnalysisFeatureHandler.FEATURE_CODE,
                1,
                parameters,
                List.of(assetId),
                List.of(new InputAssetReference(
                        assetId,
                        "sales.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        2048
                )),
                Map.of("TEXT_GENERATION", "codex2api-gpt-5-6-sol-text"),
                null,
                null
        );
    }

    private static TabularAnalysisDataset dataset() {
        TabularAnalysisDataset.ColumnProfile region =
                new TabularAnalysisDataset.ColumnProfile(
                        "区域",
                        "TEXT",
                        2,
                        1,
                        2,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(
                                new TabularAnalysisDataset.ValueFrequency("华东", 1),
                                new TabularAnalysisDataset.ValueFrequency("华南", 1)
                        )
                );
        TabularAnalysisDataset.ColumnProfile sales =
                new TabularAnalysisDataset.ColumnProfile(
                        "销售额",
                        "NUMBER",
                        3,
                        0,
                        3,
                        80.0,
                        120.0,
                        100.0,
                        100.0,
                        90.0,
                        110.0,
                        List.of()
                );
        return new TabularAnalysisDataset(
                "xlsx",
                List.of(new TabularAnalysisDataset.SheetProfile(
                        "销售",
                        3,
                        2,
                        0,
                        List.of(region, sales)
                )),
                List.of(new TabularAnalysisDataset.Anomaly(
                        "A1",
                        "MISSING_VALUE",
                        "WARNING",
                        "销售",
                        "区域",
                        null,
                        "区域字段存在缺失值",
                        "缺失数量：1/3"
                )),
                List.of(new TabularAnalysisDataset.ChartCandidate(
                        "chart-1",
                        "区域销售额",
                        "BAR",
                        "销售",
                        "区域",
                        "销售额",
                        List.of("华东", "华南"),
                        List.of(120.0, 80.0),
                        "SUM"
                )),
                3,
                5,
                List.of()
        );
    }

    private static TextGenerationResponse response(String text) {
        return new TextGenerationResponse(
                text,
                "test-provider",
                "test-model",
                UUID.randomUUID().toString(),
                300,
                120
        );
    }
}
