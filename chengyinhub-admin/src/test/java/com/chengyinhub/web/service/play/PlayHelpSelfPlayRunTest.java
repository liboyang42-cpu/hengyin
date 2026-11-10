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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * qx-c4（2026-11-10 owner 定稿「自玩也要能用提示」）：自玩通行证局用建局冻结的
 * {@code selfplay:} 身份走同一条帮助链；旧自玩局、活动场次 default 局、伪造身份仍拒绝。
 */
class PlayHelpSelfPlayRunTest {

    private static final long MEMBER = 9004L;
    private static final long RUN = 2L;
    private static final long TOPIC = 990025L;
    private static final long NODE = 31L;
    private static final String SELF_A = "selfplay:0b0e6f53-2f1d-4c4a-9a51-3c1b9f7f0a01";
    private static final String SELF_B = "selfplay:7d5c2a10-8e44-4f0b-b2a6-55e0c7d9e102";

    /** 冻结快照：阶梯 + 整局 N=2；作者开局后怎么改模板都不影响这份。 */
    private static String snapshot(String identity) {
        return "{" + (identity == null ? "" : "\"helpRunInstance\":\"" + identity + "\",")
                + "\"hintFreeUsesPerRun\":2,\"nodeAnswers\":{\"31\":{\"hintLadder\":["
                + "{\"level\":1,\"type\":\"TEXT\",\"payload\":\"观察屋檐下的年份\"},"
                + "{\"level\":2,\"type\":\"LETTER\",\"cost\":{\"points\":30}},"
                + "{\"level\":3,\"type\":\"ANSWER\",\"cost\":{\"points\":30}}],"
                + "\"questionAnswer\":\"Bridge 42\"}}}";
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
        when(quotaApplication.remainingFreeUses(anyLong(), any(), any())).thenReturn(2);
    }

    @Test
    void stampedSelfPlayRunGetsTheLadderUnderItsFrozenIdentity() {
        givenRun(selfPlayRun(SELF_A, snapshot(SELF_A)));

        PlayHelpViewVO view = application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        assertTrue(view.enabled());
        assertEquals(3, view.layers().size());
        ArgumentCaptor<PlayHelpUsagePolicy.Scope> scope = ArgumentCaptor.forClass(PlayHelpUsagePolicy.Scope.class);
        verify(quotaApplication).remainingFreeUses(eq(MEMBER), scope.capture(), eq(2));
        assertEquals(SELF_A, scope.getValue().playInstance());
        assertEquals(PlayHelpUsagePolicy.RunKind.TOPIC_ROUTE, scope.getValue().kind());
        // 已解锁层按该身份的事实键回查。
        verify(attemptMapper).selectByIdempotencyKey(PlayHelpUsagePolicy.levelFactKey(MEMBER,
                PlayHelpUsagePolicy.scopeKey(new PlayHelpUsagePolicy.Scope(TOPIC, null, NODE, SELF_A)), 1));
    }

    @Test
    void useOnSelfPlayRunChargesThroughTheSameQuotaChainWithTheRunIdentity() {
        givenRun(selfPlayRun(SELF_A, snapshot(SELF_A)));
        when(quotaApplication.use(eq(MEMBER), any(), eq(1), eq(2), anyInt(), anyInt(), eq("req-1")))
                .thenReturn(new PlayHelpRunQuotaApplication.QuotaOutcome(
                        new PlayHelpCostApplication.Charge(true, false, 1, 0, 100L, null), true, true, 1));

        PlayHelpUseVO used = application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 1, "HELP", "req-1");

