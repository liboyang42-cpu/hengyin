package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IShopDayProofService;
import com.chengyinhub.business.service.support.RouteReachabilityProvider;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CX-42 验收:共享主题运行时的 themeAction(USE)。
 * <ul>
 *   <li>同 actionId 重试只扣一次库存、只写一次状态,返回同一份回执;</li>
 *   <li>库存为 0 时 USE 干净失败:无状态 CAS、无动作回执;</li>
 *   <li>非本局成员 403,无 CAS;</li>
 *   <li>同 actionId 换载荷(别的物品 / 别的操作)409「动作标识已用于其他操作」。</li>
 * </ul>
 * 夹具与 {@link PersonalGameplayRuntimeServiceTest} 同源(冻结发布 → state 建局),
 * 动作回执表按 actionId 的内存实现。
 */
class ThemeActionIdempotencyAcceptanceTest {
    private final ObjectMapper json = new ObjectMapper();
    private TopicRouteRuntimeServiceImpl service;
    private TopicRouteSessionMapper mapper;
    private TopicRouteSession session;
    private final Map<String, TopicRouteAction> receipts = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        IShopDayProofService arrival = mock(IShopDayProofService.class);
        service = new TopicRouteRuntimeServiceImpl(mapper, topics, nodes, templates, mock(PlayerNodeProgressMapper.class),
                null, registrations, null, arrival, () -> 1L, () -> 10, Clock.systemUTC(), RouteReachabilityProvider.UNKNOWN);
        CmsTopic topic = new CmsTopic();
        topic.setId(10L); topic.setRouteMode("LINEAR"); topic.setPublishedConfigVersion(1L);
        topic.setJourneyRulesJson(GenericRouteRuntimeServiceTest.rules());
        topic.setThemeRuntimeConfigJson(PersonalGameplayRuntimeServiceTest.config());
        when(topics.selectCmsTopicById(10L)).thenReturn(topic);
        List<CmsTopicNode> list = new ArrayList<>();
        List<CmsMemberTemplate> tpl = new ArrayList<>();
        for (long id : Arrays.asList(11L, 12L)) {
            CmsTopicNode n = new CmsTopicNode();
            n.setId(id); n.setTopicId(10L); n.setStatus(1L); n.setMerchantMemberId(30L); n.setTemplateId(id);
            list.add(n);
            when(nodes.selectCmsTopicNodeById(id)).thenReturn(n);
            CmsMemberTemplate t = new CmsMemberTemplate();
            t.setId(id);
            t.setAdvancedConfigJson("{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"答案\",\"answerText\":\"正确\"}}");
            tpl.add(t);
        }
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(list);
        when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(tpl);
        topic.setPublishedRouteGraphJson(service.freezePublication(topic, null));
        // 移植分支 A包当前资格按 activity→registration 锁读当前行(CurrentPlayRegistration)，夹具同 PersonalGameplayRuntimeServiceTest。
        CurrentRegistrationStubs.currentFromProbe(registrations);
        when(arrival.hasArrived(anyLong(), anyLong(), anyLong())).thenReturn(true);
        when(mapper.selectByOwner(0L, 10L, 7L, "default")).thenAnswer(i -> session);
        when(mapper.selectCurrentByOwner(0L, 10L, 7L)).thenAnswer(i -> session);
        when(mapper.selectAccessibleById(anyLong(), eq(7L)))
                .thenAnswer(i -> session != null && i.getArgument(0).equals(session.getId()) ? session : null);
        when(mapper.insertSession(any())).thenAnswer(i -> { session = i.getArgument(0); session.setId(99L); return 1; });
        when(mapper.insertAction(any())).thenAnswer(i -> {
            TopicRouteAction a = i.getArgument(0);
            if (receipts.containsKey(a.getActionId())) throw new org.springframework.dao.DuplicateKeyException("uk_action");
            a.setId((long) receipts.size() + 1);
            receipts.put(a.getActionId(), a);
            return 1;
        });
        when(mapper.selectActionByAction(eq(99L), eq(7L), anyString())).thenAnswer(i -> receipts.get(i.getArgument(2)));
        when(mapper.updateActionResponse(anyLong(), anyString())).thenAnswer(i -> {
            for (TopicRouteAction a : receipts.values()) if (a.getId().equals(i.getArgument(0))) a.setResponseJson(i.getArgument(1));
            return 1;
        });
        when(mapper.updateStateCas(any())).thenReturn(1);
        service.state(7L, 10L, 0L);

