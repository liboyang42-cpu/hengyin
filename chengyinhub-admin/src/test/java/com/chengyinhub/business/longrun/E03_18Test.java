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
    private ThemeStateKeyService keys;
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
        keys = mock(ThemeStateKeyService.class);
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

    @Test
    void paddedRelationKeyKeepsRegisteredNpcMappingAndTriStateLinesAcrossReaders() throws Exception {
        String raw = story(",\"epilogues\":[{\"npcId\":7,\"relationKey\":\" relation.qiao \","
                + "\"ally\":\"ally line\",\"neutral\":\"neutral line\",\"guarded\":\"guarded line\"}]");
        assertEquals(raw, validator.validate(raw,
                JourneyStoryValidator.Context.of(Collections.emptySet(), Collections.singleton(7L))));
        assertEquals(Collections.singleton("relation.qiao"),
                com.chengyinhub.business.util.StateKeyRefs.collectJourneyStory(raw));
        assertEquals(Collections.singleton("relation.qiao"),
                com.chengyinhub.business.util.StateKeyRefs.collectJourneyStoryRelationKeys(raw));
        ThemeStateKey registered = new ThemeStateKey();
        registered.setStateKey("relation.qiao"); registered.setKeyType(ThemeStateKey.TYPE_RELATION);
        when(keys.listByTopic(700L)).thenReturn(Collections.singletonList(registered));
        freeze(raw);
        Integer[] values = {2, -2, 0, null};
        String[] expected = {"ally", "guarded", "neutral", "neutral"};
        for (int i = 0; i < values.length; i++) {
            state.getVariables().clear();
            if (values[i] != null) state.getVariables().put("relation.qiao", values[i]);
            session.setStateJson(json.writeValueAsString(state));
            Map<?, ?> relation = (Map<?, ?>) ((java.util.List<?>) reads.casebook(97L, 700L, 0L)
                    .get("relations")).get(0);
            assertEquals(7L, relation.get("npcId"));
            assertEquals(expected[i], relation.get("state"));
            for (Map<String, Object> view : java.util.Arrays.asList(
                    stories.ending(97L, 700L, 0L), stories.freezeEnding(700L, raw, state),
                    stories.freezeEnding(700L, raw, state, session))) {
                Map<?, ?> epilogue = (Map<?, ?>) ((java.util.List<?>) view.get("epilogues")).get(0);
                assertEquals(7L, epilogue.get("npcId"));
                assertEquals(expected[i], epilogue.get("state"));
                assertEquals(expected[i] + " line", epilogue.get("line"));
            }
        }
        assertEquals(" relation.qiao ", JourneyStory.parse(raw).getEnding().getEpilogues().get(0).getRelationKey());
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
