package com.aibox.platform.asset;

import com.aibox.feature.spi.FeatureValidationException;
import com.aibox.feature.spi.TabularAnalysisDataset;
import com.aibox.feature.spi.TabularAnalysisLimits;
import com.aibox.feature.spi.TabularAnalysisProcessor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class AssetTabularAnalysisProcessor implements TabularAnalysisProcessor {

    private static final int MAX_ANOMALIES = 200;
    private static final int MAX_CHART_CANDIDATES = 12;
    private static final int MAX_CHART_POINTS = 120;
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy")
    );

    private final AssetService assetService;

    public AssetTabularAnalysisProcessor(AssetService assetService) {
        this.assetService = assetService;
    }

    @Override
    @Transactional(readOnly = true)
    public TabularAnalysisDataset analyze(UUID assetId, TabularAnalysisLimits limits) {
        AssetService.AssetStoredFile stored = assetService.openForPreview(assetId);
        String extension = extension(stored.asset().name());
        ParsedDataset parsed = switch (extension) {
            case ".xls", ".xlsx" -> parseWorkbook(stored.path(), extension, limits);
            case ".csv" -> parseCsv(stored.path(), limits);
            default -> throw invalidFile("仅支持 XLS、XLSX 和 CSV 数据文件");
        };
        return profile(parsed, limits);
    }

    private ParsedDataset parseWorkbook(
            Path path,
            String extension,
            TabularAnalysisLimits limits
    ) {
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            List<ParsedSheet> sheets = new ArrayList<>();
            Counters counters = new Counters(limits);
            int visibleSheets = 0;
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) continue;
                visibleSheets++;
                if (visibleSheets > limits.maxVisibleSheets()) {
                    throw invalidFile(
                            "可见工作表不能超过 " + limits.maxVisibleSheets() + " 个"
                    );
                }
                sheets.add(parseSheet(workbook.getSheetAt(index), formatter, counters));
            }
            if (sheets.isEmpty()) {
                throw invalidFile("数据文件没有可读取的可见工作表");
            }
            return new ParsedDataset(
                    extension.substring(1),
                    List.copyOf(sheets),
                    counters.rows,
                    counters.nonEmptyCells
            );
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidFile("Excel 文件已损坏、受密码保护或无法读取");
        }
    }

    private ParsedSheet parseSheet(
            Sheet sheet,
            DataFormatter formatter,
            Counters counters
    ) {
        Row headerRow = firstNonEmptyRow(sheet, formatter);
        if (headerRow == null) {
            return new ParsedSheet(sheet.getSheetName(), List.of("列1"), List.of());
        }
        int width = effectiveWidth(headerRow, formatter);
        if (width > counters.limits.maxColumnsPerSheet()) {
            throw invalidFile(
                    "工作表“" + sheet.getSheetName() + "”列数不能超过 "
                            + counters.limits.maxColumnsPerSheet()
            );
        }
        for (int rowIndex = headerRow.getRowNum() + 1;
             rowIndex <= sheet.getLastRowNum();
             rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            int rowWidth = effectiveWidth(row, formatter);
            if (rowWidth > counters.limits.maxColumnsPerSheet()) {
                throw invalidFile(
                        "工作表“" + sheet.getSheetName() + "”列数不能超过 "
                                + counters.limits.maxColumnsPerSheet()
                );
            }
            width = Math.max(width, rowWidth);
        }
        width = Math.max(1, width);
        List<CellValue> headerValues = values(headerRow, width, formatter);
        List<String> headers = normalizeHeaders(headerValues.stream()
                .map(CellValue::display)
                .toList());
        counters.addCells(headerValues.stream().filter(CellValue::nonEmpty).count());
        List<DataRow> rows = new ArrayList<>();
        for (int rowIndex = headerRow.getRowNum() + 1;
             rowIndex <= sheet.getLastRowNum();
             rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            List<CellValue> cells = values(row, width, formatter);
            long nonEmpty = cells.stream().filter(CellValue::nonEmpty).count();
            if (nonEmpty == 0) continue;
            counters.addRow();
            counters.addCells(nonEmpty);
            rows.add(new DataRow(rowIndex + 1, cells));
        }
        return new ParsedSheet(sheet.getSheetName(), headers, List.copyOf(rows));
    }

    private ParsedDataset parseCsv(Path path, TabularAnalysisLimits limits) {
        try (InputStream input = Files.newInputStream(path);
             Reader decoded = new InputStreamReader(
                     input,
                     StandardCharsets.UTF_8.newDecoder()
                             .onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT)
             );
             PushbackReader reader = withoutUtf8Bom(decoded);
             CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {
            CSVRecord headerRecord = null;
            List<CSVRecord> dataRecords = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (record.stream().allMatch(value -> value == null || value.isBlank())) continue;
                if (headerRecord == null) {
                    headerRecord = record;
                } else {
                    dataRecords.add(record);
                }
            }
            if (headerRecord == null) {
                throw invalidFile("CSV 没有可读取的数据");
            }
            int width = headerRecord.size();
            for (CSVRecord record : dataRecords) {
                width = Math.max(width, record.size());
            }
            if (width > limits.maxColumnsPerSheet()) {
                throw invalidFile("CSV 列数不能超过 " + limits.maxColumnsPerSheet());
            }
            width = Math.max(1, width);
            List<CellValue> headerValues = recordValues(headerRecord, width);
            List<String> headers = normalizeHeaders(headerValues.stream()
                    .map(CellValue::display)
                    .toList());
            Counters counters = new Counters(limits);
            counters.addCells(headerValues.stream().filter(CellValue::nonEmpty).count());
            List<DataRow> rows = new ArrayList<>();
            for (CSVRecord record : dataRecords) {
                List<CellValue> cells = recordValues(record, width);
                long nonEmpty = cells.stream().filter(CellValue::nonEmpty).count();
                if (nonEmpty == 0) continue;
                counters.addRow();
                counters.addCells(nonEmpty);
                rows.add(new DataRow(Math.toIntExact(record.getRecordNumber()), cells));
            }
            return new ParsedDataset(
                    "csv",
                    List.of(new ParsedSheet("CSV", headers, List.copyOf(rows))),
                    counters.rows,
                    counters.nonEmptyCells
            );
        } catch (CharacterCodingException exception) {
            throw invalidFile("CSV 仅支持 UTF-8 或 UTF-8 BOM，请转换编码后重试");
        } catch (FeatureValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            if (hasCharacterCodingCause(exception)) {
                throw invalidFile("CSV 仅支持 UTF-8 或 UTF-8 BOM，请转换编码后重试");
            }
            throw invalidFile("CSV 已损坏或无法解析");
        }
    }

    private TabularAnalysisDataset profile(
            ParsedDataset parsed,
            TabularAnalysisLimits limits
    ) {
        List<TabularAnalysisDataset.SheetProfile> sheetProfiles = new ArrayList<>();
        List<TabularAnalysisDataset.Anomaly> anomalies = new ArrayList<>();
        List<TabularAnalysisDataset.ChartCandidate> charts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        AtomicInteger anomalySequence = new AtomicInteger();
        AtomicInteger chartSequence = new AtomicInteger();
        int nonEmptySheetCount = 0;

        for (ParsedSheet sheet : parsed.sheets) {
            if (!sheet.rows.isEmpty()) nonEmptySheetCount++;
            SheetAnalysis analysis = analyzeSheet(sheet);
            sheetProfiles.add(analysis.profile);
            appendAnomalies(
                    anomalies,
                    analysis,
                    anomalySequence,
                    warnings
            );
            appendCharts(charts, analysis, chartSequence);
        }
        if (nonEmptySheetCount == 0 || parsed.totalRows == 0) {
            throw invalidFile("数据文件没有可分析的数据行");
        }
        if (charts.isEmpty()) {
            charts.add(fallbackDatasetChart(parsed, chartSequence.incrementAndGet()));
        }
        if (charts.size() > MAX_CHART_CANDIDATES) {
            charts = new ArrayList<>(charts.subList(0, MAX_CHART_CANDIDATES));
        }
        return new TabularAnalysisDataset(
                parsed.format,
                sheetProfiles,
                anomalies,
                charts,
                parsed.totalRows,
                parsed.totalNonEmptyCells,
                warnings
        );
    }

    private SheetAnalysis analyzeSheet(ParsedSheet sheet) {
        int width = sheet.headers.size();
        List<ColumnAnalysis> columns = new ArrayList<>(width);
        for (int column = 0; column < width; column++) {
            columns.add(analyzeColumn(sheet, column));
        }
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        for (DataRow row : sheet.rows) {
            String key = row.cells.stream()
                    .map(cell -> cell.display.strip().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining("\u001F"));
            rowCounts.merge(key, 1, Integer::sum);
        }
        int duplicateRows = rowCounts.values().stream()
                .filter(count -> count > 1)
                .mapToInt(count -> count - 1)
                .sum();
        return new SheetAnalysis(
                sheet,
                columns,
                duplicateRows,
                new TabularAnalysisDataset.SheetProfile(
                        sheet.name,
                        sheet.rows.size(),
                        width,
                        duplicateRows,
                        columns.stream().map(ColumnAnalysis::profile).toList()
                )
        );
    }

    private ColumnAnalysis analyzeColumn(ParsedSheet sheet, int columnIndex) {
        List<ObservedValue> observed = new ArrayList<>();
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        Set<ValueType> types = new LinkedHashSet<>();
        for (DataRow row : sheet.rows) {
            CellValue cell = row.cells.get(columnIndex);
            if (!cell.nonEmpty()) continue;
            observed.add(new ObservedValue(row.sourceRowNumber, cell));
            types.add(cell.type);
            frequencies.merge(cell.display, 1, Integer::sum);
        }
        List<Double> numeric = observed.stream()
                .map(ObservedValue::value)
                .filter(value -> value.number != null)
                .map(value -> value.number)
                .sorted()
                .toList();
        String type = types.isEmpty()
                ? "EMPTY"
                : types.size() == 1 ? types.iterator().next().name() : "MIXED";
        Double minimum = numeric.isEmpty() ? null : numeric.get(0);
        Double maximum = numeric.isEmpty() ? null : numeric.get(numeric.size() - 1);
        Double mean = numeric.isEmpty()
                ? null
                : numeric.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        Double median = numeric.isEmpty() ? null : quantile(numeric, 0.5);
        Double q1 = numeric.isEmpty() ? null : quantile(numeric, 0.25);
        Double q3 = numeric.isEmpty() ? null : quantile(numeric, 0.75);
        List<TabularAnalysisDataset.ValueFrequency> topValues = frequencies.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .map(entry -> new TabularAnalysisDataset.ValueFrequency(
                        abbreviate(entry.getKey(), 120),
                        entry.getValue()
                ))
                .toList();
        return new ColumnAnalysis(
                columnIndex,
                sheet.headers.get(columnIndex),
                observed,
                types,
                frequencies,
                numeric,
                new TabularAnalysisDataset.ColumnProfile(
                        sheet.headers.get(columnIndex),
                        type,
                        observed.size(),
                        sheet.rows.size() - observed.size(),
                        frequencies.size(),
                        minimum,
                        maximum,
                        mean,
                        median,
                        q1,
                        q3,
                        topValues
                )
        );
    }

    private void appendAnomalies(
            List<TabularAnalysisDataset.Anomaly> target,
            SheetAnalysis analysis,
            AtomicInteger sequence,
            List<String> warnings
    ) {
        if (analysis.duplicateRows > 0) {
            addAnomaly(target, sequence, new AnomalyDraft(
                    "DUPLICATE_ROWS",
                    "WARNING",
                    analysis.sheet.name,
                    "",
                    null,
                    "发现重复数据行",
                    "重复行数量：" + analysis.duplicateRows
            ));
        }
        for (ColumnAnalysis column : analysis.columns) {
            int rowCount = analysis.sheet.rows.size();
            int missing = column.profile.missingCount();
            if (missing > 0) {
                double ratio = rowCount == 0 ? 0 : (double) missing / rowCount;
                addAnomaly(target, sequence, new AnomalyDraft(
                        "MISSING_VALUES",
                        ratio >= 0.2 ? "WARNING" : "INFO",
                        analysis.sheet.name,
                        column.name,
                        null,
                        "字段存在缺失值",
                        missing + "/" + rowCount + "，缺失率 "
                                + String.format(Locale.ROOT, "%.1f%%", ratio * 100)
                ));
            }
            if (column.types.size() > 1) {
                addAnomaly(target, sequence, new AnomalyDraft(
                        "TYPE_INCONSISTENCY",
                        "WARNING",
                        analysis.sheet.name,
                        column.name,
                        null,
                        "字段包含不一致的数据类型",
                        "识别类型：" + column.types
                ));
            }
            appendOutliers(target, analysis.sheet, column, sequence);
        }
        appendTimeJumps(target, analysis, sequence);
        if (target.size() >= MAX_ANOMALIES
                && warnings.stream().noneMatch(value -> value.contains("异常数量"))) {
            warnings.add("异常数量超过展示上限，仅保留前 " + MAX_ANOMALIES + " 项");
        }
    }

    private void appendOutliers(
            List<TabularAnalysisDataset.Anomaly> target,
            ParsedSheet sheet,
            ColumnAnalysis column,
            AtomicInteger sequence
    ) {
        if (column.numeric.size() < 8) return;
        double q1 = quantile(column.numeric, 0.25);
        double q3 = quantile(column.numeric, 0.75);
        double iqr = q3 - q1;
        if (!Double.isFinite(iqr) || iqr <= 0) return;
        double lower = q1 - 1.5 * iqr;
        double upper = q3 + 1.5 * iqr;
        for (ObservedValue observed : column.observed) {
            if (target.size() >= MAX_ANOMALIES) return;
            Double value = observed.value.number;
            if (value == null || (value >= lower && value <= upper)) continue;
            addAnomaly(target, sequence, new AnomalyDraft(
                    "IQR_OUTLIER",
                    "WARNING",
                    sheet.name,
                    column.name,
                    observed.rowNumber,
                    "数值超出 IQR 正常范围",
                    "值=" + formatNumber(value)
                            + "，正常范围=" + formatNumber(lower)
                            + " 至 " + formatNumber(upper)
            ));
        }
    }

    private void appendTimeJumps(
            List<TabularAnalysisDataset.Anomaly> target,
            SheetAnalysis analysis,
            AtomicInteger sequence
    ) {
        ColumnAnalysis dateColumn = analysis.columns.stream()
                .filter(column -> column.types.equals(Set.of(ValueType.DATE)))
                .findFirst()
                .orElse(null);
        if (dateColumn == null) return;
        for (ColumnAnalysis numericColumn : analysis.columns.stream()
                .filter(column -> column.types.equals(Set.of(ValueType.NUMBER)))
                .limit(2)
                .toList()) {
            Map<Integer, LocalDate> datesByRow = dateColumn.observed.stream()
                    .filter(value -> value.value.date != null)
                    .collect(Collectors.toMap(
                            ObservedValue::rowNumber,
                            value -> value.value.date,
                            (left, right) -> left
                    ));
            List<TimePoint> points = numericColumn.observed.stream()
                    .filter(value -> value.value.number != null)
                    .filter(value -> datesByRow.containsKey(value.rowNumber))
                    .map(value -> new TimePoint(
                            value.rowNumber,
                            datesByRow.get(value.rowNumber),
                            value.value.number
                    ))
                    .sorted(Comparator.comparing(TimePoint::date))
                    .toList();
            if (points.size() < 8) continue;
            List<Double> changes = new ArrayList<>();
            for (int index = 1; index < points.size(); index++) {
                changes.add(Math.abs(points.get(index).value - points.get(index - 1).value));
            }
            List<Double> sorted = changes.stream().sorted().toList();
            double q1 = quantile(sorted, 0.25);
            double q3 = quantile(sorted, 0.75);
            double iqr = q3 - q1;
            double threshold = iqr > 0
                    ? q3 + 1.5 * iqr
                    : Math.max(0, q3 * 3);
            if (threshold <= 0) continue;
            for (int index = 1; index < points.size(); index++) {
                double change = Math.abs(points.get(index).value - points.get(index - 1).value);
                if (change <= threshold || target.size() >= MAX_ANOMALIES) continue;
                TimePoint point = points.get(index);
                addAnomaly(target, sequence, new AnomalyDraft(
                        "TIME_SERIES_JUMP",
                        "WARNING",
                        analysis.sheet.name,
                        numericColumn.name,
                        point.rowNumber,
                        "时间序列出现异常涨跌",
                        point.date + " 相邻变化=" + formatNumber(change)
                                + "，阈值=" + formatNumber(threshold)
                ));
            }
        }
    }

    private void appendCharts(
            List<TabularAnalysisDataset.ChartCandidate> target,
            SheetAnalysis analysis,
            AtomicInteger sequence
    ) {
        if (analysis.sheet.rows.isEmpty() || target.size() >= MAX_CHART_CANDIDATES) return;
        ColumnAnalysis dateColumn = analysis.columns.stream()
                .filter(column -> column.types.equals(Set.of(ValueType.DATE)))
                .findFirst()
                .orElse(null);
        ColumnAnalysis numericColumn = analysis.columns.stream()
                .filter(column -> column.types.equals(Set.of(ValueType.NUMBER)))
                .findFirst()
                .orElse(null);
        ColumnAnalysis categoryColumn = analysis.columns.stream()
                .filter(column -> Set.of("TEXT", "BOOLEAN").contains(column.profile.type()))
                .filter(column -> column.profile.distinctCount() >= 2
                        && column.profile.distinctCount() <= 50)
                .findFirst()
                .orElse(null);

        if (dateColumn != null && numericColumn != null) {
            TabularAnalysisDataset.ChartCandidate line = timeChart(
                    analysis,
                    dateColumn,
                    numericColumn,
                    sequence.incrementAndGet()
            );
            if (line != null) target.add(line);
        }
        if (categoryColumn != null && numericColumn != null
                && target.size() < MAX_CHART_CANDIDATES) {
            TabularAnalysisDataset.ChartCandidate comparison = categoryAverageChart(
                    analysis,
                    categoryColumn,
                    numericColumn,
                    sequence.incrementAndGet()
            );
            if (comparison != null) target.add(comparison);
        }
        if (categoryColumn != null && target.size() < MAX_CHART_CANDIDATES) {
            target.add(categoryCountChart(
                    analysis,
                    categoryColumn,
                    sequence.incrementAndGet()
            ));
        }
        if (target.size() < MAX_CHART_CANDIDATES) {
            target.add(completenessChart(analysis, sequence.incrementAndGet()));
        }
    }

    private TabularAnalysisDataset.ChartCandidate timeChart(
            SheetAnalysis analysis,
            ColumnAnalysis dateColumn,
            ColumnAnalysis numericColumn,
            int sequence
    ) {
        Map<Integer, LocalDate> datesByRow = dateColumn.observed.stream()
                .filter(value -> value.value.date != null)
                .collect(Collectors.toMap(
                        ObservedValue::rowNumber,
                        value -> value.value.date,
                        (left, right) -> left
                ));
        Map<LocalDate, List<Double>> values = new LinkedHashMap<>();
        numericColumn.observed.stream()
                .filter(value -> value.value.number != null)
                .filter(value -> datesByRow.containsKey(value.rowNumber))
                .sorted(Comparator.comparing(value -> datesByRow.get(value.rowNumber)))
                .forEach(value -> values
                        .computeIfAbsent(datesByRow.get(value.rowNumber), ignored -> new ArrayList<>())
                        .add(value.value.number));
        if (values.size() < 2) return null;
        List<Map.Entry<LocalDate, Double>> points = values.entrySet().stream()
                .map(entry -> Map.entry(
                        entry.getKey(),
                        entry.getValue().stream()
                                .mapToDouble(Double::doubleValue)
                                .average()
                                .orElse(0)
                ))
                .toList();
        AggregatedSeries series = aggregateTimeSeries(points);
        return new TabularAnalysisDataset.ChartCandidate(
                chartId(sequence),
                analysis.sheet.name + "：" + numericColumn.name + "趋势",
                "LINE",
                analysis.sheet.name,
                dateColumn.name,
                numericColumn.name,
                series.categories,
                series.values,
                series.aggregated ? "按连续时间区间平均" : "同日期平均"
        );
    }

    private TabularAnalysisDataset.ChartCandidate categoryAverageChart(
            SheetAnalysis analysis,
            ColumnAnalysis categoryColumn,
            ColumnAnalysis numericColumn,
            int sequence
    ) {
        Map<Integer, String> categoriesByRow = categoryColumn.observed.stream()
                .collect(Collectors.toMap(
                        ObservedValue::rowNumber,
                        value -> value.value.display,
                        (left, right) -> left
                ));
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        numericColumn.observed.stream()
                .filter(value -> value.value.number != null)
                .filter(value -> categoriesByRow.containsKey(value.rowNumber))
                .forEach(value -> grouped
                        .computeIfAbsent(
                                categoriesByRow.get(value.rowNumber),
                                ignored -> new ArrayList<>()
                        )
                        .add(value.value.number));
        if (grouped.size() < 2) return null;
        List<Map.Entry<String, Double>> values = grouped.entrySet().stream()
                .map(entry -> Map.entry(
                        entry.getKey(),
                        entry.getValue().stream()
                                .mapToDouble(Double::doubleValue)
                                .average()
                                .orElse(0)
                ))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(12)
                .toList();
        return new TabularAnalysisDataset.ChartCandidate(
                chartId(sequence),
                analysis.sheet.name + "：" + numericColumn.name + "分类均值",
                "BAR",
                analysis.sheet.name,
                categoryColumn.name,
                numericColumn.name,
                values.stream().map(entry -> abbreviate(entry.getKey(), 30)).toList(),
                values.stream().map(Map.Entry::getValue).toList(),
                "按分类平均"
        );
    }

    private TabularAnalysisDataset.ChartCandidate categoryCountChart(
            SheetAnalysis analysis,
            ColumnAnalysis categoryColumn,
            int sequence
    ) {
        List<Map.Entry<String, Integer>> values = categoryColumn.frequencies.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12)
                .toList();
        return new TabularAnalysisDataset.ChartCandidate(
                chartId(sequence),
                analysis.sheet.name + "：" + categoryColumn.name + "分布",
                "BAR",
                analysis.sheet.name,
                categoryColumn.name,
                "记录数",
                values.stream().map(entry -> abbreviate(entry.getKey(), 30)).toList(),
                values.stream().map(entry -> entry.getValue().doubleValue()).toList(),
                "按分类计数"
        );
    }

    private TabularAnalysisDataset.ChartCandidate completenessChart(
            SheetAnalysis analysis,
            int sequence
    ) {
        List<ColumnAnalysis> columns = analysis.columns.stream().limit(20).toList();
        int rowCount = Math.max(1, analysis.sheet.rows.size());
        return new TabularAnalysisDataset.ChartCandidate(
                chartId(sequence),
                analysis.sheet.name + "：字段完整率",
                "BAR",
                analysis.sheet.name,
                "字段",
                "完整率（%）",
                columns.stream().map(column -> abbreviate(column.name, 30)).toList(),
                columns.stream()
                        .map(column -> column.profile.nonEmptyCount() * 100.0 / rowCount)
                        .toList(),
                "非空记录占比"
        );
    }

    private TabularAnalysisDataset.ChartCandidate fallbackDatasetChart(
            ParsedDataset parsed,
            int sequence
    ) {
        return new TabularAnalysisDataset.ChartCandidate(
                chartId(sequence),
                "各工作表数据行数",
                "BAR",
                "",
                "工作表",
                "数据行数",
                parsed.sheets.stream().map(sheet -> abbreviate(sheet.name, 30)).toList(),
                parsed.sheets.stream().map(sheet -> (double) sheet.rows.size()).toList(),
                "非空数据行计数"
        );
    }

    private static AggregatedSeries aggregateTimeSeries(
            List<Map.Entry<LocalDate, Double>> points
    ) {
        if (points.size() <= MAX_CHART_POINTS) {
            return new AggregatedSeries(
                    points.stream().map(entry -> entry.getKey().toString()).toList(),
                    points.stream().map(Map.Entry::getValue).toList(),
                    false
            );
        }
        int bucketSize = (int) Math.ceil((double) points.size() / MAX_CHART_POINTS);
        List<String> categories = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (int start = 0; start < points.size(); start += bucketSize) {
            int end = Math.min(points.size(), start + bucketSize);
            List<Map.Entry<LocalDate, Double>> bucket = points.subList(start, end);
            categories.add(bucket.get(0).getKey() + "～" + bucket.get(bucket.size() - 1).getKey());
            values.add(bucket.stream()
                    .mapToDouble(Map.Entry::getValue)
                    .average()
                    .orElse(0));
        }
        return new AggregatedSeries(categories, values, true);
    }

    private static void addAnomaly(
            List<TabularAnalysisDataset.Anomaly> target,
            AtomicInteger sequence,
            AnomalyDraft draft
    ) {
        if (target.size() >= MAX_ANOMALIES) return;
        target.add(new TabularAnalysisDataset.Anomaly(
                "A" + sequence.incrementAndGet(),
                draft.type,
                draft.severity,
                draft.sheetName,
                draft.columnName,
                draft.rowNumber,
                draft.description,
                draft.evidence
        ));
    }

    private static Row firstNonEmptyRow(Sheet sheet, DataFormatter formatter) {
        for (Row row : sheet) {
            if (effectiveWidth(row, formatter) > 0) return row;
        }
        return null;
    }

    private static int effectiveWidth(Row row, DataFormatter formatter) {
        int last = row.getLastCellNum();
        if (last <= 0) return 0;
        for (int index = last - 1; index >= 0; index--) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && !formatter.formatCellValue(cell).trim().isEmpty()) {
                return index + 1;
            }
        }
        return 0;
    }

    private static List<CellValue> values(
            Row row,
            int width,
            DataFormatter formatter
    ) {
        List<CellValue> values = new ArrayList<>(width);
        for (int index = 0; index < width; index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            values.add(cellValue(cell, formatter));
        }
        return List.copyOf(values);
    }

    private static List<CellValue> recordValues(CSVRecord record, int width) {
        List<CellValue> values = new ArrayList<>(width);
        for (int index = 0; index < width; index++) {
            values.add(parseTextValue(index < record.size() ? record.get(index) : ""));
        }
        return List.copyOf(values);
    }

    private static CellValue cellValue(Cell cell, DataFormatter formatter) {
        if (cell == null) return CellValue.empty();
        String display = normalize(formatter.formatCellValue(cell));
        if (display.isEmpty()) return CellValue.empty();
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();
        try {
            if (type == CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime value = cell.getLocalDateTimeCellValue();
                    return new CellValue(display, ValueType.DATE, null, value.toLocalDate());
                }
                return new CellValue(display, ValueType.NUMBER, cell.getNumericCellValue(), null);
            }
            if (type == CellType.BOOLEAN) {
                return new CellValue(display, ValueType.BOOLEAN, null, null);
            }
        } catch (RuntimeException ignored) {
            return parseTextValue(display);
        }
        return parseTextValue(display);
    }

    private static CellValue parseTextValue(String raw) {
        String value = normalize(raw);
        if (value.isEmpty()) return CellValue.empty();
        String lower = value.toLowerCase(Locale.ROOT);
        if (Set.of("true", "false", "是", "否").contains(lower)) {
            return new CellValue(value, ValueType.BOOLEAN, null, null);
        }
        try {
            String numeric = value.replace(",", "");
            return new CellValue(
                    value,
                    ValueType.NUMBER,
                    new BigDecimal(numeric).doubleValue(),
                    null
            );
        } catch (NumberFormatException ignored) {
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return new CellValue(
                        value,
                        ValueType.DATE,
                        null,
                        LocalDate.parse(value, formatter)
                );
            } catch (DateTimeParseException ignored) {
            }
        }
        return new CellValue(value, ValueType.TEXT, null, null);
    }

    private static List<String> normalizeHeaders(List<String> raw) {
        List<String> headers = new ArrayList<>(raw.size());
        Map<String, Integer> counts = new HashMap<>();
        for (int index = 0; index < raw.size(); index++) {
            String base = raw.get(index).isBlank() ? "列" + (index + 1) : raw.get(index).trim();
            int count = counts.merge(base, 1, Integer::sum);
            headers.add(count == 1 ? base : base + "-" + count);
        }
        return List.copyOf(headers);
    }

    private static double quantile(List<Double> sorted, double position) {
        if (sorted.isEmpty()) return 0;
        double index = position * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return sorted.get(lower);
        double weight = index - lower;
        return sorted.get(lower) * (1 - weight) + sorted.get(upper) * weight;
    }

    private static PushbackReader withoutUtf8Bom(Reader source) throws IOException {
        PushbackReader reader = new PushbackReader(source, 1);
        int first = reader.read();
        if (first >= 0 && first != '\uFEFF') reader.unread(first);
        return reader;
    }

    private static boolean hasCharacterCodingCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof CharacterCodingException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.replace("\u0000", "")
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .trim();
    }

    private static String extension(String fileName) {
        if (fileName == null) return "";
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index).toLowerCase(Locale.ROOT);
    }

    private static String chartId(int sequence) {
        return "C" + sequence;
    }

    private static String abbreviate(String value, int maximum) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.length() <= maximum
                ? normalized
                : normalized.substring(0, maximum);
    }

    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.4f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private static FeatureValidationException invalidFile(String message) {
        return new FeatureValidationException("dataFile", message);
    }

    private enum ValueType {
        EMPTY,
        NUMBER,
        DATE,
        BOOLEAN,
        TEXT
    }

    private record CellValue(
            String display,
            ValueType type,
            Double number,
            LocalDate date
    ) {
        private static CellValue empty() {
            return new CellValue("", ValueType.EMPTY, null, null);
        }

        private boolean nonEmpty() {
            return type != ValueType.EMPTY;
        }
    }

    private record DataRow(int sourceRowNumber, List<CellValue> cells) {
    }

    private record ParsedSheet(String name, List<String> headers, List<DataRow> rows) {
    }

    private record ParsedDataset(
            String format,
            List<ParsedSheet> sheets,
            int totalRows,
            int totalNonEmptyCells
    ) {
    }

    private record ObservedValue(int rowNumber, CellValue value) {
    }

    private record ColumnAnalysis(
            int index,
            String name,
            List<ObservedValue> observed,
            Set<ValueType> types,
            Map<String, Integer> frequencies,
            List<Double> numeric,
            TabularAnalysisDataset.ColumnProfile profile
    ) {
    }

    private record SheetAnalysis(
            ParsedSheet sheet,
            List<ColumnAnalysis> columns,
            int duplicateRows,
            TabularAnalysisDataset.SheetProfile profile
    ) {
    }

    private record AnomalyDraft(
            String type,
            String severity,
            String sheetName,
            String columnName,
            Integer rowNumber,
            String description,
            String evidence
    ) {
    }

    private record TimePoint(int rowNumber, LocalDate date, double value) {
    }

    private record AggregatedSeries(
            List<String> categories,
            List<Double> values,
            boolean aggregated
    ) {
    }

    private static final class Counters {
        private final TabularAnalysisLimits limits;
        private int rows;
        private int nonEmptyCells;

        private Counters(TabularAnalysisLimits limits) {
            this.limits = limits;
        }

        private void addRow() {
            rows++;
            if (rows > limits.maxRows()) {
                throw invalidFile("数据行总数不能超过 " + limits.maxRows());
            }
        }

        private void addCells(long count) {
            nonEmptyCells = Math.addExact(nonEmptyCells, Math.toIntExact(count));
            if (nonEmptyCells > limits.maxNonEmptyCells()) {
                throw invalidFile(
                        "非空单元格总数不能超过 " + limits.maxNonEmptyCells()
                );
            }
        }
    }
}
