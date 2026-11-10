package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.PlayHelpUseVO;
import com.chengyinhub.business.domain.vo.PlayHelpViewVO;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.support.PlayHelpCostApplication;
import com.chengyinhub.business.service.support.PlayHelpReceiptReadModel;
import com.chengyinhub.business.service.support.PlayHelpRunQuotaApplication;
import com.chengyinhub.business.service.support.PlayHelpUsagePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * qx-c5：活动场次（俱乐部开场）首局与自玩局一样，用建局冻结的 {@code firstrun:} 身份走同一条帮助链；
 * 进度实例仍是 default；旧活动首局（无身份）照旧拒绝「原局身份不可用」。
 */
class PlayHelpActivityFirstRunTest {

    private static final long MEMBER = 9004L;
    private static final long RUN = 7L;
    private static final long TOPIC = 990025L;
    private static final long ACTIVITY = 44L;
    private static final long NODE = 31L;
    private static final String RUN_A = "firstrun:2c7f1e0a-6b3d-4e55-8f21-9a0c4d7e1b01";

    private static String snapshot(String identity) {
        return "{" + (identity == null ? "" : "\"helpRunInstance\":\"" + identity + "\",")
                + "\"hintFreeUsesPerRun\":1,\"nodeAnswers\":{\"31\":{\"hintLadder\":["
                + "{\"level\":1,\"type\":\"TEXT\",\"payload\":\"看站厅里的线路图\"},"
                + "{\"level\":2,\"type\":\"LETTER\",\"cost\":{\"points\":30}},"
                + "{\"level\":3,\"type\":\"ANSWER\",\"cost\":{\"points\":30}}],"
                + "\"questionAnswer\":\"Line 3\"}}}";
    }

