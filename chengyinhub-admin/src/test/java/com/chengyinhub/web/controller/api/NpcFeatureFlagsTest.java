package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 补充方案 §13.1:Event=false 时 Chat 必须被服务端判关(非法组合)。
 *
 * <p>组合判定原先写在 ApiConfigController.normalizeNpcFlags 里,只归一 /api/config/features
 * 的返回 Map ⇒ 直调 /api/ai/npc/chat 完全绕开(端点闸独立读 roamNpcChat)。现在两个消费面
     * 共用本组件,判定只剩一份、没有第二条路可绕。端点侧的负控在 NpcChatFlagGateTest。
 */
class NpcFeatureFlagsTest {

    private ISysConfigService configService;
    private NpcFeatureFlags flags;
    private CommunityPostFeatureGate communityPostFeatureGate;

    @BeforeEach
    void setUp() {
        configService = mock(ISysConfigService.class);
        flags = new NpcFeatureFlags();
        communityPostFeatureGate = mock(CommunityPostFeatureGate.class);
        ReflectionTestUtils.setField(flags, "configService", configService);
    }

    private void config(String event, String chat) {
        when(configService.selectConfigByKey("feature.flag.roamNpcEvent")).thenReturn(event);
        when(configService.selectConfigByKey("feature.flag.roamNpcChat")).thenReturn(chat);
    }

    @Test
    void illegalCombo_chatForcedOff() {
        config("false", "true");
        assertFalse(flags.eventOn());
        assertFalse(flags.chatOn(), "Event 关则 Chat 强制关");
    }

    @Test
    void chatRemainsOffEvenWhenLegacyConfigsAreBothTrue() {
        config("true", "true");
        assertTrue(flags.eventOn());
        assertFalse(flags.chatOn(), "无形陪伴 V1 不允许遗留配置打开 Chat");

        config("true", "false");
        assertTrue(flags.eventOn());
        assertFalse(flags.chatOn());
    }

    @Test
    void bothOff_untouched() {
        config("false", "false");
        assertFalse(flags.eventOn());
        assertFalse(flags.chatOn(), "双关是合法组合,不该报警");
    }

    /** 开关值口径:两个消费面同源,防漂移出「features 下发 true 但端点 403」。 */
    @Test
    void flagOn_semantics() {
        assertTrue(NpcFeatureFlags.flagOn("true"));
        assertTrue(NpcFeatureFlags.flagOn("TRUE"));
        assertTrue(NpcFeatureFlags.flagOn("1"));
        assertFalse(NpcFeatureFlags.flagOn("false"));
        assertFalse(NpcFeatureFlags.flagOn(null));
        assertFalse(NpcFeatureFlags.flagOn(""));
        assertFalse(NpcFeatureFlags.flagOn("yes"));
    }

    /**
     * features 下发走同一个组件:遗留配置即使误开，roamNpcChat 仍必须是 false。键集合包含
     * 小程序使用的通用开关和 App 使用的门店 NPC 能力；客户端只按自己认识的键读取。
     *
     * <p>X09:原先下发 10 个键,其中 selfPlay / citymap / eggs / trackRecord / xpBudget
     * 两端都没有任何读取点——运营在 RuoYi 后台把它们改成 true 不会有任何效果,
     * 这比没有开关更危险。已从 FLAG_KEYS 移除,断言随之改为「键集合精确相等」,
     * 比原来的计数更严:多一个键少一个键都会被拦下。
     */
    @Test
    @SuppressWarnings("unchecked")
    void featuresEndpoint_readsThroughSameComponent() {
        config("false", "true");
        when(configService.selectConfigByKey("feature.flag.finishNearbyRoute")).thenReturn("true");
        ApiConfigController c = new ApiConfigController();
        ReflectionTestUtils.setField(c, "configService", configService);
        ReflectionTestUtils.setField(c, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(c, "communityPostFeatureGate", communityPostFeatureGate);

        AjaxResult r = c.features();
        Map<String, Object> data = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);

        assertEquals(false, data.get("roamNpcChat"), "非法组合下发必须归一为 false");
        assertEquals(false, data.get("roamNpcEvent"));
        assertEquals(
                new java.util.TreeSet<>(java.util.Arrays.asList(
                        "drops", "roamNpcEvent", "roamNpcChat", "roamDemoPois",
                        "finishNearbyRoute", "clubMembership", "communityPostRead",
                        "communityPostWrite", "shopNpcChat", "shopNpcVoice",
                        "shopNpcVoiceAvailable", "merchantNpcChat", "merchantDeskProducts")),
                new java.util.TreeSet<>(data.keySet()),
                "下发键集合必须与小程序/App 的受控功能键逐项一致");
        assertFalse(data.containsKey("selfPlay"), "无人读取的假开关不得再下发(X09)");
        assertFalse(data.containsKey("xpBudget"), "无人读取的假开关不得再下发(X09)");
        assertEquals(false, data.get("merchantNpcChat"), "门店主页对话必须默认关闭");
        assertEquals(false, data.get("clubMembership"), "会员收费功能必须默认关闭");
        assertEquals(false, data.get("merchantDeskProducts"), "D2:商家工作台商品与履约必须默认关闭");
        assertEquals(true, data.get("finishNearbyRoute"), "配置明确为 true 时必须能开启完赛推荐灰度");
    }