        // 低血量 + 一份口粮:USE 必然有效果
        ObjectNode state = (ObjectNode) json.readTree(session.getStateJson());
        state.with("variables").put("sys.hp", 2);
        state.with("themeRuntime").with("inventory").put("food", 1);
        session.setStateJson(state.toString());
        clearInvocations(mapper);
    }

    private int food() throws Exception {
        return json.readTree(session.getStateJson()).path("themeRuntime").path("inventory").path("food").asInt(0);
    }

    private int hp() throws Exception {
        return json.readTree(session.getStateJson()).path("variables").path("sys.hp").asInt();
    }

    @Test
    void retryWithSameActionIdDeductsOnlyOnce() throws Exception {
        int v = session.getVersion();
        Map<String, Object> first = service.themeAction(7L, 99L, v, "use-food-1", "USE", "food");
        assertEquals(0, food());
        int hpAfter = hp();
        assertEquals(5, hpAfter, "口粮 +3");

        // 网络重试:同 actionId、同载荷(客户端还拿着旧 version)
        Map<String, Object> retry = service.themeAction(7L, 99L, v, "use-food-1", "USE", "food");
        assertEquals(json.readTree(json.writeValueAsString(first)), json.readTree(json.writeValueAsString(retry)), "重试拿到同一份回执");
        assertEquals(0, food(), "没有二次扣减");
        assertEquals(hpAfter, hp(), "没有二次加血");
        verify(mapper, times(1)).updateStateCas(any());
        verify(mapper, times(1)).insertAction(any());
        assertEquals(1, receipts.size());
    }

    @Test
    void useWithZeroStockFailsCleanly() throws Exception {
        ObjectNode state = (ObjectNode) json.readTree(session.getStateJson());
        ((ObjectNode) state.with("themeRuntime").with("inventory")).remove("food");
        session.setStateJson(state.toString());
        String before = session.getStateJson();
        int v = session.getVersion();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.themeAction(7L, 99L, v, "use-empty", "USE", "food"));
        assertEquals("没有可用物品", e.getMessage());
        verify(mapper, never()).updateStateCas(any());
        verify(mapper, never()).insertAction(any());
        assertEquals(before, session.getStateJson());
        assertEquals(v, session.getVersion().intValue());
        assertEquals(0, receipts.size());
    }

    @Test
    void foreignMemberIsRefusedWith403AndNoWrite() throws Exception {
        String before = session.getStateJson();
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.themeAction(8L, 99L, session.getVersion(), "steal-food", "USE", "food"));
        assertEquals(Integer.valueOf(403), e.getCode());
        assertEquals("本局不存在或无权操作", e.getMessage());
        verify(mapper, never()).updateStateCas(any());
        verify(mapper, never()).insertAction(any());
        assertEquals(before, session.getStateJson());
        assertEquals(1, food());
    }

    @Test
    void sameActionIdWithDifferentPayloadIs409() throws Exception {
        int v = session.getVersion();
        service.themeAction(7L, 99L, v, "dup-key", "USE", "food");
        String after = session.getStateJson();
        clearInvocations(mapper);

        ServiceException otherItem = assertThrows(ServiceException.class,
                () -> service.themeAction(7L, 99L, v, "dup-key", "USE", "water"));
        assertEquals(Integer.valueOf(409), otherItem.getCode());
        assertEquals("动作标识已用于其他操作", otherItem.getMessage());
        ServiceException otherOp = assertThrows(ServiceException.class,
                () -> service.themeAction(7L, 99L, v, "dup-key", "DISCARD", "food"));
        assertEquals(Integer.valueOf(409), otherOp.getCode());
        assertEquals("动作标识已用于其他操作", otherOp.getMessage());

        verify(mapper, never()).updateStateCas(any());
        verify(mapper, never()).insertAction(any());
        assertEquals(after, session.getStateJson());
        assertEquals(1, receipts.size());
        assertEquals("USE", receipts.get("theme:dup-key").getOutcomeCode(), "原回执仍是第一次 USE");
        assertEquals(0, food());
    }
}
