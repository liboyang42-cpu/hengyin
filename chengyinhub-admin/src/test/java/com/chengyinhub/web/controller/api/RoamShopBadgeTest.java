package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「三店连亮」配置化 + 真落库的锁。
 *
 * 为什么需要这个类:这枚章此前是**纯前端假徽章**(_popMedal 只 setData、零请求,勋章墙查不到,
 * 而 shareMedal 还能把它画成「徽章到手」的图分享出去)。本轮让它真落 player_badge,
 * 并把阈值/文案/图从代码里挪进 growth_badge.unlock_rule_json。
 *
 * ★ 最要紧的一条是 threshold 真的来自 def:如果代码里还藏着 `if (shops == 3)`,
 *   "配置化"就是假的 —— 改 SQL 不会改变行为。testcase 用 threshold=5 喂 3 家来钉死它。
 */
class RoamShopBadgeTest {

    private static final Long MEMBER = 77L;
    private static final String CODE = "ROAM_SHOP_STREAK";

    private ApiRoamController controller;
    private GrowthBadgeMapper growthBadgeMapper;
    private PlayerBadgeMapper playerBadgeMapper;
    private com.chengyinhub.business.service.impl.RoamBadgeDeliveryService delivery;

    @BeforeEach
    void setUp() {
        controller = new ApiRoamController();
        growthBadgeMapper = mock(GrowthBadgeMapper.class);
        playerBadgeMapper = mock(PlayerBadgeMapper.class);
        delivery = mock(com.chengyinhub.business.service.impl.RoamBadgeDeliveryService.class);
        ReflectionTestUtils.setField(controller, "roamBadgeDeliveryService", delivery);
        ReflectionTestUtils.setField(controller, "growthBadgeMapper", growthBadgeMapper);
        ReflectionTestUtils.setField(controller, "playerBadgeMapper", playerBadgeMapper);
        ReflectionTestUtils.setField(controller, "ruleEvaluator", new RuleEvaluator());  // 纯函数,不 mock
    }

