package com.aibox.platform.asset;

import com.aibox.platform.common.PlatformException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SpreadsheetPreviewReader {

    static final int MAX_SHEETS = 5;
    static final int MAX_ROWS_PER_SHEET = 100;
    static final int MAX_COLUMNS = 30;
    static final int MAX_CELL_CHARACTERS = 300;

    public SpreadsheetPreview read(Path path, String extension) {
        String normalized = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case ".xls", ".xlsx" -> readWorkbook(path);
            case ".csv" -> readCsv(path);
            default -> throw new PlatformException(
                    "ASSET_PREVIEW_FAILED",
                    "The spreadsheet format cannot be previewed"
            );
        };
    }

    private SpreadsheetPreview readWorkbook(Path path) {
        try (InputStream input = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(input)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            List<SheetPreview> sheets = new ArrayList<>();
            boolean truncated = false;

            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) continue;
                if (sheets.size() >= MAX_SHEETS) {
                    truncated = true;
                    break;
                }
                SheetPreview sheet = readSheet(workbook.getSheetAt(index), formatter);
                sheets.add(sheet);
                truncated |= sheet.truncated();
            }
            if (sheets.isEmpty()) {
                throw previewFailed("The workbook does not contain visible sheets");
            }
            return new SpreadsheetPreview(List.copyOf(sheets), truncated);
        } catch (PlatformException exception) {
            throw exception;
        } catch (Exception exception) {
            throw previewFailed("The Excel file is damaged, protected, or cannot be decoded");
        }
    }

    private SheetPreview readSheet(Sheet sheet, DataFormatter formatter) {
        List<String> header = null;
        int headerRowNumber = 0;
        List<RawRow> rows = new ArrayList<>();
        int width = 0;
        boolean truncated = false;

        for (Row row : sheet) {
            ExcelRow excelRow = excelRow(row, formatter);
            if (!excelRow.hasContent()) continue;
            truncated |= excelRow.truncated();
            width = Math.max(width, excelRow.cells().size());
            if (header == null) {
                header = excelRow.cells();
                headerRowNumber = row.getRowNum() + 1;
                continue;
            }
            if (rows.size() >= MAX_ROWS_PER_SHEET) {
                truncated = true;
                break;
            }
            rows.add(new RawRow(row.getRowNum() + 1, excelRow.cells()));
        }

        if (header == null) {
            return new SheetPreview(
                    sheet.getSheetName(),
                    0,
                    List.of("列1"),
                    List.of(),
                    false
            );
        }
        int finalWidth = Math.max(1, width);
        List<String> columns = normalizeHeaders(pad(header, finalWidth));
        List<RowPreview> normalizedRows = rows.stream()
                .map(row -> new RowPreview(row.rowNumber(), pad(row.cells(), finalWidth)))
                .toList();
        return new SheetPreview(
                sheet.getSheetName(),
                headerRowNumber,
                columns,
                normalizedRows,
                truncated
        );
    }

    private static ExcelRow excelRow(Row row, DataFormatter formatter) {
        String[] visibleCells = new String[MAX_COLUMNS];
        java.util.Arrays.fill(visibleCells, "");
        int lastVisibleColumn = -1;
        boolean hasContent = false;
        boolean truncated = false;

        for (Cell cell : row) {
            String raw = formatter.formatCellValue(cell);
            if (cellWouldBeTruncated(raw)) truncated = true;
            String value = normalizeCell(raw);
            if (value.isBlank()) continue;
            hasContent = true;
            int column = cell.getColumnIndex();
            if (column >= MAX_COLUMNS) {
                truncated = true;
                continue;
            }
            visibleCells[column] = value;
            lastVisibleColumn = Math.max(lastVisibleColumn, column);
        }
        int width = Math.max(1, lastVisibleColumn + 1);
        return new ExcelRow(
                List.of(java.util.Arrays.copyOf(visibleCells, width)),
                hasContent,
                truncated
        );
    }

    private SpreadsheetPreview readCsv(Path path) {
        for (Charset charset : preferredCsvCharsets(path)) {
            try {
                return parseCsv(path, charset);
            } catch (Exception exception) {
                if (hasCharacterCodingCause(exception)) continue;
                if (exception instanceof PlatformException platformException) {
                    throw platformException;
                }
                throw previewFailed("The CSV file is damaged or cannot be decoded");
            }
        }
        throw previewFailed("The CSV file is damaged or cannot be decoded");
    }

    private SpreadsheetPreview parseCsv(Path path, Charset charset) throws IOException {
        char delimiter = detectDelimiter(path, charset);
        try (InputStream input = Files.newInputStream(path);
             Reader decoded = new InputStreamReader(
                     input,
                     charset.newDecoder()
                             .onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT)
             );
             PushbackReader reader = withoutBom(decoded);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setDelimiter(delimiter)
                     .get()
                     .parse(reader)) {
            List<String> header = null;
            int headerRowNumber = 0;
            List<RawRow> rows = new ArrayList<>();
            int width = 0;
            boolean truncated = false;

            for (CSVRecord record : parser) {
                if (record.stream().allMatch(SpreadsheetPreviewReader::isBlank)) continue;
                int recordWidth = Math.max(1, record.size());
                if (recordWidth > MAX_COLUMNS) truncated = true;
                int previewWidth = Math.min(recordWidth, MAX_COLUMNS);
                if (record.stream()
                        .limit(previewWidth)
                        .anyMatch(SpreadsheetPreviewReader::cellWouldBeTruncated)) {
                    truncated = true;
                }
                width = Math.max(width, previewWidth);
                List<String> cells = csvCells(record, previewWidth);

                if (header == null) {
                    header = cells;
                    headerRowNumber = Math.toIntExact(record.getRecordNumber());
                    continue;
                }
                if (rows.size() >= MAX_ROWS_PER_SHEET) {
                    truncated = true;
                    break;
                }
                rows.add(new RawRow(Math.toIntExact(record.getRecordNumber()), cells));
            }
            if (header == null) {
                throw previewFailed("The CSV file does not contain readable rows");
            }

            int finalWidth = width;
            List<String> columns = normalizeHeaders(pad(header, finalWidth));
            List<RowPreview> normalizedRows = rows.stream()
                    .map(row -> new RowPreview(row.rowNumber(), pad(row.cells(), finalWidth)))
                    .toList();
            SheetPreview sheet = new SheetPreview(
                    "CSV",
                    headerRowNumber,
                    columns,
                    normalizedRows,
                    truncated
            );
            return new SpreadsheetPreview(List.of(sheet), truncated);
        }
    }

    private static List<Charset> preferredCsvCharsets(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] prefix = input.readNBytes(3);
            if (prefix.length >= 3
                    && prefix[0] == (byte) 0xEF
                    && prefix[1] == (byte) 0xBB
                    && prefix[2] == (byte) 0xBF) {
                return List.of(StandardCharsets.UTF_8);
            }
            if (prefix.length >= 2
                    && ((prefix[0] == (byte) 0xFF && prefix[1] == (byte) 0xFE)
                    || (prefix[0] == (byte) 0xFE && prefix[1] == (byte) 0xFF))) {
                return List.of(StandardCharsets.UTF_16);
            }
            return List.of(StandardCharsets.UTF_8, Charset.forName("GB18030"));
        } catch (IOException exception) {
            throw previewFailed("The CSV file cannot be read");
        }
    }

    private static char detectDelimiter(Path path, Charset charset) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     input,
                     charset.newDecoder()
                             .onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT)
             ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = line.startsWith("\uFEFF") ? line.substring(1) : line;
                if (normalized.isBlank()) continue;
                int commas = countDelimiter(normalized, ',');
                int semicolons = countDelimiter(normalized, ';');
                int tabs = countDelimiter(normalized, '\t');
                if (tabs > commas && tabs > semicolons) return '\t';
                if (semicolons > commas) return ';';
                return ',';
            }
            return ',';
        }
    }

    private static int countDelimiter(String line, char delimiter) {
        int count = 0;
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && current == delimiter) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasCharacterCodingCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof CharacterCodingException) return true;
            if (current instanceof UncheckedIOException unchecked
                    && unchecked.getCause() instanceof CharacterCodingException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static List<String> csvCells(CSVRecord record, int width) {
        List<String> cells = new ArrayList<>(width);
        for (int column = 0; column < width; column++) {
            cells.add(normalizeCell(record.get(column)));
        }
        return List.copyOf(cells);
    }

    private static List<String> pad(List<String> values, int width) {
        if (values.size() == width) return List.copyOf(values);
        List<String> padded = new ArrayList<>(width);
        padded.addAll(values.subList(0, Math.min(values.size(), width)));
        while (padded.size() < width) padded.add("");
        return List.copyOf(padded);
    }

    private static List<String> normalizeHeaders(List<String> values) {
        Map<String, Integer> occurrences = new HashMap<>();
        List<String> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String base = values.get(index).isBlank() ? "列" + (index + 1) : values.get(index);
            int occurrence = occurrences.merge(base, 1, Integer::sum);
            result.add(occurrence == 1 ? base : base + " (" + occurrence + ")");
        }
        return List.copyOf(result);
    }

    private static String normalizeCell(String value) {
        String normalized = cleanedCell(value);
        if (normalized.length() <= MAX_CELL_CHARACTERS) return normalized;
        return normalized.substring(0, MAX_CELL_CHARACTERS) + "...";
    }

    private static boolean cellWouldBeTruncated(String value) {
        return cleanedCell(value).length() > MAX_CELL_CHARACTERS;
    }

    private static String cleanedCell(String value) {
        return value == null
                ? ""
                : value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static PushbackReader withoutBom(Reader source) throws IOException {
        PushbackReader reader = new PushbackReader(source, 1);
        int first = reader.read();
        if (first >= 0 && first != '\uFEFF') reader.unread(first);
        return reader;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static PlatformException previewFailed(String message) {
        return new PlatformException("ASSET_PREVIEW_FAILED", message);
    }

    public record SpreadsheetPreview(List<SheetPreview> sheets, boolean truncated) {
    }

    public record SheetPreview(
            String name,
            int headerRowNumber,
            List<String> columns,
            List<RowPreview> rows,
            boolean truncated
    ) {
    }

    public record RowPreview(int rowNumber, List<String> cells) {
    }

    private record RawRow(int rowNumber, List<String> cells) {
    }

    private record ExcelRow(List<String> cells, boolean hasContent, boolean truncated) {
    }
}
