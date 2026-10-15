package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.INodeMerchantOverrideService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayJourneyReadServiceImpl;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.support.JourneyStoryValidator;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mapper substitutes with real validator, frozen-story parsing and service evaluation. NOT_RUN. */
class E03_18Test {
    private final ObjectMapper json = new ObjectMapper();
    private final JourneyStoryValidator validator = new JourneyStoryValidator();
    private PlayJourneyStoryServiceImpl stories;
    private PlayJourneyReadServiceImpl reads;
    private TopicRouteSession session;
    private CmsTopic topic;
    private TopicRouteEngine.State state;

    @BeforeEach
    void setup() {
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        topic = new CmsTopic(); topic.setId(700L); topic.setDelFlag(0);
        when(topics.selectCmsTopicById(700L)).thenReturn(topic);
        TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
        session = new TopicRouteSession();
        session.setId(99L); session.setMemberId(97L); session.setTopicId(700L);
        session.setActivityId(0L); session.setPlayInstanceId("default");
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        session.setStateJson("{\"status\":\"COMPLETED\",\"variables\":{\"clue.page\":1},\"tags\":[]}");
        when(sessions.selectCurrentByOwner(0L, 700L, 97L)).thenReturn(session);
        state = new TopicRouteEngine.State();
        state.getVariables().put("clue.page", Integer.valueOf(1));
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        ThemeStateKeyService keys = mock(ThemeStateKeyService.class);
        when(keys.listByTopic(700L)).thenReturn(Collections.emptyList());
        NpcProfileService npcs = mock(NpcProfileService.class);
        stories = new PlayJourneyStoryServiceImpl(topics, mock(CmsTopicChapterMapper.class), nodes,
                sessions, keys, npcs);
        reads = new PlayJourneyReadServiceImpl(topics, nodes, mock(CmsMemberTemplateMapper.class), sessions,
                mock(PlayerNodeProgressMapper.class), mock(SmsCouponHistoryMapper.class),
                mock(PlayJourneyReadMapper.class), npcs, mock(INodeMerchantOverrideService.class), keys,
                mock(ISmsCouponService.class), mock(IXpBudgetService.class));
    }

    @Test
    void acceptedWhitespaceRemainsStoredWhileFrozenReadersAndBothFreezeOverloadsAgree() throws Exception {
        String raw = story(",\"optionalClues\":[\" clue.page \"]");
        String checked = validator.validate(raw, JourneyStoryValidator.Context.none());
        assertEquals(raw, checked, "Validation preserves author JSON, including internal whitespace/extensions");
        freeze(checked);
        topic.setJourneyStoryJson(story(",\"optionalClues\":[\"clue.live\"]"));
        assertProgress(reads.casebook(97L, 700L, 0L), 1, 1);
        assertProgress(stories.ending(97L, 700L, 0L), 1, 1);
        assertProgress(stories.freezeEnding(700L, checked, state), 1, 1);
        assertProgress(stories.freezeEnding(700L, checked, state, session), 1, 1);
        assertEquals(checked, json.readTree(session.getConfigSnapshotJson()).path("journeyStory").asText());
        assertEquals(" clue.page ", JourneyStory.parse(checked).getEnding().getOptionalClues().get(0));
    }

    @Test
    void absentNullAndEmptyListsKeepZeroProgressAcrossReadersAndFreezeOverloads() throws Exception {
        for (String field : new String[]{"", ",\"optionalClues\":null", ",\"optionalClues\":[]"}) {
            String raw = validator.validate(story(field), JourneyStoryValidator.Context.none());
            freeze(raw);
            assertProgress(reads.casebook(97L, 700L, 0L), 0, 0);
            assertProgress(stories.ending(97L, 700L, 0L), 0, 0);
            assertProgress(stories.freezeEnding(700L, raw, state), 0, 0);
            assertProgress(stories.freezeEnding(700L, raw, state, session), 0, 0);
        }
    }

    @Test
    void blankNullItemAndTrimEquivalentDuplicatesRemainRejected() {
        for (String values : new String[]{"[\"   \"]", "[null]", "[\"clue.page\",\" clue.page \"]"}) {
            String raw = story(",\"optionalClues\":" + values);
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validate(raw, JourneyStoryValidator.Context.none()));
            assertThrows(IllegalArgumentException.class, () -> stories.freezeEnding(700L, raw, state));
            assertThrows(IllegalArgumentException.class, () -> stories.freezeEnding(700L, raw, state, session));
        }
    }

    @Test
    void archivedResultStillWinsWithoutRecomputingProgress() throws Exception {
        freeze(story(",\"optionalClues\":[\" clue.page \"]"));
        session.setStateJson("{\"themeRuntime\":{\"result\":{\"endingId\":\"archived\",\"found\":0,\"total\":1}}}");
        Map<String, Object> view = stories.ending(97L, 700L, 0L);
        assertEquals("archived", view.get("code"));
        assertProgress(view, 0, 1);
    }

    private void freeze(String raw) throws Exception {
        session.setConfigSnapshotJson(json.createObjectNode().put("journeyStory", raw).toString());
    }

    private static String story(String field) {
        return "{\"schemaVersion\":1,\"extension\":{\"keep\":true},\"ending\":{\"endings\":["
                + "{\"code\":\"done\",\"title\":\"Done\",\"fallback\":true}]" + field + "}}";
    }

    private static void assertProgress(Map<String, Object> view, int found, int total) {
        assertEquals(found, view.get("found"));
        assertEquals(total, view.get("total"));
    }
}
