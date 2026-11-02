package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.PlayerGrowth;
import com.chengyinhub.business.mapper.PlayerGrowthMapper;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.impl.PlayerProgressionServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * P-22(2026-11-02 业主批准):成就页「累计打卡 N 处」= 核销过的店 + 完成的城市站点,不重复计;
 * 公里数暂不展示(里程没有真实来源)。
 */
class PlayGrowthCheckinPlacesTest {

    @Test
    void growthReturnsCheckinPlacesFromService() {
        IPlayerProgressionService progression = mock(IPlayerProgressionService.class);
        PlayerGrowth g = new PlayerGrowth();
        g.setMemberId(9004L); g.setLevel(2); g.setTotalCheckins(0);
        g.setTotalMileage(BigDecimal.ZERO); g.setStreakDays(1);
        when(progression.getGrowth(9004L)).thenReturn(g);
        when(progression.countCheckinPlaces(9004L)).thenReturn(4);
        PlayPlayerQueryApplication app = new PlayPlayerQueryApplication();
        ReflectionTestUtils.setField(app, "playerProgressionService", progression);

        AjaxResult res = app.growth(9004L);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.get(AjaxResult.DATA_TAG);
        assertEquals(4, data.get("checkinPlaces"));
        // 旧字段不动:其它读端仍按原口径读
        assertEquals(0, data.get("totalCheckins"));
    }

    @Test
    void serviceSumsShopsAndStationsAndTreatsMissingAsZero() {
        PlayerGrowthMapper mapper = mock(PlayerGrowthMapper.class);
        PlayerProgressionServiceImpl svc = new PlayerProgressionServiceImpl();
        ReflectionTestUtils.setField(svc, "playerGrowthMapper", mapper);

        when(mapper.countRedeemedShops(7L)).thenReturn(4);
        when(mapper.countCompletedCityStations(7L)).thenReturn(3);
        assertEquals(7, svc.countCheckinPlaces(7L));

        when(mapper.countRedeemedShops(8L)).thenReturn(null);
        when(mapper.countCompletedCityStations(8L)).thenReturn(null);
        assertEquals(0, svc.countCheckinPlaces(8L));

        assertEquals(0, svc.countCheckinPlaces(null));
        assertEquals(0, svc.countCheckinPlaces(0L));
        verify(mapper, never()).countRedeemedShops(0L);
    }

    /** 两段 SQL 互不重叠:店只数生效核销,站点排除商家节点与自由探索主题,两段都按 id 去重。 */
    @Test
    void mapperSqlKeepsShopsAndStationsDisjoint() throws Exception {
        String xml = new String(new ClassPathResource("mapper/business/PlayerGrowthMapper.xml")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String shops = select(xml, "countRedeemedShops");
        assertTrue(shops.contains("count(distinct r.chapter_id)"));
        assertTrue(shops.contains("reg.member_id = #{memberId}"));
        assertTrue(shops.contains("r.status = 'ACTIVE'"));

        String stations = select(xml, "countCompletedCityStations");
        assertTrue(stations.contains("count(distinct p.node_id)"));
        assertTrue(stations.contains("p.member_id = #{memberId}"));
        assertTrue(stations.contains("p.status = 1"));
        assertTrue(stations.contains("p.del_flag = 0"));
        assertTrue(stations.contains("n.merchant_member_id is null"), "商家节点只能由核销计,不得再按游戏完成计一次");
        assertTrue(stations.contains("t.product_type != 2"), "自由探索主题的节点不算城市站点");
        // Druid wall:不得出现恒假片段或 BINARY 比较
        assertFalse(stations.contains("1 = 0") || shops.contains("1 = 0"));
        assertFalse(stations.toUpperCase().contains("BINARY") || shops.toUpperCase().contains("BINARY"));
    }

    private static String select(String xml, String id) {
        Matcher m = Pattern.compile("<select id=\"" + id + "\"[^>]*>([\\s\\S]*?)</select>").matcher(xml);
        assertTrue(m.find(), "缺 " + id);
        return m.group(1);
    }
}
