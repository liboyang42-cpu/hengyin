package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.seed.SeedImportReport;
import com.chengyinhub.business.domain.dto.seed.SeedImportRow;
import com.chengyinhub.business.domain.dto.seed.SeedPlace;
import com.chengyinhub.business.mapper.SeedContentMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeedPlaceImportServiceTest {
    private static final String COVER = "/profile/upload/2026/11/15/0123456789abcdef0123456789abcdef.jpg";
    private SeedContentMapper mapper;
    private IOfficialEventV2Service operators;
    private SeedPlaceImportService service;
    private final List<SeedPlace> existing = new ArrayList<>();

    @BeforeEach void setUp() {
        mapper = mock(SeedContentMapper.class);
        operators = mock(IOfficialEventV2Service.class);
        when(operators.resolveAuthorizedOfficialMember(1L)).thenReturn(7L);
        when(mapper.selectPlacesInBox(any(), any(), any(), any())).thenAnswer(call -> existing);
        long[] ids = {1000};
        when(mapper.insertImportedPlace(any())).thenAnswer(call -> { ((SeedPlace) call.getArgument(0)).setId(ids[0]++); return 1; });
        service = new SeedPlaceImportService(mapper, operators);
    }

    private static List<SeedTableReader.Line> csv(String body) {
        return SeedTableReader.read("p.csv", ("name,category,lat,lng,address,cover_image_url,tags,opening_hours,description,city_code\n" + body)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static SeedPlace place(long id, String name, String lat, String lng) {
        SeedPlace p = new SeedPlace();
        p.setId(id); p.setName(name); p.setLat(new BigDecimal(lat)); p.setLng(new BigDecimal(lng)); p.setType(1);
        return p;
    }

    @Test void dryRunValidatesEveryRowAndWritesNothing() {
        SeedImportReport report = service.importPlaces(1L, csv(
                "断桥,景点,30.259300,120.151000,北山街,"+ COVER + ",\"桥，湖\",全天,湖上的桥,\n"
                + ",景点,30.2,120.1,某路,,,,,\n"
                + "反了,景点,120.15,30.25,某路,,,,,\n"
                + "国外,景点,48.85,2.35,巴黎,,,,,\n"
                + "坏数,景点,abc,120.1,某路,,,,,\n"
                + "外链封面,景点,30.27,120.16,某路,https://example.com/a.jpg,,,短介绍,\n"), "330100", null, true);
        assertTrue(report.isDryRun());
        assertFalse(report.isCommitted());
        assertEquals(6, report.getTotal());
        assertEquals(2, report.getNewCount());
        assertEquals(4, report.getErrorCount());
        List<SeedImportRow> rows = report.getRows();
        assertEquals(2, rows.get(0).getRowNumber());
        assertTrue(rows.get(0).getErrors().isEmpty());
        assertTrue(rows.get(1).getErrors().contains("缺少名称"));
        assertTrue(rows.get(2).getErrors().contains("纬度和经度像是填反了"));
        assertTrue(rows.get(3).getErrors().get(0).startsWith("坐标不在中国境内"));
        assertTrue(rows.get(4).getErrors().contains("纬度不是数字"));
        assertTrue(rows.get(5).getWarnings().get(0).startsWith("封面不是平台上传的图片"));
        verify(mapper, never()).insertImportedPlace(any());
        verify(operators, never()).resolveAuthorizedOfficialMember(any());
    }

    @Test void duplicatesByNameAndDistanceAgainstExistingAndWithinFile() {
        existing.add(place(55, "断 桥", "30.259400", "120.151100")); // ~14 m away, same name once normalised
        existing.add(place(56, "白堤", "30.300000", "120.200000"));
        SeedImportReport report = service.importPlaces(1L, csv(
                "断桥,景点,30.259300,120.151000,北山街,,,,,\n"
                + "白堤,景点,30.259300,120.151000,北山街,,,,,\n"     // same name as #56 but ~6 km away: new
                + "白堤,景点,30.259350,120.151050,北山街,,,,,\n"     // same as the row above: duplicate in file
                + "孤山,景点,30.259310,120.151010,北山街,,,,,\n"), "330100", 100, true);
        List<SeedImportRow> rows = report.getRows();
        assertEquals(SeedImportRow.DUPLICATE, rows.get(0).getOutcome());
        assertEquals(55L, rows.get(0).getDuplicateOfId());
        assertEquals(SeedImportRow.NEW, rows.get(1).getOutcome());
        assertEquals(SeedImportRow.DUPLICATE, rows.get(2).getOutcome());
        assertEquals("本文件第 3 行", rows.get(2).getDuplicateOfName());
        assertEquals(SeedImportRow.NEW, rows.get(3).getOutcome());
        assertTrue(rows.get(3).getWarnings().stream().anyMatch(w -> w.contains("断 桥")), "nearby different name is flagged");
        assertEquals(2, report.getNewCount());
        assertEquals(2, report.getDuplicateCount());
    }

    @Test void commitWritesOnlyNewRowsAsHiddenDrafts() {
        existing.add(place(55, "断桥", "30.259300", "120.151000"));
        SeedImportReport report = service.importPlaces(1L, csv(
                "断桥,景点,30.259300,120.151000,北山街,,,,,\n"
                + "孤山,景点,30.2560001,120.1450004,孤山路," + COVER + ",\"山，湖、园\",08:00-17:00,湖中小山,310100\n"), "330100", null, false);
        assertTrue(report.isCommitted());
        ArgumentCaptor<SeedPlace> saved = ArgumentCaptor.forClass(SeedPlace.class);
        verify(mapper, times(1)).insertImportedPlace(saved.capture());
        SeedPlace p = saved.getValue();
        assertEquals("孤山", p.getName());
        assertEquals("310100", p.getCityCode(), "row city code wins over the form default");
        assertEquals(new BigDecimal("30.256000"), p.getLat());
        assertEquals(new BigDecimal("120.145000"), p.getLng());
        assertEquals("山,湖,园", p.getTags());
        assertEquals("08:00-17:00", p.getOpeningHours());
        assertEquals(COVER, p.getCoverImg());
        assertEquals("[\"" + COVER + "\"]", p.getGalleryJson());
        assertEquals(1000L, report.getRows().get(1).getCreatedId());
        assertNull(report.getRows().get(0).getCreatedId());
    }

    @Test void commitWithAnyErrorWritesNothing() {
        ServiceException e = assertThrows(ServiceException.class, () -> service.importPlaces(1L, csv(
                "孤山,景点,30.256,120.145,孤山路,,,,,\n"
                + ",景点,30.256,120.145,孤山路,,,,,\n"), "330100", null, false));
        assertTrue(e.getMessage().contains("1 行有错误"));
        verify(mapper, never()).insertImportedPlace(any());
    }

    @Test void commitNeedsAnAuthorisedOfficialOperator() {
        assertThrows(ServiceException.class, () -> service.importPlaces(2L, csv("孤山,景点,30.256,120.145,孤山路,,,,,\n"), "330100", null, false));
        verify(mapper, never()).insertImportedPlace(any());
    }

    @Test void rejectsMissingColumnsCityCodeAndBadDedupeDistance() {
        List<SeedTableReader.Line> noAddress = SeedTableReader.read("p.csv", "name,lat,lng\n孤山,30.2,120.1\n".getBytes(StandardCharsets.UTF_8));
        ServiceException missing = assertThrows(ServiceException.class, () -> service.importPlaces(1L, noAddress, "330100", null, true));
        assertTrue(missing.getMessage().contains("address"));
        SeedImportReport noCity = service.importPlaces(1L, csv("孤山,景点,30.256,120.145,孤山路,,,,,\n"), "", null, true);
        assertEquals(1, noCity.getErrorCount());
        assertThrows(ServiceException.class, () -> service.importPlaces(1L, csv("孤山,景点,30.256,120.145,孤山路,,,,,\n"), "330100", 5, true));
    }

    @Test void acceptsChineseHeaders() {
        List<SeedTableReader.Line> lines = SeedTableReader.read("p.csv",
                "名称,纬度,经度,地址,城市码\n孤山,30.256,120.145,孤山路,330100\n".getBytes(StandardCharsets.UTF_8));
        SeedImportReport report = service.importPlaces(1L, lines, null, null, true);
        assertEquals(1, report.getNewCount());
        assertEquals("孤山", report.getRows().get(0).getValues().get("name"));
    }

    @Test void templateAndExportShareTheImportFormat() throws Exception {
        ByteArrayOutputStream template = new ByteArrayOutputStream();
        service.writeTemplate(template);
        SeedImportReport fromTemplate = service.importPlaces(1L,
                SeedTableReader.read("t.csv", template.toByteArray()), null, null, true);
        assertEquals(2, fromTemplate.getNewCount(), "both example rows are valid");

        SeedPlace stored = place(9, "=孤山", "30.256000", "120.145000");
        stored.setAddress("孤山路, 1号"); stored.setCityCode("330100"); stored.setTags("山,湖"); stored.setCategory("景点");
        when(mapper.selectOfficialPlacesForExport("330100")).thenReturn(List.of(stored));
        ByteArrayOutputStream export = new ByteArrayOutputStream();
        service.writeExport("330100", export);
        String text = export.toString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿" + String.join(",", SeedPlaceImportService.HEADERS)));
        List<SeedTableReader.Line> back = SeedTableReader.read("e.csv", export.toByteArray());
        assertEquals("=孤山", back.get(0).values.get("name"));
        assertEquals("孤山路, 1号", back.get(0).values.get("address"));
        assertEquals("30.256000", back.get(0).values.get("lat"));
    }
}
