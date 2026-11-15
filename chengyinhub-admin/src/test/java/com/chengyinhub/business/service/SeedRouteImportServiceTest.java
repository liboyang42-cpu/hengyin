package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.seed.SeedImportReport;
import com.chengyinhub.business.domain.dto.seed.SeedImportRow;
import com.chengyinhub.business.domain.dto.seed.SeedPlace;
import com.chengyinhub.business.domain.dto.seed.SeedRoute;
import com.chengyinhub.business.mapper.SeedContentMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeedRouteImportServiceTest {
    private SeedContentMapper mapper;
    private SeedRouteImportService service;
    private final List<SeedPlace> places = new ArrayList<>();

    @BeforeEach void setUp() {
        mapper = mock(SeedContentMapper.class);
        IOfficialEventV2Service operators = mock(IOfficialEventV2Service.class);
        when(operators.resolveAuthorizedOfficialMember(1L)).thenReturn(7L);
        when(mapper.selectPlacesByIds(any())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            return places.stream().filter(p -> ids.contains(p.getId())).toList();
        });
        when(mapper.selectExistingRouteNames(any())).thenReturn(List.of("已有路线"));
        when(mapper.insertRouteDraft(any())).thenAnswer(call -> { ((SeedRoute) call.getArgument(0)).setId(900L); return 1; });
        when(mapper.insertRouteNode(any(), anyInt(), any(), any(), any())).thenReturn(1);
        service = new SeedRouteImportService(mapper, operators);
        places.add(official(101, "30.2500", "120.1500", 11L, 1));
        places.add(official(102, "30.2590", "120.1500", null, 1));
        places.add(official(103, "30.2590", "120.1600", 13L, 0));
        SeedPlace shop = official(104, "30.2600", "120.1600", 14L, 1); shop.setType(2); shop.setMerchantId(5L);
        places.add(shop);
    }

    private static SeedPlace official(long id, String lat, String lng, Long template, int status) {
        SeedPlace p = new SeedPlace();
        p.setId(id); p.setName("地点" + id); p.setAddress("地址" + id); p.setType(1); p.setStatus(status);
        p.setLat(new BigDecimal(lat)); p.setLng(new BigDecimal(lng)); p.setTemplateId(template);
        return p;
    }

    private static List<SeedTableReader.Line> csv(String body) {
        return SeedTableReader.read("r.csv", ("title,difficulty,place_ids,description\n" + body).getBytes(StandardCharsets.UTF_8));
    }

    @Test void previewChecksEveryRouteAndWritesNothing() {
        SeedImportReport report = service.importRoutes(1L, "ops", csv(
                "湖滨线,轻松,\"101;102;103\",三站\n"
                + "坏难度,地狱,\"101;102\",\n"
                + "单站,easy,101,\n"
                + "重复站,easy,\"101,101\",\n"
                + "不存在,hard,\"101;999\",\n"
                + "商家站,hard,\"101;104\",\n"
                + "已有路线,standard,\"101;102\",\n"
                + "湖滨线,standard,\"102;103\",\n"
                + "坏id,easy,\"101;abc\",\n"), true);
        List<SeedImportRow> rows = report.getRows();
        assertEquals(SeedImportRow.NEW, rows.get(0).getOutcome());
        assertEquals(2, rows.get(0).getWarnings().size(), "one stop without game, one stop not live");
        assertTrue(rows.get(1).getErrors().get(0).startsWith("难度只能是"));
        assertTrue(rows.get(2).getErrors().get(0).contains("2 到 30"));
        assertTrue(rows.get(3).getErrors().get(0).contains("重复"));
        assertTrue(rows.get(4).getErrors().contains("地点 #999 不存在"));
        assertTrue(rows.get(5).getErrors().contains("地点 #104 不是官方地点"));
        assertEquals(SeedImportRow.DUPLICATE, rows.get(6).getOutcome());
        assertEquals(SeedImportRow.DUPLICATE, rows.get(7).getOutcome());
        assertEquals("本文件第 2 行", rows.get(7).getDuplicateOfName());
        assertTrue(rows.get(8).getErrors().get(0).contains("abc"));
        assertEquals(1, report.getNewCount());
        verify(mapper, never()).insertRouteDraft(any());
    }

    @Test void commitWritesDraftTopicWithOrderedNodesAndDifficulty() {
        SeedImportReport report = service.importRoutes(1L, "ops", csv("湖滨线,硬核,\"103 → 101 → 102\",三站\n已有路线,easy,\"101;102\",\n"), false);
        assertTrue(report.isCommitted());
        ArgumentCaptor<SeedRoute> topic = ArgumentCaptor.forClass(SeedRoute.class);
        verify(mapper, times(1)).insertRouteDraft(topic.capture());
        SeedRoute t = topic.getValue();
        assertEquals(7L, t.getMemberId());
        assertEquals("湖滨线", t.getName());
        assertEquals(3, t.getLocationCount());
        assertEquals(2, t.getTemplateCount());
        assertEquals("地址103", t.getAddressName());
        assertTrue(t.getTotalMileage().compareTo(new BigDecimal("1.5")) > 0, "~0.96 km + 1.0 km");
        assertTrue(t.getJourneyRulesJson().contains("\"difficulty\":\"hard\""), t.getJourneyRulesJson());
        InOrder order = inOrder(mapper);
        order.verify(mapper).insertRouteNode(eq(900L), eq(1), argThat(p -> p.getId() == 103L), eq(13L), eq("ops"));
        order.verify(mapper).insertRouteNode(eq(900L), eq(2), argThat(p -> p.getId() == 101L), eq(11L), eq("ops"));
        order.verify(mapper).insertRouteNode(eq(900L), eq(3), argThat(p -> p.getId() == 102L), eq(0L), eq("ops"));
        assertEquals(900L, report.getRows().get(0).getCreatedId());
    }

    @Test void commitWithErrorsOrWithoutOperatorWritesNothing() {
        assertThrows(ServiceException.class, () -> service.importRoutes(1L, "ops", csv("a,easy,\"101;999\",\n"), false));
        assertThrows(ServiceException.class, () -> service.importRoutes(2L, "ops", csv("a,easy,\"101;102\",\n"), false));
        assertThrows(ServiceException.class, () -> service.importRoutes(1L, "ops",
                SeedTableReader.read("r.csv", "title,place_ids\na,\"1;2\"\n".getBytes(StandardCharsets.UTF_8)), true));
        verify(mapper, never()).insertRouteDraft(any());
    }

    @Test void templateRowsParse() {
        places.clear();
        SeedImportReport report = service.importRoutes(1L, "ops",
                SeedTableReader.read("t.csv", service.template().getBytes(StandardCharsets.UTF_8)), true);
        assertEquals(2, report.getTotal());
        assertTrue(report.getRows().stream().allMatch(r -> r.getErrors().stream().allMatch(e -> e.contains("不存在"))),
                "example ids are placeholders; only existence fails");
    }
}
