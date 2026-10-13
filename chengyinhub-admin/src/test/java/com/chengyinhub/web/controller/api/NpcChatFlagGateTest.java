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
 * /api/ai/npc/chat 的开关闸门:NpcFeatureFlags.chatOn() 非真时必须拒绝,且不得触达 NpcChatService。
 *
 * <p>为什么要真调入口(MockMvc)而不是只测纯函数:这正是「闸放错层」那类病 ——
 * 判定逻辑写对了、但入口没调用它,纯函数测试照样全绿。只有真打这个 URL 才抓得到。
 *
 * <p>为什么这个闸门重要:<b>不是「防烧钱」</b> —— PR #75 已把 NpcChatService 收编进
 * AiGatewayService(频控 + 内容安全 + 审计日志都在网关里),那个洞已经堵上了。
 * 这里挡的是**合规**:Chat 轨被 Phase 0 合规签字阻断,签字前不该对任何已登录用户开放。
 * 组合约束(Event=false ⇒ Chat 恒关)与 /api/config/features 的下发共用 NpcFeatureFlags,
 * 判定只有一份 —— 曾经归一只写在 features 的返回 Map 上,直调本端点即绕开。
 *
 * <p>拒绝用 AjaxResult.error(HttpStatus.FORBIDDEN, ...) 而不是 servlet 层 sendError:
 * 与仓库既有惯用法一致(GlobalExceptionHandler#handleAccessDeniedException 就是这么写的),
 * 且 chat 在 #75 之后返回 AjaxResult —— 客户端认的是 body 里的 code。
 */
class NpcChatFlagGateTest {

    private MockMvc mvc;
    private ISysConfigService configService;
    private NpcChatService chatService;

    @BeforeEach
    void setUp() {
        // spy 而非 mock:只把 getAppUserId 换掉(standalone MockMvc 无登录态,真调会抛
        // 「获取APP用户信息异常」),其余走真实 controller 逻辑 —— 闸门本身必须是真的。
        ApiAiNpcController c = spy(new ApiAiNpcController());
        doReturn(1001L).when(c).getAppUserId();
        configService = mock(ISysConfigService.class);
        chatService = mock(NpcChatService.class);
        when(chatService.chat(any(), anyLong())).thenReturn(new NpcChatResp());
        // NpcFeatureFlags 用真货(只把它读的 sys_config 换成 mock):闸门判定本身必须是真的,
        // mock 掉组件就等于把被测的闸换成了假闸
        NpcFeatureFlags flags = new NpcFeatureFlags();
        ReflectionTestUtils.setField(flags, "configService", configService);
        ReflectionTestUtils.setField(c, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(c, "npcChatService", chatService);
        ReflectionTestUtils.setField(c, "npcProfileService", mock(NpcProfileService.class));
        ReflectionTestUtils.setField(c, "npcEventService", mock(NpcEventService.class));
        mvc = MockMvcBuilders.standaloneSetup(c).build();
    }

    /** 两个开关都要显式给:Chat 轨的开 = Event 开 且 Chat 开,只 stub 一个测不出组合。 */
    private void flags(String event, String chat) {
        when(configService.selectConfigByKey("feature.flag.roamNpcEvent")).thenReturn(event);
        when(configService.selectConfigByKey("feature.flag.roamNpcChat")).thenReturn(chat);
    }

    private void expectCode(String body, int code) throws Exception {
        mvc.perform(post("/api/ai/npc/chat").contentType("application/json").content(body))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void flagOff_returns403_andNeverReachesService() throws Exception {
        flags("true", "false");
        expectCode("{\"question\":\"hi\"}", 403);
        verify(chatService, never()).chat(any(), anyLong());
    }

    @Test
    void flagAbsent_returns403() throws Exception {
        flags("true", null);   // sys_config 里压根没这一行
        expectCode("{\"question\":\"hi\"}", 403);
        verify(chatService, never()).chat(any(), anyLong());
    }

    /**
     * ★ 负控本体:Event=false&Chat=true 是<b>非法组合</b>,端点必须自己判死,不能只靠 features 归一。
     *
     * <p>这条红过:归一只作用于 /api/config/features 的返回 Map,直调 /api/ai/npc/chat
     * 完全绕开它 —— 展示级的闸抵不了「Chat 轨 Phase0 合规签字未过」这个合规风险。
     */
    @Test
    void eventOff_chatOn_returns403_illegalComboCannotBypassEndpoint() throws Exception {
        flags("false", "true");
        expectCode("{\"question\":\"hi\"}", 403);
        verify(chatService, never()).chat(any(), anyLong());
    }

    /** ★ V1 负控:两项遗留配置都被误开时，端点仍必须拒绝且不得触达 Chat Service。 */
    @Test
    void bothLegacyFlagsOn_stillReturns403() throws Exception {
        flags("true", "true");
        expectCode("{\"question\":\"hi\"}", 403);
        verify(chatService, never()).chat(any(), anyLong());
    }
}