        assertEquals("观察屋檐下的年份", used.content());
        ArgumentCaptor<PlayHelpUsagePolicy.Scope> scope = ArgumentCaptor.forClass(PlayHelpUsagePolicy.Scope.class);
        verify(quotaApplication).use(eq(MEMBER), scope.capture(), eq(1), eq(2), anyInt(), anyInt(), eq("req-1"));
        assertEquals(SELF_A, scope.getValue().playInstance());
    }

    @Test
    void twoSelfPlayRunsNeverShareFactsOrQuota() {
        String a = PlayHelpUsagePolicy.scopeKey(new PlayHelpUsagePolicy.Scope(TOPIC, null, NODE, SELF_A));
        String b = PlayHelpUsagePolicy.scopeKey(new PlayHelpUsagePolicy.Scope(TOPIC, null, NODE, SELF_B));

        assertNotEquals(a, b);
        assertNotEquals(PlayHelpUsagePolicy.levelFactKey(MEMBER, a, 1), PlayHelpUsagePolicy.levelFactKey(MEMBER, b, 1));
    }

    @Test
    void receiptReadsProgressUnderTheDefaultProgressInstanceAndFactsUnderTheRunIdentity() {
        givenRun(selfPlayRun(SELF_A, snapshot(SELF_A)));

        application.receipt(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        ArgumentCaptor<PlayerNodeProgress> probe = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService, org.mockito.Mockito.atLeastOnce()).selectPlayerNodeProgressList(probe.capture());
        assertEquals("default", probe.getValue().getPlayInstanceId());
        ArgumentCaptor<PlayHelpUsagePolicy.Scope> scope = ArgumentCaptor.forClass(PlayHelpUsagePolicy.Scope.class);
        verify(receiptReadModel).receipt(eq(MEMBER), scope.capture(), any(), any(), anyBoolean(), anyBoolean());
        assertEquals(SELF_A, scope.getValue().playInstance());
    }

    @Test
    void legacySelfPlayRunWithoutFrozenIdentityKeepsTheRefusal() {
        givenRun(selfPlayRun(null, snapshot(null)));

        assertRefusedWithoutCharge();
    }

    @Test
    void malformedIdentityInSnapshotIsNotAccepted() {
        givenRun(selfPlayRun(null, snapshot("selfplay:../../other")));

        assertRefusedWithoutCharge();
    }

    @Test
    void activitySessionDefaultRunIsStillRefusedEvenIfASelfPlayIdentityWereInItsSnapshot() {
        TopicRouteSession run = selfPlayRun(SELF_A, snapshot(SELF_A));
        run.setActivityId(44L);
        when(sessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(run);
        CmsTopicNode node = node();
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        PlayNodeContext.PlayCtx ctx = new PlayNodeContext.PlayCtx();
        ctx.actId = 44L;
        ctx.topicId = TOPIC;
        when(playNodeContext.resolveCtx(MEMBER, "44", String.valueOf(TOPIC), node)).thenReturn(ctx);

        assertRefusedWithoutCharge();
    }

    @Test
    void anotherPlayersSelfPlayRunIsIndistinguishableFromMissing() {
        when(sessionMapper.selectAccessibleById(RUN, MEMBER + 1)).thenReturn(null);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.use(MEMBER + 1, String.valueOf(RUN), String.valueOf(NODE), 1, "HELP", "req-x"));

        assertEquals("原局不存在或无权访问", refused.getMessage());
        verifyNoInteractions(quotaApplication);
    }

    @Test
    void completionReceiptUsesTheSameRunIdentityAsTheHelpRoute() {
        assertEquals(SELF_A, PlayNodeCompletionApplication.helpInstanceOf(selfPlayRun(SELF_A, snapshot(SELF_A)), "default"));
        // 旧自玩局：明细没有帮助身份（与 /api/play/help 拒绝一致），不拿 default 冒充一局。
        assertEquals(null, PlayNodeCompletionApplication.helpInstanceOf(selfPlayRun(null, snapshot(null)), "default"));
        TopicRouteSession replay = selfPlayRun(null, snapshot(null));
        replay.setPlayInstanceId("replay:12");
        assertEquals("replay:12", PlayNodeCompletionApplication.helpInstanceOf(replay, "replay:12"));
        assertEquals("default", PlayNodeCompletionApplication.helpInstanceOf(null, "default"));
    }

    private void assertRefusedWithoutCharge() {
        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE)));
        assertEquals("原局身份不可用，请重新进入本局", refused.getMessage());
        assertEquals("原局身份不可用，请重新进入本局", assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 1, "HELP", "req-x"))
                .getMessage());
        verify(quotaApplication, never()).use(anyLong(), any(), anyInt(), any(), anyInt(), anyInt(), anyString());
        verifyNoInteractions(attemptMapper);
    }

    private void givenRun(TopicRouteSession run) {
        when(sessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(run);
        CmsTopicNode node = node();
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        PlayNodeContext.PlayCtx ctx = new PlayNodeContext.PlayCtx();
        ctx.actId = 0L;
        ctx.topicId = TOPIC;
        when(playNodeContext.resolveCtx(MEMBER, null, String.valueOf(TOPIC), node)).thenReturn(ctx);
    }

    private static TopicRouteSession selfPlayRun(String ignored, String snapshot) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(RUN);
        session.setMemberId(MEMBER);
        session.setTopicId(TOPIC);
        session.setActivityId(0L);
        session.setPlayInstanceId("default");
        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setConfigSnapshotJson(snapshot);
        return session;
    }

    private static CmsTopicNode node() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        return node;
    }
}
