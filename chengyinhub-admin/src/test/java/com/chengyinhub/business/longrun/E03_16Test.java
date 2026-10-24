package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.NodeMerchantOverride;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.INodeMerchantOverrideService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayJourneyReadServiceImpl;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real current read-service consumers with distinct default/replay fixtures. All methods NOT_RUN. */
class E03_16Test {
    private TopicRouteSessionMapper sessions;
    private TopicRouteSession original;
    private TopicRouteSession current;
    private PlayJourneyStoryServiceImpl stories;
    private PlayJourneyReadServiceImpl reads;

    @BeforeEach
    void setup() {
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic(); topic.setId(700L);
        when(topics.selectCmsTopicById(700L)).thenReturn(topic);
        sessions = mock(TopicRouteSessionMapper.class);
        original = run(99L, "default", "old", 0);
        original.setStatus(TopicRouteSession.STATUS_COMPLETED);
        original.setVersion(1);
        current = run(100L, "replay:99", "current", 1);
        when(sessions.selectByOwner(0L, 700L, 97L, "default")).thenReturn(original);
        when(sessions.selectCurrentByOwner(0L, 700L, 97L)).thenReturn(current);
        when(sessions.selectMemberTopicSessions(97L, 700L)).thenReturn(Collections.singletonList(original));
        CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(77L); chapter.setTopicId(700L);
        when(chapters.selectCmsTopicChapterById(77L)).thenReturn(chapter);
        CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        CmsTopicNode node = new CmsTopicNode(); node.setId(21L); node.setTopicId(700L); node.setChapterId(77L);
        node.setSortId(1L); node.setName("station");
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Collections.singletonList(node));
        ThemeStateKeyService keys = mock(ThemeStateKeyService.class);
        when(keys.listByTopic(700L)).thenReturn(Collections.emptyList());
        NpcProfileService npcs = mock(NpcProfileService.class);
        stories = new PlayJourneyStoryServiceImpl(topics, chapters, nodes, sessions, keys, npcs);
        INodeMerchantOverrideService overrides = mock(INodeMerchantOverrideService.class);
        when(overrides.forNode(21L)).thenReturn(NodeMerchantOverride.empty());
        reads = new PlayJourneyReadServiceImpl(topics, nodes, mock(CmsMemberTemplateMapper.class), sessions,
                mock(PlayerNodeProgressMapper.class), mock(SmsCouponHistoryMapper.class), mock(PlayJourneyReadMapper.class),
                npcs, overrides, keys, mock(ISmsCouponService.class), mock(IXpBudgetService.class));
    }

    @Test
    void endingDoesNotReplayCompletedDefaultWhileCurrentRunIsUnfinished() {
        ServiceException failure = assertThrows(ServiceException.class, () -> stories.ending(97L, 700L, 0L));
        assertEquals("本局尚未完成", failure.getMessage());
        current.setStatus(TopicRouteSession.STATUS_COMPLETED);
        Map<String, Object> ending = stories.ending(97L, 700L, 0L);
        assertEquals("current", ending.get("code"));
        assertEquals("current ending", ending.get("title"));
    }

    @Test
    void stageEndUsesCurrentFrozenStageAndStateVersion() {
        Map<String, Object> stage = stories.stageEnd(97L, 700L, 0L, 77L);
        assertEquals("[current stage]", stage.get("changed").toString());
        assertEquals("current open", stage.get("open"));
        assertEquals(7, stage.get("stateVersion"));
    }

    @Test
    void casebookAndBackpackUseCurrentFactsAndFrozenDeliveryWithinResolvedScope() {
        // Omitted activity resolves the one original scope, then reads that scope's current run.
        Map<String, Object> casebook = reads.casebook(97L, 700L, null);
        assertEquals(1, casebook.get("found"));
        assertEquals(1, casebook.get("total"));
        Map<String, Object> backpack = reads.backpack(97L, 700L, 0L);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) backpack.get("nodes")).get(0);
        assertEquals("current delivery", row.get("reward"));
        assertEquals("pending", row.get("status"));
    }

    @Test
    void allCurrentReadersKeepOmittedActivityAmbiguityRejection() {
        TopicRouteSession anotherScope = run(101L, "default", "other", 1);
        anotherScope.setActivityId(2L);
        when(sessions.selectMemberTopicSessions(97L, 700L)).thenReturn(Arrays.asList(original, anotherScope));
        assertThrows(IllegalArgumentException.class, () -> stories.ending(97L, 700L, null));
        assertThrows(IllegalArgumentException.class, () -> stories.stageEnd(97L, 700L, null, 77L));
        assertThrows(IllegalArgumentException.class, () -> reads.casebook(97L, 700L, null));
        assertThrows(IllegalArgumentException.class, () -> reads.backpack(97L, 700L, null));
        verify(sessions, never()).selectCurrentByOwner(anyLong(), anyLong(), anyLong());
    }

    private static TopicRouteSession run(long id, String instance, String label, int clue) {
        TopicRouteSession run = new TopicRouteSession();
        run.setId(id); run.setMemberId(97L); run.setTopicId(700L); run.setActivityId(0L);
        run.setPlayInstanceId(instance); run.setStatus(TopicRouteSession.STATUS_RUNNING); run.setVersion(7);
        run.setStateJson("{\"status\":\"ACTIVE\",\"completedNodeIds\":[21],\"variables\":{\"clue.page\":" + clue + "},\"tags\":[]}");
        String story = "{\"schemaVersion\":1,\"stages\":[{\"chapterId\":77,\"end\":{\"changed\":[{\"text\":\"" + label
                + " stage\"}],\"open\":\"" + label + " open\"}}],\"ending\":{\"endings\":[{\"code\":\"" + label
                + "\",\"title\":\"" + label + " ending\",\"fallback\":true}],\"optionalClues\":[\"clue.page\"]}}";
        // Object form is supported by the same frozen-story helper; no new DSL or session source.
        run.setConfigSnapshotJson("{\"journeyStory\":" + story + ",\"nodeJourney\":{\"21\":{\"deliver\":{\"reward\":\""
                + label + " delivery\"}}}}");
        return run;
    }
}
