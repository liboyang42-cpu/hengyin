package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsPresetRoute;
import com.chengyinhub.business.mapper.CmsPresetRouteMapper;
import com.chengyinhub.business.mapper.CmsPresetRouteNodeMapper;
import com.chengyinhub.business.service.impl.CmsPresetRouteServiceImpl;
import com.chengyinhub.business.service.impl.PresetServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R9-25 + 裁决 20(2026-09-15):路线/预设下架(status=0)与删除(del_flag≠0)一样,
 * 公开详情直接返回不存在,不设已购例外;后台详情不受影响。
 *
 * <p>公开面 = {@link PresetServiceImpl#getRouteWithNodes}(/api/preset/route/info);
 * 后台面 = {@link CmsPresetRouteServiceImpl#selectCmsPresetRouteById}(/business/preset/route/{id})。
 * 两条路径共用同一个 mapper 方法,所以过滤只能加在公开服务里 —— 加进 SQL 会把后台一起挡掉。
 *
 * <p>⚠️ 负控:删掉 {@code isNotPubliclyVisible} 判定 ⇒ 下架/删除两条用例红;
 * 把过滤挪进 mapper SQL ⇒ {@code adminDetailStillReturnsOffShelfRoute} 红。
 */
class PresetRoutePublicVisibilityTest {

    private static final long ROUTE = 700L;

    private CmsPresetRouteMapper routeMapper;
    private CmsPresetRouteNodeMapper nodeMapper;
    private PresetServiceImpl publicService;
    private CmsPresetRouteServiceImpl adminService;

    @BeforeEach
    void setUp() {
        routeMapper = mock(CmsPresetRouteMapper.class);
        nodeMapper = mock(CmsPresetRouteNodeMapper.class);
        publicService = new PresetServiceImpl();
        adminService = new CmsPresetRouteServiceImpl();
        ReflectionTestUtils.setField(publicService, "routeMapper", routeMapper);
        ReflectionTestUtils.setField(publicService, "routeNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(adminService, "cmsPresetRouteMapper", routeMapper);
        ReflectionTestUtils.setField(adminService, "cmsPresetRouteNodeMapper", nodeMapper);
        when(nodeMapper.selectByRouteId(anyLong())).thenReturn(Collections.emptyList());
    }

    private CmsPresetRoute route(String status, Integer delFlag) {
        CmsPresetRoute r = new CmsPresetRoute();
        r.setId(ROUTE);
        r.setStatus(status);
        r.setDelFlag(delFlag);
        return r;
    }

    @Test
    void offShelfRouteIsNotServedByPublicLookup() {
        when(routeMapper.selectCmsPresetRouteById(ROUTE)).thenReturn(route("0", 0));

        assertNull(publicService.getRouteWithNodes(ROUTE),
                "下架(status=0)与删除一样:公开详情必须按不存在收口,不设已购例外");
    }

    @Test
    void deletedRouteIsNotServedByPublicLookup() {
        when(routeMapper.selectCmsPresetRouteById(ROUTE)).thenReturn(route("1", 2));

        assertNull(publicService.getRouteWithNodes(ROUTE), "已删除路线不得再由公开接口返回");
    }

    @Test
    void onShelfRouteStillServedWithNodes() {
        when(routeMapper.selectCmsPresetRouteById(ROUTE)).thenReturn(route("1", 0));

        CmsPresetRoute served = publicService.getRouteWithNodes(ROUTE);

        assertNotNull(served, "在架路线口径不变");
        assertEquals(ROUTE, served.getId());
        assertNotNull(served.getNodes());
    }

    @Test
    void missingRouteStaysNull() {
        when(routeMapper.selectCmsPresetRouteById(ROUTE)).thenReturn(null);

        assertNull(publicService.getRouteWithNodes(ROUTE));
    }

    /** 后台详情不受影响:同一条下架/删除件,后台查询仍必须拿得到(否则就没法重新上架)。 */
    @Test
    void adminDetailStillReturnsOffShelfRoute() {
        when(routeMapper.selectCmsPresetRouteById(ROUTE)).thenReturn(route("0", 0));

        CmsPresetRoute adminView = adminService.selectCmsPresetRouteById(ROUTE);

        assertNotNull(adminView, "后台详情不能跟着公开面一起收口 —— 过滤只能加在公开服务,不能进 SQL");
        assertEquals("0", adminView.getStatus());
    }
}
