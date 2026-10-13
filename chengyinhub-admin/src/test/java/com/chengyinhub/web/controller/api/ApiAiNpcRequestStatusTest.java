package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.business.service.impl.NpcChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ApiAiNpcRequestStatusTest {
    private static final String RID = "abcdef01-abcd-4abc-8abc-abcdefabcdef";
    private ApiAiNpcController controller;
    private NpcFeatureFlags flags;
    private NpcChatService chat;
    private PlayTeamMapper registrations;
    private SpeechToTextService speech;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        controller = spy(new ApiAiNpcController());
        doReturn(9L).when(controller).getAppUserId();
        flags = mock(NpcFeatureFlags.class);
        when(flags.shopChatOn()).thenReturn(true);
        when(flags.merchantChatOn()).thenReturn(true);
        registrations = mock(PlayTeamMapper.class);
        speech = mock(SpeechToTextService.class);
        chat = mock(NpcChatService.class);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(11L); node.setTopicId(31L); node.setMerchantMemberId(41L);
        when(nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        ReflectionTestUtils.setField(controller, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(controller, "playTeamMapper", registrations);
        ReflectionTestUtils.setField(controller, "speechToTextService", speech);
        ReflectionTestUtils.setField(controller, "npcChatService", chat);
        NpcChatResp result = new NpcChatResp();
        result.setRequestId(RID); result.setOutcomeStatus("SUCCEEDED"); result.setSafetyDecision("PASS"); result.setSafeText("原回复");
        when(chat.requestStatus(RID, 9L, "shop", 11L)).thenReturn(result);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void expiredPassBlocksStatusBeforeReadingOriginalText() throws Exception {
        mvc.perform(get("/api/ai/npc/request-status").param("requestId", RID).param("scope", "shop").param("bizId", "11"))
                .andExpect(jsonPath("$.code").value(403));
        verify(registrations).hasCurrentNpcRegistration(9L, 31L);
        verifyNoInteractions(chat, speech);
    }

    @Test
    void renewedPassReadsOriginalRequestForAuthenticatedOwnerOnly() throws Exception {
        when(registrations.hasCurrentNpcRegistration(9L, 31L)).thenReturn(1);
        mvc.perform(get("/api/ai/npc/request-status").param("requestId", RID).param("scope", "shop")
                        .param("bizId", "11").param("userId", "10"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.requestId").value(RID))
                .andExpect(jsonPath("$.data.safeText").value("原回复"));
        verify(chat).requestStatus(RID, 9L, "shop", 11L);
        verifyNoMoreInteractions(chat);
        verifyNoInteractions(speech);
    }

    @Test
    void allScopeFlagsRemainClosedWithoutTouchingChatOrSpeech() {
        when(flags.shopChatOn()).thenReturn(false);
        when(flags.merchantChatOn()).thenReturn(false);
        for (String scope : new String[]{"shop", "merchant", "roam"}) {
            org.junit.jupiter.api.Assertions.assertEquals(403, controller.requestStatus(RID, scope, 11L).get("code"));
        }
        verifyNoInteractions(chat, speech, registrations);
    }

    @Test
    void noLoginAndInvalidScopeNeverReadOrGenerate() {
        org.junit.jupiter.api.Assertions.assertEquals(500, controller.requestStatus(RID, "global", 11L).get("code"));
        doReturn(null).when(controller).getAppUserId();
        org.junit.jupiter.api.Assertions.assertEquals(401, controller.requestStatus(RID, "shop", 11L).get("code"));
        verifyNoInteractions(chat, speech, registrations);
    }
}
