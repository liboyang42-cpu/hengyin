package com.chengyinhub.business.service;

import com.chengyinhub.common.exception.ServiceException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads a bulk-import sheet (UTF-8 CSV, with or without BOM, or the first sheet of an XLSX) into rows keyed by
 * lower-case header. Blank rows are dropped; the row number kept is the spreadsheet line, so operators can find it.
 */
public final class SeedTableReader {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ROWS = 2000;

    /** A parsed data row: its spreadsheet line number and header → trimmed value. */
    public static final class Line {
        public final int number;
        public final Map<String, String> values;
        Line(int number, Map<String, String> values) { this.number = number; this.values = values; }
    }

    private SeedTableReader() {}

    public static List<Line> read(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new ServiceException("文件是空的");
        if (bytes.length > MAX_BYTES) throw new ServiceException("文件不能超过 5MB");
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        List<List<String>> grid = name.endsWith(".xlsx") || name.endsWith(".xls") ? readWorkbook(bytes) : readCsv(bytes);
        return toLines(grid);
    }

    static List<Line> toLines(List<List<String>> grid) {
        int headerIndex = -1;
        for (int i = 0; i < grid.size(); i++) if (!blank(grid.get(i))) { headerIndex = i; break; }
        if (headerIndex < 0) throw new ServiceException("文件里没有表头");
        List<String> header = new ArrayList<>();
        for (String cell : grid.get(headerIndex)) header.add(normalizeHeader(cell));
        List<Line> lines = new ArrayList<>();
        for (int i = headerIndex + 1; i < grid.size(); i++) {
            List<String> cells = grid.get(i);
            if (blank(cells)) continue;
            if (lines.size() >= MAX_ROWS) throw new ServiceException("单次最多导入 " + MAX_ROWS + " 行，请分批");
            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                if (header.get(c).isEmpty()) continue;
                String value = c < cells.size() && cells.get(c) != null ? cells.get(c).trim() : "";
                // Undo the export's formula guard ('=..., '+..., '-..., '@...) so an export re-imports unchanged.
                if (value.length() > 1 && value.charAt(0) == '\'' && "=+-@".indexOf(value.charAt(1)) >= 0) value = value.substring(1);
                values.put(header.get(c), value);
            }
            lines.add(new Line(i + 1, values));
        }
        return lines;
    }

    static String normalizeHeader(String raw) {
        if (raw == null) return "";
        return raw.replace("﻿", "").trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static boolean blank(List<String> cells) {
        for (String cell : cells) if (cell != null && !cell.trim().isEmpty()) return false;
        return true;
    }

    /** RFC 4180 CSV: quoted fields may hold commas, doubled quotes and line breaks. */
    static List<List<String>> readCsv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        if (text.indexOf('�') >= 0) throw new ServiceException("请把 CSV 另存为 UTF-8 编码");
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(ch);
            } else if (ch == '"' && cell.length() == 0) {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString()); cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (quoted) throw new ServiceException("CSV 引号没有闭合");
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    private static List<List<String>> readWorkbook(byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                    }
                }
                rows.add(cells);
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ServiceException) throw (ServiceException) e;
            throw new ServiceException("表格读取失败，请确认是 CSV 或 XLSX 文件");
        }
    }

    /** CSV cell for export: quote when needed; neutralise leading formula characters for spreadsheet safety. */
    public static String csvCell(Object value) {
        if (value == null) return "";
        String text = value.toString();
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 && !text.matches("-?[0-9.]+")) text = "'" + text;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r"))
            return "\"" + text.replace("\"", "\"\"") + "\"";
        return text;
    }
}
