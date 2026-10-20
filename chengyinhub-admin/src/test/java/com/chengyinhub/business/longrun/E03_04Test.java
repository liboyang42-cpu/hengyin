package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.business.service.impl.PlayJourneyStoryServiceImpl;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real stageEnd consumer with mapper query boundaries substituted. All methods NOT_RUN. */
class E03_04Test {
    private static final String STORY = "{\"schemaVersion\":1,\"stages\":[{\"chapterId\":77,"
            + "\"end\":{\"changed\":[{\"text\":\"frozen changed\"}],\"open\":\"frozen open\"}}]}";
    private CmsTopicNodeMapper nodes;
    private CmsTopicChapterMapper chapters;
    private TopicRouteSession session;
    private PlayJourneyStoryServiceImpl service;

    @BeforeEach
    void setup() {
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic(); topic.setId(700L); topic.setJourneyStoryJson(STORY);
        when(topics.selectCmsTopicById(700L)).thenReturn(topic);
        chapters = mock(CmsTopicChapterMapper.class);
        CmsTopicChapter chapter = new CmsTopicChapter(); chapter.setId(77L); chapter.setTopicId(700L);
        when(chapters.selectCmsTopicChapterById(77L)).thenReturn(chapter);
        nodes = mock(CmsTopicNodeMapper.class);
        TopicRouteSessionMapper sessions = mock(TopicRouteSessionMapper.class);
        session = new TopicRouteSession(); session.setId(99L); session.setMemberId(97L);
        session.setTopicId(700L); session.setActivityId(0L); session.setPlayInstanceId("default");
        session.setStatus(TopicRouteSession.STATUS_RUNNING); session.setVersion(3);
        completed("21");
        when(sessions.selectCurrentByOwner(0L, 700L, 97L)).thenReturn(session);
        ThemeStateKeyService keys = mock(ThemeStateKeyService.class);
        when(keys.listByTopic(700L)).thenReturn(Collections.emptyList());
        service = new PlayJourneyStoryServiceImpl(topics, chapters, nodes, sessions, keys,
                mock(NpcProfileService.class));
    }

    @Test
    void newlyEnabledLiveNodeDoesNotExtendValidFrozenStageGate() {
        snapshot("[{\"chapterId\":77,\"nodeIds\":[21]}]");
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(node(21L), node(22L)));
        Map<String, Object> view = service.stageEnd(97L, 700L, 0L, 77L);
        assertEquals(Collections.singletonList("frozen changed"), view.get("changed"));
        assertEquals("frozen open", view.get("open"));
        assertEquals(3, view.get("stateVersion"));
    }

    @Test
    void removingLiveNodeDoesNotWaiveUnfinishedFrozenNode() {
        snapshot("[{\"chapterId\":77,\"nodeIds\":[21,22]}]");
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Collections.singletonList(node(21L)));
        assertUnfinished();
        completed("21,22");
        assertEquals("frozen open", service.stageEnd(97L, 700L, 0L, 77L).get("open"));
    }

    @Test
    void allOtherSnapshotShapesRetainTheOriginalLiveGate() {
        String[] shapes = {null, "null", "{}", "[]", "[{}]", "[{\"chapterId\":78,\"nodeIds\":[21]}]",
                "[{\"chapterId\":77}]", "[{\"chapterId\":77,\"nodeIds\":null}]",
                "[{\"chapterId\":77,\"nodeIds\":{}}]", "[{\"chapterId\":77,\"nodeIds\":[]}]",
                "[{\"chapterId\":77,\"nodeIds\":[21]},{\"chapterId\":77,\"nodeIds\":[21]}]"};
        for (String shape : shapes) {
            snapshot(shape);
            assertLiveGateBothWays();
        }
        for (String invalidId : new String[]{"null", "0", "-1", "1.5", "\"21\"", "true", "9223372036854775808"}) {
            snapshot("[{\"chapterId\":77,\"nodeIds\":[21," + invalidId + "]}]");
            assertLiveGateBothWays();
        }
        for (String invalidChapter : new String[]{"{}", "{\"chapterId\":\"77\",\"nodeIds\":[21]}",
                "{\"chapterId\":9223372036854775808,\"nodeIds\":[21]}",
                "{\"chapterId\":0,\"nodeIds\":[21]}"}) {
            snapshot("[{\"chapterId\":77,\"nodeIds\":[21]}," + invalidChapter + "]");
            assertLiveGateBothWays();
        }
        session.setConfigSnapshotJson("not JSON");
        assertLiveGateBothWays();
    }

    @Test
    void deletedOrReassignedLiveChapterRemainsRejectedDespiteValidFrozenEvidence() {
        snapshot("[{\"chapterId\":77,\"nodeIds\":[21]}]");
        when(chapters.selectCmsTopicChapterById(77L)).thenReturn(null);
        assertEquals("章节不存在", assertThrows(ServiceException.class,
                () -> service.stageEnd(97L, 700L, 0L, 77L)).getMessage());
        CmsTopicChapter foreign = new CmsTopicChapter(); foreign.setId(77L); foreign.setTopicId(701L);
        when(chapters.selectCmsTopicChapterById(77L)).thenReturn(foreign);
        assertEquals("章节不存在", assertThrows(ServiceException.class,
                () -> service.stageEnd(97L, 700L, 0L, 77L)).getMessage());
    }

    private void assertLiveGateBothWays() {
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Collections.singletonList(node(22L)));
        assertUnfinished();
        when(nodes.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Collections.emptyList());
        assertEquals("frozen open", service.stageEnd(97L, 700L, 0L, 77L).get("open"));
    }

    private void assertUnfinished() {
        assertEquals("本章节还有站点未完成", assertThrows(ServiceException.class,
                () -> service.stageEnd(97L, 700L, 0L, 77L)).getMessage());
    }

    private void completed(String ids) {
        session.setStateJson("{\"status\":\"ACTIVE\",\"completedNodeIds\":[" + ids
                + "],\"variables\":{},\"tags\":[]}");
    }

    private void snapshot(String thoughtChapters) {
        // Object-form story is an existing snapshot format; missing collection remains legacy.
        session.setConfigSnapshotJson("{\"journeyStory\":" + STORY
                + (thoughtChapters == null ? "" : ",\"thoughtChapters\":" + thoughtChapters) + "}");
    }

    private static CmsTopicNode node(long id) {
        CmsTopicNode node = new CmsTopicNode(); node.setId(id); node.setTopicId(700L);
        node.setChapterId(77L); node.setStatus(1L); return node;
    }
}
