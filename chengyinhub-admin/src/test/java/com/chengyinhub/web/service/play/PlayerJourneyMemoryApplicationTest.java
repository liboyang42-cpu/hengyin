package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import org.mockito.ArgumentCaptor;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.vo.PlayerJourneyMemoryVO;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P8 私人回忆：只出现本人事实、同场他人照片不泄露、未参与不可读、无私照分享同意事实时能力不可用。
 */
class PlayerJourneyMemoryApplicationTest {

    private static final Long VIEWER = 11L;
    private static final Long OTHER = 22L;
    private static final Long TOPIC = 7L;
    private static final Long ACTIVITY = 900L;

    private PlayerJourneyMemoryApplication application;
    private ICmsTopicService topicService;
    private ICmsActivityService activityService;
    private ICmsTopicNodeService nodeService;
    private IPlayerNodeProgressService progressService;
    private ICmsRegistrationService registrationService;
    private PlayCompletionFacts completionFacts;
    private TopicRouteSessionMapper routeMapper;

    @BeforeEach
    void setUp() {
        routeMapper = mock(TopicRouteSessionMapper.class);
        topicService = mock(ICmsTopicService.class);
        activityService = mock(ICmsActivityService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        registrationService = mock(ICmsRegistrationService.class);
        completionFacts = mock(PlayCompletionFacts.class);
        application = new PlayerJourneyMemoryApplication();
        ReflectionTestUtils.setField(application, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(application, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(application, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(application, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(application, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(application, "playCompletionFacts", completionFacts);
        ReflectionTestUtils.setField(application, "topicRouteSessionMapper", routeMapper);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setStatus(1);
        topic.setDelFlag(0);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(1L);
        node.setTopicId(TOPIC);
        node.setName("一号站");
        node.setFragmentText("碎片一");
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(List.of(node));
    }

    @Test
    void onlyOwnMomentsAndOwnPhotosAreReturned() {
        PlayerNodeProgress mine = progress(VIEWER, 1L, "https://img.example/mine.jpg");
        PlayerNodeProgress teammate = progress(OTHER, 2L, "https://img.example/teammate.jpg");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(mine, teammate));
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(List.of(
                registration(VIEWER), registration(OTHER)));

        PlayerJourneyMemoryVO vo = (PlayerJourneyMemoryVO) application.memory(VIEWER, ACTIVITY, TOPIC)
                .get(AjaxResult.DATA_TAG);

        assertEquals(1, vo.getMoments().size(), "只投影本人完成事实");
        assertEquals(Long.valueOf(1L), vo.getMoments().get(0).getNodeId());
        assertEquals("https://img.example/mine.jpg", vo.getMoments().get(0).getPicUrl());
        assertNull(vo.getMoments().get(0).getFragmentText(), "旧局不读实时节点碎片，避免改稿后泄露");
        assertEquals(2, vo.getParticipantCount().intValue(), "只数人数，不返回队友身份");
        assertEquals(PlayerJourneyMemoryVO.IDENTITY_SELF_ONLY, vo.getIdentityPolicy());
        assertEquals(PlayerJourneyMemoryVO.PHOTO_SHARE_UNAVAILABLE, vo.getPhotoShareCapability());
        assertEquals(PlayerJourneyMemoryVO.PHOTO_SHARE_NO_CONSENT_FACT, vo.getPhotoShareReason());
        assertTrue(vo.getReadOnly());
        for (PlayerJourneyMemoryVO.Moment moment : vo.getMoments()) {
            assertEquals(Long.valueOf(1L), moment.getNodeId());
        }
    }

    @Test
    void nonParticipantDeniedBeforeNodeRead() {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of());
        when(completionFacts.isRegistered(VIEWER, ACTIVITY)).thenReturn(false);

        assertFalse(application.memory(VIEWER, ACTIVITY, TOPIC).isSuccess());
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void selfPlayUsesTopicScopeAndReportsUnavailablePhotoShare() {
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of());
        when(completionFacts.hasSelfPlayRegistration(VIEWER, TOPIC)).thenReturn(true);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(List.of(registration(VIEWER)));

        PlayerJourneyMemoryVO vo = (PlayerJourneyMemoryVO) application.memory(VIEWER, null, TOPIC)
                .get(AjaxResult.DATA_TAG);

        assertEquals(Long.valueOf(0L), vo.getActivityId());
        assertEquals(TOPIC, vo.getTopicId());
        assertEquals(PlayerJourneyMemoryApplication.VIEWER_SELF_PLAY, vo.getViewerRole());
        assertTrue(vo.getMoments().isEmpty());
        assertEquals(1, vo.getParticipantCount().intValue());
        assertEquals(PlayerJourneyMemoryVO.PHOTO_SHARE_UNAVAILABLE, vo.getPhotoShareCapability());
    }

    @Test
    void uncompletedRowsAreNotMemories() {
        PlayerNodeProgress pending = progress(VIEWER, 1L, null);
        pending.setStatus(0);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(pending));
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(List.of(registration(VIEWER)));

        PlayerJourneyMemoryVO vo = (PlayerJourneyMemoryVO) application.memory(VIEWER, ACTIVITY, TOPIC)
                .get(AjaxResult.DATA_TAG);

        assertTrue(vo.getMoments().isEmpty(), "status!=1 不是完成事实");
    }

    @Test
    void topicActivityMismatchRejected() {
        assertFalse(application.memory(VIEWER, ACTIVITY, 55L).isSuccess());
    }
    @Test
    void historyBlockedTopicDeniesBeforeProgressRead() {
        when(completionFacts.topicHistoryBlocked(any())).thenReturn(true);

        assertFalse(application.memory(VIEWER, ACTIVITY, TOPIC).isSuccess());
        verify(progressService, never()).selectPlayerNodeProgressList(any());
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }


    private static PlayerNodeProgress progress(Long memberId, Long nodeId, String picUrl) {
        PlayerNodeProgress row = new PlayerNodeProgress();
        row.setMemberId(memberId);
        row.setActivityId(ACTIVITY);
        row.setTopicId(TOPIC);
        row.setNodeId(nodeId);
        row.setStatus(1);
        row.setPicUrl(picUrl);
        row.setCheckinTime(new Date(1_700_000_000_000L + nodeId * 1000L));
        return row;
    }

    private static CmsRegistration registration(Long memberId) {
        CmsRegistration row = new CmsRegistration();
        row.setMemberId(memberId);
        row.setOwnerType(2L);
        row.setOwnerId(ACTIVITY);
        row.setPaymentStatus(2);
        row.setRegistrationStatus(2);
        return row;
    }

    @Test
    void runScopeUsesFrozenContentAndPlayInstance() {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(88L);
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        session.setTopicId(TOPIC);
        session.setActivityId(ACTIVITY);
        session.setPlayInstanceId("run-b");
        session.setConfigSnapshotJson("{\"routeStations\":[{\"nodeId\":1,\"nodeName\":\"冻结站\"}],"
                + "\"nodeAnswers\":{\"1\":{\"nodePresentation\":{\"fragmentText\":\"冻结碎片\"}}}}");
        when(routeMapper.selectAccessibleById(88L, VIEWER)).thenReturn(session);
        PlayerNodeProgress current = progress(VIEWER, 1L, null);
        current.setPlayInstanceId("run-b");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(current));
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(List.of(registration(VIEWER)));

        PlayerJourneyMemoryVO vo = (PlayerJourneyMemoryVO) application.memory(VIEWER, ACTIVITY, TOPIC, 88L)
                .get(AjaxResult.DATA_TAG);

        assertEquals(PlayerJourneyMemoryVO.SCOPE_RUN, vo.getScopeKind());
        assertEquals(Long.valueOf(88L), vo.getRunId());
        assertEquals("ACTIVITY", vo.getRunKind());
        assertEquals("run-b", vo.getPlayInstanceId());
        assertEquals("冻结站", vo.getMoments().get(0).getName(), "节点名取本局冻结快照");
        assertEquals("冻结碎片", vo.getMoments().get(0).getFragmentText(), "碎片取本局冻结内容");
        ArgumentCaptor<PlayerNodeProgress> probe = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService).selectPlayerNodeProgressList(probe.capture());
        assertEquals("run-b", probe.getValue().getPlayInstanceId(), "读取绑定到本局既有 play_instance_id");
    }

    @Test
    void namedHistoricalRunKeepsItsOwnFrozenMoments() {
        // 最新一局是另一局；点名旧的 replay 局时必须读它自己的冻结内容与实例行。
        when(routeMapper.selectCurrentByOwner(ACTIVITY, TOPIC, VIEWER)).thenReturn(
                session(88L, "default", "{\"routeStations\":[{\"nodeId\":2,\"nodeName\":\"新局站\"}]}"));
        when(routeMapper.selectAccessibleById(77L, VIEWER)).thenReturn(
                session(77L, "replay:77", "{\"routeStations\":[{\"nodeId\":1,\"nodeName\":\"旧局站\"}],"
                        + "\"nodeAnswers\":{\"1\":{\"nodePresentation\":{\"fragmentText\":\"旧局碎片\"}}}}"));
        PlayerNodeProgress oldRun = progress(VIEWER, 1L, null);
        oldRun.setPlayInstanceId("replay:77");
        PlayerNodeProgress newRun = progress(VIEWER, 2L, "https://img.example/new.jpg");
        newRun.setPlayInstanceId("default");
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(oldRun, newRun));
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(List.of(registration(VIEWER)));

        PlayerJourneyMemoryVO vo = (PlayerJourneyMemoryVO) application.memory(VIEWER, ACTIVITY, TOPIC, 77L)
                .get(AjaxResult.DATA_TAG);

        assertEquals(PlayerJourneyMemoryVO.SCOPE_RUN, vo.getScopeKind());
        assertEquals(PlayerJourneyMemoryVO.PROGRESS_RUN_INSTANCE, vo.getProgressScope());
        assertEquals(Long.valueOf(77L), vo.getRunId());
        assertEquals("replay:77", vo.getPlayInstanceId());
        assertEquals(1, vo.getMoments().size(), "只投影该局本人的节点，不混最新局");
        assertEquals("旧局站", vo.getMoments().get(0).getName());
        assertEquals("旧局碎片", vo.getMoments().get(0).getFragmentText());
        ArgumentCaptor<PlayerNodeProgress> probe = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService).selectPlayerNodeProgressList(probe.capture());
        assertEquals("replay:77", probe.getValue().getPlayInstanceId());
    }

    @Test
    void namedRunOfAnotherMemberIsRejectedBeforeAnyRead() {
        when(routeMapper.selectAccessibleById(999L, VIEWER)).thenReturn(null);

        AjaxResult result = application.memory(VIEWER, ACTIVITY, TOPIC, 999L);

        assertFalse(result.isSuccess());
        assertEquals(403, result.get(AjaxResult.CODE_TAG));
        assertEquals("RUN_NOT_FOUND", result.get("reasonCode"));
        verify(progressService, never()).selectPlayerNodeProgressList(any());
        verify(nodeService, never()).selectCmsTopicNodeList(any());
    }

    @Test
    void namedRunOutsideTheRequestedScopeIsRejected() {
        TopicRouteSession foreign = session(55L, "default", "{}");
        foreign.setActivityId(ACTIVITY + 1);
        when(routeMapper.selectAccessibleById(55L, VIEWER)).thenReturn(foreign);

        AjaxResult result = application.memory(VIEWER, ACTIVITY, TOPIC, 55L);

        assertFalse(result.isSuccess());
        assertEquals("RUN_SCOPE_MISMATCH", result.get("reasonCode"));
        verify(progressService, never()).selectPlayerNodeProgressList(any());
    }

    @Test
    void namedRunWithoutPlayInstanceIsUnattributedNotTopicHistory() {
        when(routeMapper.selectAccessibleById(66L, VIEWER)).thenReturn(
                session(66L, "", "{\"routeStations\":[{\"nodeId\":1,\"nodeName\":\"冻结站\"}]}"));

        PlayerJourneyMemoryVO vo = (PlayerJourneyMemoryVO) application.memory(VIEWER, ACTIVITY, TOPIC, 66L)
                .get(AjaxResult.DATA_TAG);

        assertEquals(PlayerJourneyMemoryVO.PROGRESS_UNATTRIBUTED, vo.getProgressScope());
        assertEquals(Long.valueOf(66L), vo.getRunId());
        assertTrue(vo.getMoments().isEmpty(), "无法归因就不出回忆，不拿主题历史行顶替");
        verify(progressService, never()).selectPlayerNodeProgressList(any());
    }

    @Test
    void rowsFromAnotherTopicOrActivityAreFilteredOut() {
        when(routeMapper.selectCurrentByOwner(ACTIVITY, TOPIC, VIEWER)).thenReturn(null);
        PlayerNodeProgress otherTopic = progress(VIEWER, 1L, "https://img.example/x.jpg");
        otherTopic.setTopicId(TOPIC + 1);
        PlayerNodeProgress otherActivity = progress(VIEWER, 2L, "https://img.example/y.jpg");
        otherActivity.setActivityId(ACTIVITY + 1);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(List.of(otherTopic, otherActivity));
        when(completionFacts.isRegistered(VIEWER, ACTIVITY)).thenReturn(true);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(List.of(registration(VIEWER)));

        PlayerJourneyMemoryVO vo = (PlayerJourneyMemoryVO) application.memory(VIEWER, ACTIVITY, TOPIC)
                .get(AjaxResult.DATA_TAG);

        assertTrue(vo.getMoments().isEmpty(), "回忆只能来自本主题/本活动");
        assertEquals(PlayerJourneyMemoryVO.PROGRESS_LEGACY_TOPIC, vo.getProgressScope());
        assertNull(vo.getRunId(), "没有会话就是主题历史口径，不伪造一局");
    }

    private static TopicRouteSession session(Long id, String instance, String snapshot) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(id);
        session.setActivityId(ACTIVITY);
        session.setTopicId(TOPIC);
        session.setPlayInstanceId(instance);
        session.setStatus(TopicRouteSession.STATUS_COMPLETED);
        session.setConfigSnapshotJson(snapshot);
        return session;
    }
}
