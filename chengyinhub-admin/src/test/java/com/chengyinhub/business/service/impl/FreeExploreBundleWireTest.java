package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;

class FreeExploreBundleWireTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ledgerIdsSurviveJsonWithoutChangingJavaTypesOrNumericVersions() throws Exception {
        for (Object bean : List.of(new ExploreBundleOrder(), new ExploreBundleGrant(), new ExploreBundleUnit(),
                new ExploreBundleReservation(), new ExploreBundleExitCase())) {
            for (var method : bean.getClass().getMethods()) {
                if (method.getName().startsWith("set") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == Long.class) method.invoke(bean, Long.MAX_VALUE);
            }
            JsonNode wire = json.readTree(json.writeValueAsString(bean));
            for (var method : bean.getClass().getMethods()) {
                if (method.getName().startsWith("get") && method.getParameterCount() == 0
                        && method.getReturnType() == Long.class) {
                    String name = Character.toLowerCase(method.getName().charAt(3)) + method.getName().substring(4);
                    assertEquals(Long.MAX_VALUE, method.invoke(bean));
                    assertTrue(wire.get(name).isTextual(), bean.getClass().getSimpleName() + "." + name);
                    assertEquals(Long.toString(Long.MAX_VALUE), wire.get(name).textValue());
                }
            }
        }
        ExploreBundleReservation row = new ExploreBundleReservation();
        row.setVersion(3);
        assertTrue(json.readTree(json.writeValueAsString(row)).get("version").isNumber());
    }

    @Test
    void balanceProjectionKeepsExactIdsAndNumericCounts() throws Exception {
        ExploreBundleGrant grant = new ExploreBundleGrant();
        grant.setId(Long.MAX_VALUE); grant.setHolderMemberId(Long.MAX_VALUE); grant.setChapterId(Long.MAX_VALUE);
        grant.setValidFrom(new Date(0)); grant.setValidTo(new Date(2000)); grant.setUnitsTotal(1);
        ExploreBundleUnit unit = new ExploreBundleUnit();
        unit.setGrantId(Long.MAX_VALUE); unit.setStatus("AVAILABLE");
        JsonNode row = json.readTree(json.writeValueAsString(
                FreeExploreBundleServiceImpl.balances(List.of(grant), List.of(unit), new Date(1000)))).get(0);
        for (String name : List.of("grantId", "holderMemberId", "chapterId")) {
            assertEquals(Long.toString(Long.MAX_VALUE), row.get(name).textValue());
        }
        assertEquals(1, row.path("counts").path("AVAILABLE").intValue());
        assertEquals(1, row.path("unitsTotal").intValue());
        assertFalse(row.path("expired").booleanValue());
    }
}
