package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.dto.ai.NpcDialogueReq;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NpcDialogueServiceTest
{
    @Test
    void dialogueValidatesAndDelegatesEffectsViaRuntimeService()
    {
        NpcProfileMapper npcMapper = mock(NpcProfileMapper.class);
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        AiGatewayService gateway = mock(AiGatewayService.class);

        TopicRouteSession session = new TopicRouteSession();
        session.setId(101L);
        session.setTopicId(22L);
        session.setMemberId(7L);
        session.setStatus(TopicRouteSession.STATUS_RUNNING);

        NpcProfile profile = new NpcProfile();
        profile.setId(5L);
        profile.setScopeType(1);
        profile.setScopeId(22L);
        profile.setName("掌柜林婶");
        profile.setPersona("热心掌柜");
        profile.setRelationKey("relation.lin");
        profile.setEnabled(1);
        profile.setAuditStatus(1);

        ThemeStateKey relKey = new ThemeStateKey();
        relKey.setStateKey("relation.lin");
        relKey.setKeyType("relation");
        relKey.setLabel("林婶好感");

        when(runtime.requireNpcDialogueSession(7L, 22L, null, 101L)).thenReturn(session);
        when(runtime.findNpcDialogueReplay(session, 7L, "dlg:5:act-2")).thenReturn(null);
        when(runtime.findNpcDialogueReplay(session, 7L, "dlg:5:act-1")).thenReturn(null);
        when(npcMapper.selectThemeNpcById(5L)).thenReturn(profile);
        when(runtime.allowedNpcDialogueKeys(session)).thenReturn(Collections.singletonList(relKey));
        when(gateway.generate(eq(7L), eq("player"), eq("npc_dialogue"), anyString(), eq("你好掌柜")))
                .thenReturn(new AiGatewayResult("t1",
                        "```json\n{\"reply\":\"欢迎光临茶馆\",\"effects\":[{\"type\":\"INC\",\"key\":\"relation.lin\",\"delta\":1},{\"type\":\"ADD_ITEM\",\"key\":\"reward.gold\"}]}\n```",
                        "deepseek", 0));

        Map<String, Object> expectedReceipt = Collections.singletonMap("reply", "欢迎光临茶馆");
        when(runtime.commitNpcDialogue(eq(session), eq(7L), eq(5L), eq("掌柜林婶"), eq("relation.lin"),
                eq("dlg:5:act-1"), eq("act-1"), eq("欢迎光临茶馆"), anyList())).thenReturn(expectedReceipt);

        NpcDialogueServiceImpl service = new NpcDialogueServiceImpl(npcMapper, runtime, gateway);
        NpcDialogueReq req = new NpcDialogueReq();
        req.setRunId(101L);
        req.setTopicId(22L);
        req.setNpcId(5L);
        req.setUtterance("你好掌柜");
        req.setRouteActionId("act-1");

        Map<String, Object> result = service.dialogue(7L, req);
        assertEquals("欢迎光临茶馆", result.get("reply"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(runtime).commitNpcDialogue(eq(session), eq(7L), eq(5L), eq("掌柜林婶"), eq("relation.lin"),
                eq("dlg:5:act-1"), eq("act-1"), eq("欢迎光临茶馆"), captor.capture());
        assertEquals(2, captor.getValue().size());
        verify(gateway).generate(eq(7L), eq("player"), eq("npc_dialogue"), argThat(p -> p.contains("relation.lin")), eq("你好掌柜"));
    }

    @Test
    void replayShortCircuitsBeforeCallingLlm()
    {
        NpcProfileMapper npcMapper = mock(NpcProfileMapper.class);
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        AiGatewayService gateway = mock(AiGatewayService.class);

        TopicRouteSession session = new TopicRouteSession();
        session.setId(101L);
        session.setTopicId(22L);
        Map<String, Object> cached = Collections.singletonMap("reply", "历史回复");

        when(runtime.requireNpcDialogueSession(7L, null, null, 101L)).thenReturn(session);
        when(runtime.findNpcDialogueReplay(session, 7L, "dlg:5:act-1")).thenReturn(cached);

        NpcDialogueServiceImpl service = new NpcDialogueServiceImpl(npcMapper, runtime, gateway);
        NpcDialogueReq req = new NpcDialogueReq();
        req.setRunId(101L);
        req.setNpcId(5L);
        req.setUtterance("再次发送");
        req.setRouteActionId("act-1");

        assertSame(cached, service.dialogue(7L, req));
        verifyNoInteractions(gateway);
        verify(runtime, never()).commitNpcDialogue(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsCrossTopicOrDisabledNpc()
    {
        NpcProfileMapper npcMapper = mock(NpcProfileMapper.class);
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        AiGatewayService gateway = mock(AiGatewayService.class);

        TopicRouteSession session = new TopicRouteSession();
        session.setId(101L);
        session.setTopicId(22L);
        NpcProfile wrongTopic = new NpcProfile();
        wrongTopic.setId(5L);
        wrongTopic.setScopeId(99L);
        wrongTopic.setEnabled(1);
        wrongTopic.setAuditStatus(1);

        when(runtime.requireNpcDialogueSession(7L, 22L, null, 101L)).thenReturn(session);
        when(runtime.findNpcDialogueReplay(session, 7L, "dlg:5:act-2")).thenReturn(null);
        when(npcMapper.selectThemeNpcById(5L)).thenReturn(wrongTopic);

        NpcDialogueServiceImpl service = new NpcDialogueServiceImpl(npcMapper, runtime, gateway);
        NpcDialogueReq req = new NpcDialogueReq();
        req.setRunId(101L);
        req.setTopicId(22L);
        req.setNpcId(5L);
        req.setUtterance("Hello");
        req.setRouteActionId("act-2");

        assertThrows(ServiceException.class, () -> service.dialogue(7L, req));
        verifyNoInteractions(gateway);
    }
}
