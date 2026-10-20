package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.INodeMerchantOverrideService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayJourneyReadServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Ordinary service regression, not a real-MySQL claim. All methods NOT_RUN. */
class E03_14Test {
    private CmsTopic topic;
    private PlayJourneyReadMapper read;
    private PlayJourneyReadServiceImpl service;

    @BeforeEach
    void setup() {
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        topic = new CmsTopic();
        topic.setId(700L);
        when(topics.selectCmsTopicById(700L)).thenReturn(topic);
        TopicRouteSession session = new TopicRouteSession();
        session.setId(99L);
        session.setMemberId(97L);
        session.setTopicId(700L);
        session.setActivityId(0L);
        session.setStateJson("{\"status\":\"ACTIVE\",\"variables\":{},\"tags\":[]}");
        session.setConfigSnapshotJson("{\"journeyRules\":" + rules(10, 3) + "}");
        TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
        when(sessions.selectByOwner(0L, 700L, 97L, "default")).thenReturn(session);
        read = mock(PlayJourneyReadMapper.class);
        ThemeStateKeyService keys = mock(ThemeStateKeyService.class);
        when(keys.listByTopic(700L)).thenReturn(Collections.emptyList());
        service = new PlayJourneyReadServiceImpl(topics, mock(CmsTopicNodeMapper.class),
                mock(CmsMemberTemplateMapper.class), sessions, mock(PlayerNodeProgressMapper.class),
                mock(SmsCouponHistoryMapper.class), read, mock(NpcProfileService.class),
                mock(INodeMerchantOverrideService.class), keys, mock(ISmsCouponService.class), mock(IXpBudgetService.class));
    }

    @Test
    void firstReceiptDeltasUseFrozenInitialValuesAfterAuthorEditsLiveRules() {
        topic.setJourneyRulesJson(rules(20, 5));
        when(read.selectActionsBySession(99L)).thenReturn(Collections.singletonList(
                action("check:21:first:settle", "CHECK_SETTLE", 8, 2)));
        List<Map<String, Object>> events = events();
        assertEquals(1, events.size());
        assertEquals(-2, events.get(0).get("hpDelta"));
        assertEquals(-1, events.get(0).get("luckDelta"));
        assertEquals("check", events.get(0).get("kind"));
    }

    @Test
    void liveDisabledRulesDoNotEraseFrozenSessionPaidMistake() {
        topic.setJourneyRulesJson("{\"schemaVersion\":1}");
        when(read.selectActionsBySession(99L)).thenReturn(Collections.singletonList(
                action("mistake:21:2", "MISTAKE", 9, 3)));
        List<Map<String, Object>> events = events();
        assertEquals(1, events.size(), "live configuration cannot hide this session's nonzero cost");
        assertEquals("mistake", events.get(0).get("kind"));
        assertEquals(-1, events.get(0).get("hpDelta"));
        assertEquals(0, events.get(0).get("luckDelta"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events() {
        return (List<Map<String, Object>>) service.casebook(97L, 700L, 0L).get("log");
    }

    private static TopicRouteAction action(String id, String outcome, int hp, int luck) {
        TopicRouteAction action = new TopicRouteAction();
        action.setSessionId(99L);
        action.setMemberId(97L);
        action.setNodeId(21L);
        action.setActionId(id);
        action.setOutcomeCode(outcome);
        action.setResponseJson("{\"hp\":" + hp + ",\"luck\":" + luck + "}");
        return action;
    }

    private static String rules(int hp, int luck) {
        return "{\"schemaVersion\":1,\"checkEnabled\":true,\"hp\":{\"enabled\":true,\"max\":20,\"init\":" + hp
                + "},\"luck\":{\"enabled\":true,\"max\":5,\"init\":" + luck + "}}";
    }
}
