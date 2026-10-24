package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 故事开局「初始状态」页:只列作者真开了的精力 / 幸运。 */
class ApiPlayVitalsOnTest {

    @Test
    void 初始状态开着_按项给开关() {
        Map<String, Object> on = com.chengyinhub.web.service.play.PlayNodeQueryApplication.vitalsOn(
                "{\"schemaVersion\":1,\"stateEnabled\":true,\"hp\":{\"enabled\":true,\"init\":10,\"max\":10},\"luck\":{\"enabled\":false,\"init\":3,\"max\":3}}");
        assertEquals(Boolean.TRUE, on.get("hp"));
        assertEquals(Boolean.FALSE, on.get("luck"));
    }

    @Test
    void 没开初始状态_或没配_或读不出_一律不给() {
        assertNull(com.chengyinhub.web.service.play.PlayNodeQueryApplication.vitalsOn(
                "{\"schemaVersion\":1,\"stateEnabled\":false,\"hp\":{\"enabled\":true,\"init\":10,\"max\":10}}"));
        assertNull(com.chengyinhub.web.service.play.PlayNodeQueryApplication.vitalsOn(null));
        assertNull(com.chengyinhub.web.service.play.PlayNodeQueryApplication.vitalsOn("{坏的"));
    }
}