    @Test
    @SuppressWarnings("unchecked")
    void finishNearbyRoute_defaultsOffWhenConfigIsMissing() {
        config("false", "false");
        ApiConfigController c = new ApiConfigController();
        ReflectionTestUtils.setField(c, "configService", configService);
        ReflectionTestUtils.setField(c, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(c, "communityPostFeatureGate", communityPostFeatureGate);

        AjaxResult r = c.features();
        Map<String, Object> data = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);

        assertEquals(false, data.get("finishNearbyRoute"), "没有配置时不得改变全量用户的完赛体验");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shopNpcVoiceIsAvailableOnlyWhenChatFlagAndAsrAreBothReady() {
        when(configService.selectConfigByKey("feature.flag.shopNpcChat")).thenReturn("true");
        SpeechToTextService speech = mock(SpeechToTextService.class);
        when(speech.available()).thenReturn(true);
        ApiConfigController c = configController(speech);

        Map<String, Object> data = (Map<String, Object>) c.features().get(AjaxResult.DATA_TAG);

        assertEquals(true, data.get("shopNpcChat"));
        assertEquals(true, data.get("shopNpcVoice"));
        assertEquals(true, data.get("shopNpcVoiceAvailable"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shopNpcVoiceStaysOffWhenAsrIsUnavailable() {
        when(configService.selectConfigByKey("feature.flag.shopNpcChat")).thenReturn("true");
        SpeechToTextService speech = mock(SpeechToTextService.class);
        when(speech.available()).thenReturn(false);

        Map<String, Object> data = (Map<String, Object>) configController(speech).features().get(AjaxResult.DATA_TAG);

        assertEquals(true, data.get("shopNpcChat"));
        assertEquals(false, data.get("shopNpcVoice"));
        assertEquals(false, data.get("shopNpcVoiceAvailable"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shopNpcVoiceStaysOffWithoutChatFlagAndDoesNotProbeAsr() {
        when(configService.selectConfigByKey("feature.flag.shopNpcChat")).thenReturn(null);
        SpeechToTextService speech = mock(SpeechToTextService.class);

        Map<String, Object> data = (Map<String, Object>) configController(speech).features().get(AjaxResult.DATA_TAG);

        assertEquals(false, data.get("shopNpcChat"));
        assertEquals(false, data.get("shopNpcVoice"));
        assertEquals(false, data.get("shopNpcVoiceAvailable"));
        verify(speech, never()).available();
    }

    private ApiConfigController configController(SpeechToTextService speech) {
        ApiConfigController c = new ApiConfigController();
        ReflectionTestUtils.setField(c, "configService", configService);
        ReflectionTestUtils.setField(c, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(c, "speechToTextService", speech);
        // 合并 #1057×master 后 features() 同时下发 communityPost 两键,helper 必须一并注入。
        ReflectionTestUtils.setField(c, "communityPostFeatureGate", communityPostFeatureGate);
        return c;
    }
}
