package com.chengyinhub.business.service;

import com.chengyinhub.common.exception.ServiceException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedTableReaderTest {

    @Test void readsUtf8CsvWithBomQuotesAndBlankRows() {
        String csv = "﻿Name, LAT ,lng,tags\r\n"
                + "\"西湖, 断桥\",30.259,120.151,\"桥,\"\"湖\"\"\"\r\n"
                + ",,,\r\n"
                + "\"多行\n名称\",30.1,120.1,\r\n";
        List<SeedTableReader.Line> lines = SeedTableReader.read("places.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, lines.size());
        assertEquals(2, lines.get(0).number);
        assertEquals("西湖, 断桥", lines.get(0).values.get("name"));
        assertEquals("30.259", lines.get(0).values.get("lat"));
        assertEquals("桥,\"湖\"", lines.get(0).values.get("tags"));
        assertEquals("多行\n名称", lines.get(1).values.get("name"));
        assertEquals("", lines.get(1).values.get("tags"));
    }

    @Test void readsFirstSheetOfXlsx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("名称"); header.createCell(1).setCellValue("lat");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("老街书店"); row.createCell(1).setCellValue(30.2612);
            book.write(out);
        }
        List<SeedTableReader.Line> lines = SeedTableReader.read("places.xlsx", out.toByteArray());
        assertEquals(1, lines.size());
        assertEquals("老街书店", lines.get(0).values.get("名称"));
        assertEquals("30.2612", lines.get(0).values.get("lat"));
    }

    @Test void rejectsEmptyOversizedAndBrokenFiles() {
        assertThrows(ServiceException.class, () -> SeedTableReader.read("a.csv", new byte[0]));
        assertThrows(ServiceException.class, () -> SeedTableReader.read("a.csv", new byte[SeedTableReader.MAX_BYTES + 1]));
        assertThrows(ServiceException.class, () -> SeedTableReader.read("a.csv", "name\n\"open".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ServiceException.class, () -> SeedTableReader.read("a.xlsx", "not a workbook".getBytes(StandardCharsets.UTF_8)));
        byte[] gbk = {(byte) 0xC3, (byte) 0xFB, ',', 'x', '\n'};
        assertThrows(ServiceException.class, () -> SeedTableReader.read("a.csv", gbk));
    }

    @Test void capsRowCount() {
        StringBuilder csv = new StringBuilder("name\n");
        for (int i = 0; i <= SeedTableReader.MAX_ROWS; i++) csv.append("p").append(i).append('\n');
        assertThrows(ServiceException.class, () -> SeedTableReader.read("a.csv", csv.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test void exportCellsQuoteAndNeutraliseFormulasAndReimportUnchanged() {
        assertEquals("\"a,b\"", SeedTableReader.csvCell("a,b"));
        assertEquals("'=HYPERLINK(1)", SeedTableReader.csvCell("=HYPERLINK(1)"));
        assertEquals("-30.5", SeedTableReader.csvCell("-30.5"));
        String csv = "name,description\nx," + SeedTableReader.csvCell("=1+1") + "\n";
        List<SeedTableReader.Line> lines = SeedTableReader.read("a.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertEquals("=1+1", lines.get(0).values.get("description"));
    }
}
