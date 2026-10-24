package com.chengyinhub.web.controller.api;

import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S5(2026-10-24 上线前快修):广场「当前账号未开放社区」的来源核对。
 *
 * <p>结论:不是缺默认值/键名写错的 bug,而是刻意的灰度闸 —— 三个 sys_config 键都没配时,
 * 生产上任何新普通玩家看到的都是「当前账号未开放社区」。本测试把上线需要 owner 配置的**确切键值**钉住:
 * 键名一旦被改,运营按交付文档配置就会失效,这里会先红。
 */
class CommunityPostLaunchConfigTest {

    private final ISysConfigService config = mock(ISysConfigService.class);
    private final CommunityPostFeatureGate gate = new CommunityPostFeatureGate(config);

    @Test
    void documentedKeysAreTheOnesTheGateReads() {
        assertEquals("feature.flag.communityPostRead", CommunityPostFeatureGate.READ_KEY);
        assertEquals("feature.flag.communityPostWrite", CommunityPostFeatureGate.WRITE_KEY);
        assertEquals("feature.flag.communityPostPilotMembers", CommunityPostFeatureGate.PILOT_MEMBERS_KEY);
    }

    @Test
    void unconfiguredProduction_newOrdinaryPlayerIsBlocked() {
        long brandNewPlayer = 987654L;
        assertFalse(gate.canRead(brandNewPlayer), "三个键都未配置:新玩家读不到广场(刻意默认关)");
        assertFalse(gate.canWrite(brandNewPlayer));
    }

    @Test
    void flagsOnButNoPilotList_stillBlocked() {
        when(config.selectConfigByKey("feature.flag.communityPostRead")).thenReturn("true");
        when(config.selectConfigByKey("feature.flag.communityPostWrite")).thenReturn("true");
        assertFalse(gate.canRead(987654L), "只开开关不配白名单仍然关 —— 上线必须同时配 PilotMembers");
        assertFalse(gate.canWrite(987654L));
    }

    @Test
    void documentedLaunchConfig_opensForEveryOrdinaryPlayer() {
        when(config.selectConfigByKey("feature.flag.communityPostRead")).thenReturn("true");
        when(config.selectConfigByKey("feature.flag.communityPostWrite")).thenReturn("true");
        when(config.selectConfigByKey("feature.flag.communityPostPilotMembers")).thenReturn("*");
        assertTrue(gate.canRead(987654L));
        assertTrue(gate.canWrite(987654L));
        assertTrue(gate.canRead(0L), "全量 * 时未登录访客可读");
        assertFalse(gate.canWrite(0L), "未登录访客永远不能发帖");
    }
}
