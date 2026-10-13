package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.CityNodeVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.chengyinhub.web.controller.api.ApiRoamController.geoKey;
import static com.chengyinhub.web.controller.api.ApiRoamController.nearestWithin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 点亮城市「点到店」纯逻辑:坐标取整去重键 + 参与据点就近匹配。
 * (宽松 geo/幂等入库在真 MySQL 数据层验)
 */
class RoamCheckinTest {

    @Test
    void geoKey_samePoiStableSameKey() {
        assertEquals(geoKey(31.2304, 121.4737), geoKey(31.2304, 121.4737), "同坐标同键");
        assertEquals("31.230,121.474", geoKey(31.2304, 121.4737), "取整到3位小数");
    }

    @Test
    void geoKey_farPoiDifferentKey() {
        assertNotEquals(geoKey(31.230, 121.470), geoKey(31.240, 121.480), "相隔约1km键不同");
    }

    private CityNodeVO node(long poiId, double lat, double lng) {
        CityNodeVO v = new CityNodeVO();
        v.setPoiId(poiId);
        v.setLat(BigDecimal.valueOf(lat));
        v.setLng(BigDecimal.valueOf(lng));
        return v;
    }

    @Test
    void nearestWithin_matchesInsideRadius() {
        // 0.0001°纬度 ≈ 11m,50m 内命中
        List<CityNodeVO> nodes = Arrays.asList(node(7L, 31.2301, 121.4700));
        assertEquals(Long.valueOf(7L), nearestWithin(nodes, 31.2300, 121.4700, 50), "11m 内命中");
    }

    @Test
    void nearestWithin_nullWhenAllOutside() {
        // 0.001°纬度 ≈ 111m,超出 50m
        List<CityNodeVO> nodes = Arrays.asList(node(7L, 31.2310, 121.4700));
        assertNull(nearestWithin(nodes, 31.2300, 121.4700, 50), "111m 超出 50m → null");
    }

    @Test
    void nearestWithin_picksNearest() {
        List<CityNodeVO> nodes = Arrays.asList(
                node(7L, 31.2303, 121.4700),   // ~33m
                node(9L, 31.2301, 121.4700));  // ~11m
        assertEquals(Long.valueOf(9L), nearestWithin(nodes, 31.2300, 121.4700, 50), "取最近的 9");
    }

    @Test
    void nearestWithin_emptyOrNull() {
        assertNull(nearestWithin(new ArrayList<>(), 31.23, 121.47, 50), "空列表 → null");
        assertNull(nearestWithin(null, 31.23, 121.47, 50), "null → null");
    }
}
