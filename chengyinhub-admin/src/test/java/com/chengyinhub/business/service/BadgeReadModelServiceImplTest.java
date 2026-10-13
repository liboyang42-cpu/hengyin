package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.vo.BadgeWallItemVO;
import com.chengyinhub.business.domain.vo.BadgeWallV2VO;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.service.impl.BadgeReadModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BadgeReadModelServiceImplTest {

    private GrowthBadgeMapper defMapper;
    private PlayerBadgeMapper playerBadgeMapper;
    private BadgeReadModelServiceImpl svc;

    private GrowthBadge def(String code, String name, String assetType) {
        GrowthBadge g = new GrowthBadge();
        g.setBadgeCode(code); g.setBadgeName(name); g.setAssetType(assetType);
        return g;
    }

    @BeforeEach
    void setUp() {
        defMapper = mock(GrowthBadgeMapper.class);
        playerBadgeMapper = mock(PlayerBadgeMapper.class);
        svc = new BadgeReadModelServiceImpl();
        ReflectionTestUtils.setField(svc, "growthBadgeMapper", defMapper);
        ReflectionTestUtils.setField(svc, "playerBadgeMapper", playerBadgeMapper);
    }

    @Test
    void buildWallV2_groupsByAssetType_andMarksUnlocked() {
        when(defMapper.selectAllActive()).thenReturn(Arrays.asList(
            def("FIRST_STEP", "初次启程", "IDENTITY"),
            def("GW_ROUTE_3", "城市脚步·三", "GROWTH")
        ));
        PlayerBadge pb = new PlayerBadge();
        pb.setBadgeCode("FIRST_STEP"); pb.setObtainTime(new Date());
        when(playerBadgeMapper.selectListByMember(1L)).thenReturn(Collections.singletonList(pb));

        BadgeWallV2VO vo = svc.buildWallV2(1L);

        assertEquals(1, vo.getIdentity().size());
        assertTrue(vo.getIdentity().get(0).isUnlocked());
        assertEquals(1, vo.getGrowth().size());
        assertFalse(vo.getGrowth().get(0).isUnlocked()); // 未获得,锁定态
    }

    /**
     * unlock_hint 是锁定态卡片的全部意义:没有它,未点亮的身份卡就是一张不知道怎么点亮的灰卡
     * (PRD 8.3)。它要从 badge_def 一路活到 VO —— 中间任何一环漏列都是静默失效,
     * 与 PR#24 那个 selectByCode 漏列的 CRITICAL 同一失败家族,故单独钉住。
     */
    @Test
    void buildWallV2_carriesUnlockHintAndCategory_forLockedCard() {
        GrowthBadge locked = def("ID_CITY_ALLIANCE", "共创发生", "IDENTITY");
        locked.setUnlockHint("完成一次跨角色共创合作(即将开放)");
        locked.setCategory("CO_CREATE");
        when(defMapper.selectAllActive()).thenReturn(Collections.singletonList(locked));
        when(playerBadgeMapper.selectListByMember(1L)).thenReturn(Collections.<PlayerBadge>emptyList());

        BadgeWallV2VO vo = svc.buildWallV2(1L);

        BadgeWallItemVO item = vo.getIdentity().get(0);
        assertFalse(item.isUnlocked());
        assertEquals("完成一次跨角色共创合作(即将开放)", item.getUnlockHint());
        // category 决定墙面配色轨道,全 EXPLORE 会让五色塌成一色
        assertEquals("CO_CREATE", item.getCategory());
    }
}
