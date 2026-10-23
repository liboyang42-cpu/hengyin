package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MemberVisitedPoi;
import com.chengyinhub.business.mapper.MemberVisitedPoiMapper;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O-02:点到店身份不再是 3 位小数坐标。同一点不同精度只算一次;地标按 roam_poi.id 识别;
 * 3 位小数相同但相距数十米的两家不同店不再被合并。
 */
class VisitedPoiIdentityResolverTest
{
    private static final long PLAYER = 9L;

    private ISysConfigService config;
    private MemberVisitedPoiMapper mapper;
    private VisitedPoiIdentityResolver resolver;
    /** 模拟库里该玩家已有的到点记录(包围盒查询直接返回全部,由被测代码按距离精筛) */
    private final List<MemberVisitedPoi> stored = new ArrayList<>();

    @BeforeEach
    void setUp()
    {
        config = mock(ISysConfigService.class);
        mapper = mock(MemberVisitedPoiMapper.class);
        resolver = new VisitedPoiIdentityResolver();
        ReflectionTestUtils.setField(resolver, "configService", config);
        ReflectionTestUtils.setField(resolver, "visitedPoiMapper", mapper);
        when(mapper.selectLandmarkPoisInBox(any(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(mapper.selectMemberVisitsInBox(anyLong(), any(), any(), any(), any())).thenReturn(stored);
        when(mapper.insertVisitedPoiWithKeyIgnore(anyLong(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    MemberVisitedPoi row = new MemberVisitedPoi();
                    row.setId((long) (stored.size() + 1));
                    row.setPoiKey(inv.getArgument(1));
                    row.setPoiName(inv.getArgument(2));
                    row.setLat(inv.getArgument(3));
                    row.setLng(inv.getArgument(4));
                    stored.add(row);
                    return 1;
                });
    }

    private static MemberVisitedPoi row(long id, String name, double lat, double lng)
    {
        MemberVisitedPoi r = new MemberVisitedPoi();
        r.setId(id);
        r.setPoiName(name);
        r.setLat(BigDecimal.valueOf(lat));
        r.setLng(BigDecimal.valueOf(lng));
        return r;
    }

    @Test
    void switchDefaultsOff()
    {
        assertFalse(resolver.enabled());
        when(config.selectConfigByKey(VisitedPoiIdentityResolver.CONFIG_STABLE_IDENTITY)).thenReturn("true");
        assertTrue(resolver.enabled());
        when(config.selectConfigByKey(VisitedPoiIdentityResolver.CONFIG_STABLE_IDENTITY))
                .thenThrow(new IllegalStateException("down"));
        assertFalse(resolver.enabled());
    }

    @Test
    void samePointAtDifferentPrecisionCountsOnce()
    {
        // 第一次 6 位小数,第二次同一家店被取整到 3 位小数(旧 geo_key 下 31.2305→"31.231" 与 31.2304→"31.230" 分属两键)
        assertTrue(resolver.recordVisit(PLAYER, 31.230449, 121.473651, "某咖啡店", new Date()));
        assertFalse(resolver.recordVisit(PLAYER, 31.230, 121.474, "某咖啡店", new Date()));
        assertFalse(resolver.recordVisit(PLAYER, 31.2304491, 121.4736509, "某咖啡店", new Date()));
        verify(mapper).insertVisitedPoiWithKeyIgnore(eq(PLAYER), anyString(), any(), any(), any(), any());
    }

    @Test
    void differentShopsSharingAThreeDecimalCellAreNotMerged()
    {
        // 两家店相距约 30m,旧 geo_key 都是 "31.230,121.474" ⇒ 第二家永远点不亮
        assertTrue(resolver.recordVisit(PLAYER, 31.23010, 121.47360, "甲书店", new Date()));
        assertTrue(resolver.recordVisit(PLAYER, 31.23037, 121.47360, "乙面馆", new Date()));
        assertEquals(2, stored.size());
        assertTrue(!stored.get(0).getPoiKey().equals(stored.get(1).getPoiKey()));
    }

    @Test
    void unnamedPointsOnlyMergeWithinFiveMeters()
    {
        assertTrue(resolver.recordVisit(PLAYER, 31.23010, 121.47360, "未知地点", new Date()));
        assertFalse(resolver.recordVisit(PLAYER, 31.23012, 121.47361, "未知地点", new Date()));
        assertTrue(resolver.recordVisit(PLAYER, 31.23040, 121.47360, "未知地点", new Date()));
    }

    @Test
    void landmarkIdentityIsRoamPoiId()
    {
        MemberVisitedPoi landmark = row(777L, "人民广场", 31.23040, 121.47370);
        when(mapper.selectLandmarkPoisInBox(any(), any(), any(), any())).thenReturn(List.of(landmark));

        assertTrue(resolver.recordVisit(PLAYER, 31.23050, 121.47380, "人民广场", new Date()));
        verify(mapper).insertVisitedPoiWithKeyIgnore(eq(PLAYER), eq("poi:777"), any(), any(), any(), any());

        // 从地标另一侧点进来(相距 >5m、名字也不同),仍是同一个地标
        when(mapper.countByPoiKey(PLAYER, "poi:777")).thenReturn(1);
        assertFalse(resolver.recordVisit(PLAYER, 31.23010, 121.47350, "人民广场地铁站", new Date()));
        assertEquals(1, stored.size());
    }

    @Test
    void legacyRowWithoutPoiKeyStillDedupesByDistance()
    {
        // 回填前的旧行(poi_key 为 null)仍参与距离比对,开关打开后不会重复发探索值
        stored.add(row(1L, "某咖啡店", 31.230, 121.474));
        assertFalse(resolver.recordVisit(PLAYER, 31.230449, 121.473651, "某咖啡店", new Date()));
        verify(mapper, never()).insertVisitedPoiWithKeyIgnore(anyLong(), anyString(), any(), any(), any(), any());
    }

    @Test
    void pointKeyFitsLegacyGeoKeyColumn()
    {
        assertEquals("pt:31.23045,121.47365", VisitedPoiIdentityResolver.pointKey(31.230449, 121.473651));
        assertTrue(VisitedPoiIdentityResolver.pointKey(-89.999999, -179.999999).length() <= 24);
        assertTrue(("poi:" + Long.MAX_VALUE).length() <= 24);
    }

    @Test
    void nearestWithinPicksClosestAndIgnoresOutOfRange()
    {
        List<MemberVisitedPoi> rows = List.of(row(5L, "远", 31.2310, 121.4737), row(6L, "近", 31.23045, 121.4737));
        assertEquals(6L, VisitedPoiIdentityResolver.nearestWithin(rows, 31.2304, 121.4737, 50d));
        assertNull(VisitedPoiIdentityResolver.nearestWithin(rows, 31.2404, 121.4737, 50d));
    }
}
