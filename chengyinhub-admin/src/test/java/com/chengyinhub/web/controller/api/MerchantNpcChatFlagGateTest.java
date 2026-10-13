package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.service.impl.NpcChatService;
import com.chengyinhub.business.service.impl.NpcEventService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * /api/ai/npc/merchant-chat 的开关闸门。
 *
 * <p><b>为什么要单独一个测试类而不是塞进 NpcChatFlagGateTest:</b>
 * 门店轨与漫游轨是<b>两个开关、两个端点、两套合规范围</b>。合在一个类里,
 * 「改了漫游开关把门店一起放出去」这种事会因为共用 setUp 而看不出来。
 *
 * <p>这个类同时钉住<b>两轨互不干扰</b>:漫游开关的任何取值都不该让门店端点通,反之亦然。
 * 这条正是把两轨合并成一个端点加 scope 参数会丢掉的保证。
 */
class MerchantNpcChatFlagGateTest {

    private MockMvc mvc;
    private ISysConfigService configService;
    private NpcChatService chatService;

    @BeforeEach
    void setUp() {
        ApiAiNpcController c = spy(new ApiAiNpcController());
        doReturn(1001L).when(c).getAppUserId();
        configService = mock(ISysConfigService.class);
        chatService = mock(NpcChatService.class);
        when(chatService.chatWithMerchant(any(), anyLong(), anyLong())).thenReturn(new NpcChatResp());
        // NpcFeatureFlags 用真货,只把它读的 sys_config 换成 mock —— 闸门判定本身必须是真的。
        NpcFeatureFlags flags = new NpcFeatureFlags();
        ReflectionTestUtils.setField(flags, "configService", configService);
        ReflectionTestUtils.setField(c, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(c, "npcChatService", chatService);
        ReflectionTestUtils.setField(c, "npcProfileService", mock(NpcProfileService.class));
        ReflectionTestUtils.setField(c, "npcEventService", mock(NpcEventService.class));
        mvc = MockMvcBuilders.standaloneSetup(c).build();
    }

    private void merchantFlag(String value) {
        when(configService.selectConfigByKey("feature.flag.merchantNpcChat")).thenReturn(value);
    }

    private void roamFlags(String event, String chat) {
        when(configService.selectConfigByKey("feature.flag.roamNpcEvent")).thenReturn(event);
        when(configService.selectConfigByKey("feature.flag.roamNpcChat")).thenReturn(chat);
    }

    private void expectMerchantChat(int code) throws Exception {
        mvc.perform(post("/api/ai/npc/merchant-chat").contentType("application/json")
                        .content("{\"requestId\":\"r1\",\"bizId\":42,\"message\":\"有什么推荐\"}"))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void flagOff_returns403_andNeverReachesService() throws Exception {
        merchantFlag("false");
        expectMerchantChat(403);
        verify(chatService, never()).chatWithMerchant(any(), anyLong(), anyLong());
    }

    /** sys_config 里压根没这一行(迁移没跑)⇒ flagOn(null)=false ⇒ 关。失败方向必须是关。 */
    @Test
    void flagAbsent_returns403() throws Exception {
        merchantFlag(null);
        expectMerchantChat(403);
        verify(chatService, never()).chatWithMerchant(any(), anyLong(), anyLong());
    }

    /** 开关开 ⇒ 放行,且把 bizId 当 merchantId 传给 service。正向用例:没有它,恒关也能全绿。 */
    @Test
    void flagOn_passesThroughWithMerchantIdFromBizId() throws Exception {
        merchantFlag("true");
        expectMerchantChat(200);
        verify(chatService).chatWithMerchant(any(), eq(1001L), eq(42L));
    }

    /**
     * ★ 两轨隔离负控之一:漫游那两个开关全开,门店端点仍必须拒绝。
     *
     * <p>红的场景:有人把 merchant-chat 的闸写成读 roamNpcChat,或把两轨并进一个端点。
     */
    @Test
    void roamFlagsOn_doesNotOpenMerchantChat() throws Exception {
        roamFlags("true", "true");
        merchantFlag(null);
        expectMerchantChat(403);
        verify(chatService, never()).chatWithMerchant(any(), anyLong(), anyLong());
    }

    /**
     * ★ 两轨隔离负控之二:门店开关开着,漫游 /chat 仍必须 403。
     *
     * <p>漫游侧 chatOn() 是硬编码 false 的合规闸,任何配置都不该能把它打开。
     */
    @Test
    void merchantFlagOn_doesNotOpenRoamChat() throws Exception {
        merchantFlag("true");
        roamFlags("true", "true");
        mvc.perform(post("/api/ai/npc/chat").contentType("application/json")
                        .content("{\"requestId\":\"r1\",\"bizId\":1,\"message\":\"hi\"}"))
                .andExpect(jsonPath("$.code").value(403));
        verify(chatService, never()).chat(any(), anyLong());
    }
}
