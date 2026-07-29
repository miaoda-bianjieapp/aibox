package com.aibox.platform.asset;

import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.TabularAnalysisDataset;
import com.aibox.feature.spi.TabularAnalysisLimits;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetTabularAnalysisProcessorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void profilesUtf8CsvAndDetectsSupportedAnomalies() throws IOException {
        Path csv = temporaryDirectory.resolve("sales.csv");
        Files.writeString(csv, """
                日期,区域,销售额
                2026-01-01,华东,10,首日
                2026-01-02,华南,11
                2026-01-03,,12
                2026-01-04,华北,13
                2026-01-05,华东,14
                2026-01-06,华南,15
                2026-01-07,华北,16
                2026-01-08,华东,17
                2026-01-09,华南,1000
                2026-01-02,华南,11
                """, StandardCharsets.UTF_8);
        UUID assetId = UUID.randomUUID();
        AssetTabularAnalysisProcessor processor = processor(assetId, csv);

        TabularAnalysisDataset result = processor.analyze(
                assetId,
                new TabularAnalysisLimits(20, 100_000, 200, 500_000)
        );

        assertThat(result.format()).isEqualTo("csv");
        assertThat(result.totalRows()).isEqualTo(10);
        assertThat(result.totalNonEmptyCells()).isEqualTo(33);
        assertThat(result.sheets()).hasSize(1);
        assertThat(result.sheets().get(0).name()).isEqualTo("CSV");
        assertThat(result.sheets().get(0).columns()).hasSize(4);
        assertThat(result.sheets().get(0).columns().get(3).name()).isEqualTo("列4");
        assertThat(result.sheets().get(0).columns().get(3).nonEmptyCount()).isEqualTo(1);
        assertThat(result.sheets().get(0).duplicateRowCount()).isEqualTo(1);
        assertThat(result.chartCandidates()).isNotEmpty();
        Set<String> anomalyTypes = result.anomalies().stream()
                .map(TabularAnalysisDataset.Anomaly::type)
                .collect(Collectors.toSet());
        assertThat(anomalyTypes).contains(
                "MISSING_VALUES",
                "DUPLICATE_ROWS",
                "IQR_OUTLIER"
        );
    }

    @Test
    void keepsExcelCellsBeyondTheLastNamedHeaderColumn() throws IOException {
        Path workbookPath = temporaryDirectory.resolve("sales.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream output = Files.newOutputStream(workbookPath)) {
            var sheet = workbook.createSheet("销售");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("区域");
            header.createCell(1).setCellValue("销售额");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("华东");
            row.createCell(1).setCellValue(100);
            row.createCell(2).setCellValue("重点客户");
            workbook.write(output);
        }
        UUID assetId = UUID.randomUUID();
        AssetTabularAnalysisProcessor processor = processor(assetId, workbookPath);

        TabularAnalysisDataset result = processor.analyze(
                assetId,
                new TabularAnalysisLimits(20, 100_000, 200, 500_000)
        );

        assertThat(result.totalNonEmptyCells()).isEqualTo(5);
        assertThat(result.sheets().get(0).columns()).hasSize(3);
        assertThat(result.sheets().get(0).columns().get(2).name()).isEqualTo("列3");
        assertThat(result.sheets().get(0).columns().get(2).nonEmptyCount()).isEqualTo(1);
    }

    @Test
    void stopsReadingMillionRowCsvAsSoonAsRowLimitIsExceeded() throws IOException {
        Path csv = temporaryDirectory.resolve("million-rows.csv");
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(csv));
             Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writer.write("序号,内容\n");
            for (int index = 1; index <= 1_000_000; index++) {
                writer.write(Integer.toString(index));
                writer.write(",value\n");
            }
            writer.flush();
            output.write(0xC3);
        }
        UUID assetId = UUID.randomUUID();
        AssetTabularAnalysisProcessor processor = processor(assetId, csv);

        assertThatThrownBy(() -> processor.analyze(
                assetId,
                new TabularAnalysisLimits(20, 10, 200, 500_000)
        ))
                .isInstanceOf(FeatureValidationException.class)
                .hasMessageContaining("数据行总数不能超过 10");
    }

    private static AssetTabularAnalysisProcessor processor(
            UUID assetId,
            Path path
    ) {
        AssetService assetService = mock(AssetService.class);
        AssetService.AssetView asset = new AssetService.AssetView(
                assetId,
                path.getFileName().toString(),
                "text/csv",
                path.toFile().length(),
                "sha256",
                Instant.parse("2026-07-28T00:00:00Z")
        );
        when(assetService.openForPreview(assetId)).thenReturn(
                new AssetService.AssetStoredFile(asset, path)
        );
        return new AssetTabularAnalysisProcessor(assetService);
    }
}