    private PlayHelpApplication application;
    private TopicRouteSessionMapper sessionMapper;
    private CmsTopicNodeMapper nodeMapper;
    private PlayAttemptLogMapper attemptMapper;
    private PlayNodeContext playNodeContext;
    private PlayHelpRunQuotaApplication quotaApplication;
    private PlayHelpReceiptReadModel receiptReadModel;
    private IPlayerNodeProgressService progressService;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(TopicRouteSessionMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        playNodeContext = mock(PlayNodeContext.class);
        quotaApplication = mock(PlayHelpRunQuotaApplication.class);
        receiptReadModel = mock(PlayHelpReceiptReadModel.class);
        progressService = mock(IPlayerNodeProgressService.class);
        application = new PlayHelpApplication();
        ReflectionTestUtils.setField(application, "topicRouteSessionMapper", sessionMapper);
        ReflectionTestUtils.setField(application, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(application, "playAttemptLogMapper", attemptMapper);
        ReflectionTestUtils.setField(application, "playNodeContext", playNodeContext);
        ReflectionTestUtils.setField(application, "playHelpRunQuotaApplication", quotaApplication);
        ReflectionTestUtils.setField(application, "playHelpReceiptReadModel", receiptReadModel);
        ReflectionTestUtils.setField(application, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(application, "cmsMemberTemplateService",
                mock(com.chengyinhub.business.service.ICmsMemberTemplateService.class));
        ReflectionTestUtils.setField(application, "objectMapper", new ObjectMapper());
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
        when(quotaApplication.remainingFreeUses(anyLong(), any(), any())).thenReturn(1);
    }

    @Test
    void activityFirstRunGetsTheLadderUnderItsFrozenIdentityAndRealActivity() {
        givenRun(activityRun(snapshot(RUN_A)));

        PlayHelpViewVO view = application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        assertTrue(view.enabled());
        assertEquals(3, view.layers().size());
        ArgumentCaptor<PlayHelpUsagePolicy.Scope> scope = ArgumentCaptor.forClass(PlayHelpUsagePolicy.Scope.class);
        verify(quotaApplication).remainingFreeUses(eq(MEMBER), scope.capture(), eq(1));
        assertEquals(RUN_A, scope.getValue().playInstance());
        assertEquals(Long.valueOf(ACTIVITY), scope.getValue().activityId());
        assertEquals(PlayHelpUsagePolicy.RunKind.TOPIC_ROUTE, scope.getValue().kind());
    }

    @Test
    void useOnActivityFirstRunGoesThroughTheSameQuotaChain() {
        givenRun(activityRun(snapshot(RUN_A)));
        when(quotaApplication.use(eq(MEMBER), any(), eq(1), eq(1), anyInt(), anyInt(), eq("req-1")))
                .thenReturn(new PlayHelpRunQuotaApplication.QuotaOutcome(
                        new PlayHelpCostApplication.Charge(true, false, 1, 0, 100L, null), false, true, 1));

        PlayHelpUseVO used = application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 1, "HELP", "req-1");

        assertEquals("看站厅里的线路图", used.content());
        ArgumentCaptor<PlayHelpUsagePolicy.Scope> scope = ArgumentCaptor.forClass(PlayHelpUsagePolicy.Scope.class);
        verify(quotaApplication).use(eq(MEMBER), scope.capture(), eq(1), eq(1), anyInt(), anyInt(), eq("req-1"));
        assertEquals(RUN_A, scope.getValue().playInstance());
        assertEquals(Long.valueOf(ACTIVITY), scope.getValue().activityId());
    }

    @Test
    void receiptReadsActivityProgressUnderDefaultAndFactsUnderTheRunIdentity() {
        givenRun(activityRun(snapshot(RUN_A)));

        application.receipt(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        ArgumentCaptor<PlayerNodeProgress> probe = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService, org.mockito.Mockito.atLeastOnce()).selectPlayerNodeProgressList(probe.capture());
        assertEquals("default", probe.getValue().getPlayInstanceId());
        assertEquals(Long.valueOf(ACTIVITY), probe.getValue().getActivityId());
        ArgumentCaptor<PlayHelpUsagePolicy.Scope> scope = ArgumentCaptor.forClass(PlayHelpUsagePolicy.Scope.class);
        verify(receiptReadModel).receipt(eq(MEMBER), scope.capture(), any(), any(), anyBoolean(), anyBoolean());
        assertEquals(RUN_A, scope.getValue().playInstance());
    }

    @Test
    void oldActivityRunWithoutIdentityKeepsTheRefusal() {
        givenRun(activityRun(snapshot(null)));

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE)));
        assertEquals("原局身份不可用，请重新进入本局", refused.getMessage());
        assertEquals("原局身份不可用，请重新进入本局", assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 1, "HELP", "req-x"))
                .getMessage());
        verify(quotaApplication, never()).use(anyLong(), any(), anyInt(), any(), anyInt(), anyInt(), anyString());
        verifyNoInteractions(attemptMapper);
    }

    @Test
    void anotherMembersActivityRunIsIndistinguishableFromMissing() {
        when(sessionMapper.selectAccessibleById(RUN, MEMBER + 1)).thenReturn(null);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.use(MEMBER + 1, String.valueOf(RUN), String.valueOf(NODE), 1, "HELP", "req-x"));

        assertEquals("原局不存在或无权访问", refused.getMessage());
        verifyNoInteractions(quotaApplication);
    }

    @Test
    void completionReceiptOnActivityFirstRunUsesTheSameIdentity() {
        assertEquals(RUN_A, PlayNodeCompletionApplication.helpInstanceOf(activityRun(snapshot(RUN_A)), "default"));
        assertNull(PlayNodeCompletionApplication.helpInstanceOf(activityRun(snapshot(null)), "default"));
    }

    private void givenRun(TopicRouteSession run) {
        when(sessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(run);
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        PlayNodeContext.PlayCtx ctx = new PlayNodeContext.PlayCtx();
        ctx.actId = ACTIVITY;
        ctx.topicId = TOPIC;
        when(playNodeContext.resolveCtx(MEMBER, String.valueOf(ACTIVITY), String.valueOf(TOPIC), node)).thenReturn(ctx);
    }

    private static TopicRouteSession activityRun(String snapshot) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(RUN);
        session.setMemberId(MEMBER);
        session.setTopicId(TOPIC);
        session.setActivityId(ACTIVITY);
        session.setPlayInstanceId("default");
        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setConfigSnapshotJson(snapshot);
        return session;
    }
}