    private GrowthBadge def(String ruleJson) {
        GrowthBadge d = new GrowthBadge();
        d.setBadgeCode(CODE);
        d.setBadgeName("初探 · 连亮三店");
        d.setStatement("一口气点亮 3 处,城市开始记得你");
        d.setIconUrl("https://x/icon.png");
        d.setUnlockRuleJson(ruleJson);
        d.setStatus("0");   // '0'=启用
        return d;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> award(int shops) {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(controller, "awardShopStreak", MEMBER, 9L, shops);
    }

    /* ============ ★ 配置化本身 ============ */

    @Test
    void threshold_comesFromDef_notHardcoded3() {
        // def 说 5 家才发。喂 3 家 —— 若代码里还藏着 if(shops==3) 就会误发
        when(growthBadgeMapper.selectByCode(CODE)).thenReturn(def("{\"threshold\":5}"));
        when(playerBadgeMapper.selectOne(anyLong(), anyString(), anyLong())).thenReturn(null);

        assertNull(award(3), "def 阈值=5,探 3 家不该发章(发了=阈值还硬编码在代码里)");
        verify(playerBadgeMapper, never()).insertPlayerBadge(any());
    }

    @Test
    void threshold_fromDef_metAtConfiguredValue() {
        when(growthBadgeMapper.selectByCode(CODE)).thenReturn(def("{\"threshold\":5}"));
        when(playerBadgeMapper.selectOne(anyLong(), anyString(), anyLong())).thenReturn(null);

        assertNotNull(award(5), "def 阈值=5,探 5 家应发章");
        verify(delivery).prepare(eq(9L), any());
    }

    @Test
    void copy_comesFromDef_notHardcoded() {
        // 文案改配置就变,不发版
        GrowthBadge d = def("{\"threshold\":3}");
        d.setBadgeName("改过的名字");
        d.setStatement("改过的文案");
        when(growthBadgeMapper.selectByCode(CODE)).thenReturn(d);
        when(playerBadgeMapper.selectOne(anyLong(), anyString(), anyLong())).thenReturn(null);

        Map<String, Object> m = award(3);
        assertNotNull(m);
        assertEquals("改过的名字", m.get("name"));
        assertEquals("改过的文案", m.get("statement"));
    }

    /* ============ 真落库 ============ */

    @Test
    void awards_realBadgeRow_withIconFromDef() {
        when(growthBadgeMapper.selectByCode(CODE)).thenReturn(def("{\"threshold\":3}"));
        when(playerBadgeMapper.selectOne(anyLong(), anyString(), anyLong())).thenReturn(null);

        assertNotNull(award(3));
        ArgumentCaptor<PlayerBadge> cap = ArgumentCaptor.forClass(PlayerBadge.class);
        verify(delivery).prepare(eq(9L), cap.capture());
        PlayerBadge b = cap.getValue();
        assertEquals(MEMBER, b.getMemberId());
        assertEquals(CODE, b.getBadgeCode());
        // ROAM_TILES_* 漏了 setIconUrl 导致 icon 恒 null(四层墙没图),这条钉死新章不重蹈
        assertEquals("https://x/icon.png", b.getIconUrl(), "icon 必须取自 def");
        assertEquals(Long.valueOf(0L), b.getSourceId(), "source_id 恒 0 = 终身一次;用阈值做 source_id 会让调阈值后重复发");
        assertEquals(Integer.valueOf(4), b.getSourceType());
    }

    @Test
    void lifetimeOnce_notReAwarded() {
        when(growthBadgeMapper.selectByCode(CODE)).thenReturn(def("{\"threshold\":3}"));
        when(playerBadgeMapper.selectOne(eq(MEMBER), eq(CODE), eq(0L))).thenReturn(new PlayerBadge());

        assertNull(award(3), "已拿过就不再发");
        verify(playerBadgeMapper, never()).insertPlayerBadge(any());
    }

    /* ============ def 缺失 = 静默不发(必须可观测) ============ */

    @Test
    void defMissing_doesNotAward() {
        // 迁移没跑 → selectByCode 返 null。不能崩,也不能瞎发
        when(growthBadgeMapper.selectByCode(CODE)).thenReturn(null);

        assertNull(award(99));
        verify(playerBadgeMapper, never()).insertPlayerBadge(any());
    }

    @Test
    void malformedRuleJson_doesNotAward() {
        // RuleEvaluator.parse 对非法 json → threshold=MAX_VALUE(安全默认,永不授予)
        when(growthBadgeMapper.selectByCode(CODE)).thenReturn(def("{ not json"));
        when(playerBadgeMapper.selectOne(anyLong(), anyString(), anyLong())).thenReturn(null);

        assertNull(award(999), "规则 json 坏了应当不发,而不是乱发");
        verify(playerBadgeMapper, never()).insertPlayerBadge(any());
    }

    @Test
    void eligibleBadgeQueuesFrozenRightInsteadOfDirectInsert() {
        // Core settlement queues a durable right; delivery receipts control the client award display.
        when(growthBadgeMapper.selectByCode(CODE)).thenReturn(def("{\"threshold\":3}"));
        when(playerBadgeMapper.selectOne(anyLong(), anyString(), anyLong())).thenReturn(null);
        when(playerBadgeMapper.insertPlayerBadge(any())).thenThrow(new RuntimeException("dup"));

        assertNotNull(award(3));
        verify(delivery).prepare(eq(9L), any());
        verify(playerBadgeMapper, never()).insertPlayerBadge(any());
    }

    @Test
    void killSwitch_disabledDefDoesNotAward() {
        // 发现被刷 → UPDATE status='1' 停用。selectByCode 不带 status 过滤,故必须在发章处判。
        GrowthBadge d = def("{\"threshold\":3}");
        d.setStatus("1");   // 停用
        when(growthBadgeMapper.selectByCode(CODE)).thenReturn(d);
        when(playerBadgeMapper.selectOne(anyLong(), anyString(), anyLong())).thenReturn(null);

        assertNull(award(3), "def 停用(status=1)就不发章 —— 这是被刷后的 kill switch");
        verify(playerBadgeMapper, never()).insertPlayerBadge(any());
    }

    @Test
    void belowThreshold_noAward() {
        when(growthBadgeMapper.selectByCode(CODE)).thenReturn(def("{\"threshold\":3}"));
        when(playerBadgeMapper.selectOne(anyLong(), anyString(), anyLong())).thenReturn(null);

        assertNull(award(2));
        verify(playerBadgeMapper, never()).insertPlayerBadge(any());
    }
}
