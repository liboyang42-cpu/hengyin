package com.chengyinhub.business.service.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-10-31 P-7:漫游简化轨迹只收合法坐标、抽稀到 ≤300 点、5 位小数;不足两点不存。 */
class RoamTrackSimplifierTest {

    @Test
    void keepsValidPointsRoundedToFiveDecimals() {
        String out = RoamTrackSimplifier.simplify("[[31.2304123,121.4737456],[31.2310,121.4741],[\"x\",1],[95,10]]");
        assertEquals("[[31.23041,121.47375],[31.231,121.4741]]", out);
    }

    @Test
    void downsamplesToMaxPointsKeepingEnds() {
        StringBuilder raw = new StringBuilder("[");
        for (int i = 0; i < 1000; i++) raw.append(i == 0 ? "" : ",").append("[31.2,").append(121 + i * 0.0001).append(']');
        List<List<Double>> parsed = RoamTrackSimplifier.parse(RoamTrackSimplifier.simplify(raw.append(']').toString()));
        assertEquals(RoamTrackSimplifier.MAX_POINTS, parsed.size());
        assertEquals(121.0, parsed.get(0).get(1), 1e-9);
        assertEquals(121.0999, parsed.get(parsed.size() - 1).get(1), 1e-9);
    }

    /** 一个点画不成线,坏 JSON / 空值都按没有轨迹,不存也不抛(轨迹只是展示,不能让结算失败)。 */
    @Test
    void rejectsSinglePointAndGarbage() {
        assertNull(RoamTrackSimplifier.simplify("[[31.2,121.4]]"));
        assertNull(RoamTrackSimplifier.simplify("not json"));
        assertNull(RoamTrackSimplifier.simplify(null));
        assertNull(RoamTrackSimplifier.parse(null));
    }
}
