package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.PlayHelpUseVO;
import com.chengyinhub.business.domain.vo.PlayHelpViewVO;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.support.PlayHelpCostApplication;
import com.chengyinhub.business.service.support.PlayHelpRunQuotaApplication;
import com.chengyinhub.business.service.support.PlayHelpUsagePolicy;
import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BE-16 路由编排的负例:未登录、跨会员、节点不符/不存在、终局、原局身份缺失、准入闸拒绝、
 * 无冻结层级(旧行为)、未配置层、不可用层、缺内容不扣款、缺 requestId、重看零扣、跳过未接线。
 */
class PlayHelpApplicationTest {

    private static final long MEMBER = 77L;
    private static final long RUN = 5001L;
    private static final long TOPIC = 90L;
    private static final long NODE = 31L;
    private static final String INSTANCE = "inst-9f2";

    private static final String LADDER_ANSWER = "{\"nodeAnswers\":{\"31\":{\"hintLadder\":["
            + "{\"level\":1,\"type\":\"TEXT\",\"payload\":\"观察屋檐下的年份\",\"freeQuota\":1},"
            + "{\"level\":2,\"type\":\"LETTER\",\"cost\":{\"points\":30}},"
            + "{\"level\":3,\"type\":\"ANSWER\",\"cost\":{\"points\":30}}],"
            + "\"hint1\":\"看门牌\",\"questionAnswer\":\"Bridge 42\"}}}";

    /** 同样的阶梯再加一层 SKIP：作者确实配了跳过，才允许走跳过链。 */
    private static final String LADDER_WITH_SKIP = "{\"nodeAnswers\":{\"31\":{\"hintLadder\":["
            + "{\"level\":1,\"type\":\"TEXT\",\"payload\":\"观察屋檐下的年份\"},"
            + "{\"level\":2,\"type\":\"SKIP\"}],"
            + "\"hint1\":\"看门牌\",\"questionAnswer\":\"Bridge 42\"}}}";

    private PlayHelpApplication application;
    private TopicRouteSessionMapper sessionMapper;
    private CmsTopicNodeMapper nodeMapper;
    private PlayAttemptLogMapper attemptMapper;
    private PlayNodeContext playNodeContext;
    private PlayHelpRunQuotaApplication quotaApplication;
    private com.chengyinhub.business.service.support.PlayHelpReceiptReadModel receiptReadModel;
    private com.chengyinhub.business.service.ICmsMemberTemplateService templateService;
    private com.chengyinhub.business.service.IPlayerNodeProgressService progressService;
    private com.chengyinhub.business.mapper.GameRuntimeSessionMapper gameSessionMapper;
    private IAdvancedGameRuntimeService runtimeService;
    private PlayNodeCompletionApplication completionApplication;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(TopicRouteSessionMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        playNodeContext = mock(PlayNodeContext.class);
        quotaApplication = mock(PlayHelpRunQuotaApplication.class);
        receiptReadModel = mock(com.chengyinhub.business.service.support.PlayHelpReceiptReadModel.class);
        templateService = mock(com.chengyinhub.business.service.ICmsMemberTemplateService.class);
        progressService = mock(com.chengyinhub.business.service.IPlayerNodeProgressService.class);
        gameSessionMapper = mock(com.chengyinhub.business.mapper.GameRuntimeSessionMapper.class);
        runtimeService = mock(IAdvancedGameRuntimeService.class);
        completionApplication = mock(PlayNodeCompletionApplication.class);
        application = new PlayHelpApplication();
        ReflectionTestUtils.setField(application, "topicRouteSessionMapper", sessionMapper);
        ReflectionTestUtils.setField(application, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(application, "playAttemptLogMapper", attemptMapper);
        ReflectionTestUtils.setField(application, "playNodeContext", playNodeContext);
        ReflectionTestUtils.setField(application, "playHelpRunQuotaApplication", quotaApplication);
        ReflectionTestUtils.setField(application, "playHelpReceiptReadModel", receiptReadModel);
        ReflectionTestUtils.setField(application, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(application, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(application, "gameRuntimeSessionMapper", gameSessionMapper);
        ReflectionTestUtils.setField(application, "advancedGameRuntimeService", runtimeService);
        ReflectionTestUtils.setField(application, "playNodeCompletionApplication", completionApplication);
        ReflectionTestUtils.setField(application, "objectMapper", new ObjectMapper());
    }

    @Test
    void anonymousCallerIsRefusedBeforeAnyLookup() {
        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(0L, String.valueOf(RUN), String.valueOf(NODE)));

        assertEquals("请先登录", refused.getMessage());
        verifyNoInteractions(sessionMapper, nodeMapper, attemptMapper, quotaApplication);
    }

    @Test
    void malformedIdsAreRejectedBeforeLookup() {
        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(MEMBER, "abc", String.valueOf(NODE)));

        assertEquals("参数有误", refused.getMessage());
        verifyNoInteractions(sessionMapper);
    }

    @Test
    void anotherMembersRunIsIndistinguishableFromMissing() {
        when(sessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(null);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE)));

        assertEquals("原局不存在或无权访问", refused.getMessage());
    }

    @Test
    void terminalRunCannotUseHelp() {
        when(sessionMapper.selectAccessibleById(RUN, MEMBER))
                .thenReturn(session(TopicRouteSession.STATUS_COMPLETED, LADDER_ANSWER));

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE)));

        assertEquals("本局已结束，不能使用帮助", refused.getMessage());
    }

    @Test
    void nodeOutsideTheRunTopicIsRefused() {
        when(sessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(session(0, LADDER_ANSWER));
        CmsTopicNode other = node(TOPIC + 1);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(other);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE)));

        assertEquals("节点与当前原局不一致", refused.getMessage());
    }

    @Test
    void missingNodeIsRefused() {
        when(sessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(session(0, LADDER_ANSWER));
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(null);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE)));

        assertEquals("节点不存在", refused.getMessage());
    }

    @Test
    void nodeAdmissionRefusalFromTheExistingGateIsSurfaced() {
        when(sessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(session(0, LADDER_ANSWER));
        CmsTopicNode node = node(TOPIC);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        PlayNodeContext.PlayCtx ctx = new PlayNodeContext.PlayCtx();
        ctx.error = new AjaxResult(403, "请先报名该活动");
        when(playNodeContext.resolveCtx(MEMBER, null, String.valueOf(TOPIC), node)).thenReturn(ctx);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE)));

        assertEquals("请先报名该活动", refused.getMessage());
    }

    @Test
    void runWithoutPlayInstanceIdentityIsRefused() {
        TopicRouteSession session = session(0, LADDER_ANSWER);
        session.setPlayInstanceId("default");
        when(sessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(session);
        CmsTopicNode node = node(TOPIC);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(playNodeContext.resolveCtx(MEMBER, null, String.valueOf(TOPIC), node)).thenReturn(ctx());

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE)));

        assertEquals("原局身份不可用，请重新进入本局", refused.getMessage());
    }

    @Test
    void frozenConfigWithoutLadderKeepsLegacyBehaviour() {
        givenAuthorisedRun("{\"nodeAnswers\":{\"31\":{\"hint1\":\"看门牌\"}}}");

        PlayHelpViewVO view = application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        assertFalse(view.enabled());
        assertTrue(view.layers().isEmpty());
        assertNull(view.remainingFreeUses(), "无阶梯不出整局计数器");
    }

    @Test
    void ladderIsDeliveredWithFreeQuotaAndUnlockedFact() {
        givenAuthorisedRun(LADDER_ANSWER);
        when(attemptMapper.selectByIdempotencyKey(factKey(1))).thenReturn(fact(1));

        PlayHelpViewVO view = application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        assertTrue(view.enabled());
        assertEquals(3, view.layers().size());
        assertTrue(view.layers().get(0).unlocked());
        assertTrue(view.layers().get(0).free());
        assertEquals(0, view.layers().get(0).costPoints(), "已解锁层重看不扣");
        assertFalse(view.layers().get(1).unlocked());
        assertEquals(30, view.layers().get(1).costPoints());
        assertEquals(Integer.valueOf(0), view.remainingFreeUses(), "有阶梯而未声明 N:整局默认 0 次,是额度账本给出的真实 0");
    }

    @Test
    void foreignFactRowDoesNotUnlockTheLevel() {
        givenAuthorisedRun(LADDER_ANSWER);
        PlayAttemptLog other = fact(1);
        other.setMemberId(MEMBER + 1);
        when(attemptMapper.selectByIdempotencyKey(factKey(1))).thenReturn(other);

        PlayHelpViewVO view = application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        assertFalse(view.layers().get(0).unlocked());
    }

    @Test
    void useWithoutRequestIdIsRejected() {
        givenAuthorisedRun(LADDER_ANSWER);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 1, "HELP", " "));

        assertEquals("缺少请求编号", refused.getMessage());
        verifyNoInteractions(quotaApplication);
    }

    @Test
    void unconfiguredLevelIsRejected() {
        givenAuthorisedRun(LADDER_ANSWER);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 5, "HELP", "req-5"));

        assertEquals("该级帮助未配置", refused.getMessage());
        verifyNoInteractions(quotaApplication);
    }

    @Test
    void locationToolLevelIsRefusedAsUnavailable() {
        givenAuthorisedRun("{\"nodeAnswers\":{\"31\":{\"hintLadder\":["
                + "{\"level\":1,\"type\":\"LOCATION\",\"cost\":{\"points\":10}}]}}}");

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 1, "HELP", "req-loc"));

        assertTrue(refused.getMessage().contains(PlayHelpUsagePolicy.REASON_LOCATION_NOT_WIRED));
        verifyNoInteractions(quotaApplication);
    }

    @Test
    void levelWithoutDerivableContentIsRejectedBeforeAnyCharge() {
        // ANSWER 层既没有 payload,冻结答案也没有揭示文案/标准答案 => 不给内容,也不动钱。
        givenAuthorisedRun("{\"nodeAnswers\":{\"31\":{\"hintLadder\":["
                + "{\"level\":1,\"type\":\"ANSWER\",\"cost\":{\"points\":40}}]}}}");

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 1, "HELP", "req-a"));

        assertEquals("该层内容未配置，未扣除任何代价", refused.getMessage());
        verifyNoInteractions(quotaApplication);
    }

    @Test
    void firstLetterLevelRevealsOnlyOneCharacter() {
        givenAuthorisedRun(LADDER_ANSWER);
        givenCharge(new PlayHelpCostApplication.Charge(true, false, 2, 30, 970L, "bk"));

        PlayHelpUseVO result = application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE),
                2, "help", "req-letter");

        assertEquals("B", result.content(), "只发首字母,不发整串答案");
        assertEquals("LETTER", result.type());
        assertEquals(30, result.chargedPoints());
        assertEquals(970L, result.balanceAfter());
        verify(quotaApplication).use(eq(MEMBER), any(), eq(2), eq(0), eq(0), eq(30), eq("req-letter"));
    }

    @Test
    void reReadingAnUnlockedLevelChargesNothing() {
        givenAuthorisedRun(LADDER_ANSWER);
        givenCharge(new PlayHelpCostApplication.Charge(false, true, 3, 0, 970L, null));

        PlayHelpUseVO result = application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE),
                3, "HELP", "req-again");

        assertTrue(result.alreadyUnlocked());
        assertFalse(result.applied());
        assertEquals(0, result.chargedPoints());
        assertEquals("Bridge 42", result.content());
    }

    /**
     * 作者声明整局 N 时,GET 的剩余次数来自额度账本(不是逐层 0|1),POST 把冻结的 N 交给额度入口。
     * 这一条钉住「整局唯一值」不被按节点或按题重置。
     */
    @Test
    void runSharedQuotaDrivesRemainingUsesAndIsHandedToTheLedger() {
        givenAuthorisedRun("{\"hintFreeUsesPerRun\":2,\"nodeAnswers\":{\"31\":{\"hintLadder\":["
                + "{\"level\":1,\"type\":\"TEXT\",\"payload\":\"先看屋檐\"}]}}}");
        when(quotaApplication.remainingFreeUses(eq(MEMBER), any(), eq(2))).thenReturn(1);
        when(quotaApplication.use(eq(MEMBER), any(), eq(1), eq(2), eq(0), eq(0), eq("req-n")))
                .thenReturn(new PlayHelpRunQuotaApplication.QuotaOutcome(
                        new PlayHelpCostApplication.Charge(true, false, 1, 0, 970L, "bk"), true, true, 0));

        PlayHelpViewVO view = application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE));
        assertTrue(view.layers().get(0).free(), "还有 1 次整局免费,该层应显示免费");
        assertEquals(0, view.layers().get(0).costPoints());

        PlayHelpUseVO used = application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE),
                1, "HELP", "req-n");
        assertEquals(0, used.chargedPoints());
        verify(quotaApplication).use(eq(MEMBER), any(), eq(1), eq(2), eq(0), eq(0), eq("req-n"));
    }

    /**
     * 只读回执：与完成回包同一份计算，重进后读到的数值一致；本方法不写任何行。
     */
    @Test
    void receiptReadsTheSameBreakdownWithoutWritingAnything() {
        givenAuthorisedRun(LADDER_ANSWER);
        Map<String, Object> expected = new java.util.LinkedHashMap<>();
        expected.put("scoreState", "NOT_SCORED");
        expected.put("pointsCharged", 20);
        when(receiptReadModel.receipt(eq(MEMBER), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(expected);

        Map<String, Object> first = application.receipt(MEMBER, String.valueOf(RUN), String.valueOf(NODE));
        Map<String, Object> second = application.receipt(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        assertEquals(expected, first);
        assertEquals(expected, second, "重进两次读到同一份明细");
        // 只读：不消耗额度、不写层级事实。
        verifyNoInteractions(quotaApplication);
    }

    @Test
    void receiptUsesTheExistingPuzzleScorePolicyBaseWhenTheNodeIsScored() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode snapshot =
                (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(LADDER_ANSWER);
        com.fasterxml.jackson.databind.node.ObjectNode answer =
                (com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("nodeAnswers").path(String.valueOf(NODE));
        answer.put("contentSnapshotVersion", 1).put("templateId", 701L);
        answer.putObject("nodePresentation").put("id", NODE).put("topicId", TOPIC).put("templateId", 701L);
        answer.putObject("templatePresentation").put("validationMethod", 1);
        givenAuthorisedRun(snapshot.toString());
        CmsTopicNode scoredNode = node(TOPIC);
        scoredNode.setTemplateId(701L);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(scoredNode);
        when(playNodeContext.resolveCtx(MEMBER, null, String.valueOf(TOPIC), scoredNode)).thenReturn(ctx());
        when(playNodeContext.resolveMode(any(), eq(TOPIC)))
                .thenReturn(com.chengyinhub.common.constant.PlayMode.LINEAR_ORIENTEERING);
        when(receiptReadModel.receipt(anyLong(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(Map.of("baseScore", 100));

        application.receipt(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        verify(receiptReadModel).receipt(eq(MEMBER), any(), any(),
                eq(com.chengyinhub.business.util.PuzzleScorePolicy.solvedScore(0)), anyBoolean(), anyBoolean());
        verifyNoInteractions(templateService);
    }

    /** 跳过回执里也带同一份明细：两次动作口径一致。 */
    @Test
    void skipCarriesTheSameBreakdownInItsResponse() {
        givenAuthorisedRun(LADDER_WITH_SKIP);
        com.chengyinhub.business.domain.PlayerNodeProgress skipped =
                new com.chengyinhub.business.domain.PlayerNodeProgress();
        skipped.setStatus(1);
        skipped.setCompletionMode(com.chengyinhub.business.util.PuzzleScorePolicy.COMPLETION_SKIPPED);
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(java.util.List.of(skipped));
        when(runtimeService.skip(eq(MEMBER), any(), eq(TOPIC), eq(NODE), eq("req-s")))
                .thenReturn(Map.of("status", "COMPLETED"));
        when(completionApplication.advanceProgress(anyLong(), any(), any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(Map.of("firstTime", true));
        when(receiptReadModel.receipt(anyLong(), any(), any(), any(), eq(true), anyBoolean()))
                .thenReturn(Map.of("skipped", true, "netScore", 0));

        Map<String, Object> result = application.skip(MEMBER, String.valueOf(RUN), String.valueOf(NODE), "req-s");

        assertEquals(Map.of("skipped", true, "netScore", 0), result.get("helpReceipt"));
    }

    /**
     * 路线局的跳过只能来自自己的进度行：同一会员在同一节点另开过**高级玩法局**并跳过时，
     * 那条 state.skip 属于另一个原局，绝不能把正常完成的路线节点报成「跳过 · 0 分」。
     */
    @Test
    void routeReceiptIgnoresAnUnrelatedAdvancedSkipFact() {
        givenAuthorisedRun(LADDER_ANSWER);
        // 高级玩法局那边存在跳过事实
        when(runtimeService.skippedAssistedCompletion(eq(MEMBER), any(), eq(TOPIC), eq(NODE))).thenReturn(true);
        // 而路线局的进度行是正常解出
        com.chengyinhub.business.domain.PlayerNodeProgress done =
                new com.chengyinhub.business.domain.PlayerNodeProgress();
        done.setStatus(1);
        done.setCompletionMode(com.chengyinhub.business.util.PuzzleScorePolicy.COMPLETION_SOLVED);
        when(progressService.selectPlayerNodeProgressList(any()))
                .thenReturn(java.util.List.of(done));
        when(receiptReadModel.receipt(anyLong(), any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(Map.of("skipped", false));

        application.receipt(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        // 路线局判跳过/揭示时不查高级局状态
        verify(runtimeService, never()).skippedAssistedCompletion(anyLong(), any(), any(), any());
        verify(receiptReadModel).receipt(eq(MEMBER), any(), any(), any(), eq(false), eq(false));
    }

    /** 高级玩法局则相反：只看自己的 state.skip，不读个人进度行。 */
    @Test
    void advancedReceiptReadsOnlyTheAdvancedSkipFact() {
        GameRuntimeSession runtime = new GameRuntimeSession();
        runtime.setId(RUN);
        runtime.setTopicId(TOPIC);
        runtime.setNodeId(NODE);
        runtime.setStatus(GameRuntimeSession.STATUS_RUNNING);
        runtime.setConfigSnapshotJson("{\"hintLadder\":[{\"level\":1,\"type\":\"TEXT\"}]}");
        when(gameSessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(runtime);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node(TOPIC));
        when(runtimeService.skippedAssistedCompletion(eq(MEMBER), any(), eq(TOPIC), eq(NODE))).thenReturn(true);
        when(receiptReadModel.receipt(anyLong(), any(), any(), any(), eq(true), eq(false)))
                .thenReturn(Map.of("skipped", true));

        application.receiptAdvanced(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        verify(runtimeService).skippedAssistedCompletion(eq(MEMBER), any(), eq(TOPIC), eq(NODE));
        verifyNoInteractions(progressService);
    }

    /**
     * 纯高级玩法局:没有 topic_route_session,但层级/额度/扣减走同一套。
     * 这里钉住「原局身份来源换了,规则没换」。
     */
    @Test
    void advancedSessionUsesTheSameLadderAndQuotaRules() {
        GameRuntimeSession runtime = new GameRuntimeSession();
        runtime.setId(RUN);
        runtime.setTopicId(TOPIC);
        runtime.setNodeId(NODE);
        runtime.setStatus(GameRuntimeSession.STATUS_RUNNING);
        runtime.setConfigSnapshotJson("{\"hintFreeUsesPerRun\":1,\"hintLadder\":["
                + "{\"level\":1,\"type\":\"TEXT\",\"payload\":\"看屋檐\"}]}");
        when(gameSessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(runtime);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node(TOPIC));
        when(quotaApplication.remainingFreeUses(eq(MEMBER), any(), eq(1))).thenReturn(1);

        PlayHelpViewVO view = application.viewAdvanced(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        assertTrue(view.enabled());
        assertTrue(view.layers().get(0).free(), "整局还剩 1 次免费");
        // kind 必须是 ADVANCED_GAME：交给额度层的作用域决定归属守卫走哪张表。
        org.mockito.ArgumentCaptor<PlayHelpUsagePolicy.Scope> captor =
                org.mockito.ArgumentCaptor.forClass(PlayHelpUsagePolicy.Scope.class);
        verify(quotaApplication).remainingFreeUses(eq(MEMBER), captor.capture(), eq(1));
        assertEquals(PlayHelpUsagePolicy.RunKind.ADVANCED_GAME, captor.getValue().kind());
        assertEquals(Long.valueOf(RUN), captor.getValue().advancedSessionId());
        assertEquals("game:" + RUN, captor.getValue().playInstance());
    }

    /**
     * 高级玩法局跳过:只落局状态,不写个人进度;0 分 + SKIPPED + 辅助完成。
     * 断言的是传给运行时的身份与回执口径,不是文案。
     */
    @Test
    void advancedSessionSkipSettlesZeroScoreWithoutProgressRow() {
        GameRuntimeSession runtime = new GameRuntimeSession();
        runtime.setId(RUN);
        runtime.setTopicId(TOPIC);
        runtime.setNodeId(NODE);
        runtime.setStatus(GameRuntimeSession.STATUS_RUNNING);
        runtime.setConfigSnapshotJson("{\"hintLadder\":[{\"level\":1,\"type\":\"SKIP\"}]}");
        when(gameSessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(runtime);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node(TOPIC));
        when(runtimeService.skip(eq(MEMBER), any(), eq(TOPIC), eq(NODE), eq("req-s")))
                .thenReturn(Map.of("status", "COMPLETED"));

        Map<String, Object> result = application.skipAdvanced(MEMBER, String.valueOf(RUN),
                String.valueOf(NODE), "req-s");
        // 高级局跳过只落局状态：不能再写一条 topic_route 的个人进度行。
        verify(completionApplication, never()).advanceProgress(anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());

        assertEquals(true, result.get("skipped"));
        assertEquals(0, result.get("score"));
        assertEquals(PuzzleScorePolicy.COMPLETION_SKIPPED, result.get("completionMode"));
        verify(completionApplication, never()).advanceProgress(anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    /** 高级玩法局的节点必须与局一致:串局一律拒绝,不靠 sessionId 猜。 */
    @Test
    void advancedSessionRejectsAForeignNode() {
        GameRuntimeSession runtime = new GameRuntimeSession();
        runtime.setId(RUN);
        runtime.setTopicId(TOPIC);
        runtime.setNodeId(NODE + 1);
        runtime.setStatus(GameRuntimeSession.STATUS_RUNNING);
        when(gameSessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(runtime);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.viewAdvanced(MEMBER, String.valueOf(RUN), String.valueOf(NODE)));

        assertEquals("节点与当前原局不一致", refused.getMessage());
    }

    /** 各节点声明了不同的 N:拒绝,不静默挑一个。 */
    @Test
    void conflictingRunQuotaDeclarationsAreRefused() {
        givenAuthorisedRun("{\"nodeAnswers\":{\"31\":{\"hintFreeUsesPerRun\":2},"
                + "\"32\":{\"hintFreeUsesPerRun\":3}}}");

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE)));

        assertTrue(refused.getMessage().startsWith("本局各节点"));
        verifyNoInteractions(quotaApplication);
    }

    @Test
    void costRefusalPropagatesSoTheTransactionRollsBack() {
        givenAuthorisedRun(LADDER_ANSWER);
        when(quotaApplication.use(anyLong(), any(), anyInt(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new PlayHelpCostApplication.PlayHelpCostException("POINTS_INSUFFICIENT", "积分不足"));

        PlayHelpCostApplication.PlayHelpCostException refused = assertThrows(
                PlayHelpCostApplication.PlayHelpCostException.class,
                () -> application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 2, "HELP", "req-poor"));

        assertEquals("积分不足", refused.getMessage());
        assertEquals("POINTS_INSUFFICIENT", refused.code());
    }

    /**
     * 跳过不能借提示回执冒充:走 use 的 SKIP 必须被指到独立入口,且**一格代价都不许动**。
     */
    @Test
    void skipThroughTheHintRouteIsRefusedAndPointsAtTheSkipRoute() {
        givenAuthorisedRun(LADDER_ANSWER);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 1, "SKIP", "req-skip"));

        assertTrue(refused.getMessage().startsWith("跳过请使用"));
        verifyNoInteractions(quotaApplication);
    }

    /** 作者未配 SKIP 层:拒绝,且既不动运行时也不写进度。 */
    @Test
    void skipIsRefusedWhenTheFrozenLadderHasNoSkipLevel() {
        givenAuthorisedRun(LADDER_ANSWER);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.skip(MEMBER, String.valueOf(RUN), String.valueOf(NODE), "req-skip"));

        assertEquals("该节点未配置跳过", refused.getMessage());
        verifyNoInteractions(runtimeService, completionApplication);
    }

    /**
     * 配了 SKIP 层:运行时先记跳过事实,再由既有完成链写 0 分 + SKIPPED + 探索值/徽章。
     * 断言的是**传给完成链的参数**,不是文案 —— 跳过被写成 SOLVED 或按 FULL 发奖都会在这里红。
     */
    @Test
    void skipMarksAssistedCompletionWithZeroScoreAndExplorationRewardsOnly() {
        givenAuthorisedRun(LADDER_WITH_SKIP);
        when(runtimeService.skip(eq(MEMBER), any(), eq(TOPIC), eq(NODE), eq("req-skip")))
                .thenReturn(Map.of("status", "COMPLETED"));
        when(completionApplication.advanceProgress(anyLong(), any(), any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any(), any())).thenReturn(Map.of("firstTime", true));

        Map<String, Object> result = application.skip(MEMBER, String.valueOf(RUN), String.valueOf(NODE), "req-skip");

        assertEquals(true, result.get("skipped"));
        assertEquals(0, result.get("score"));
        assertEquals(PuzzleScorePolicy.COMPLETION_SKIPPED, result.get("completionMode"));
        assertEquals(true, result.get("assisted"));
        verify(completionApplication).advanceProgress(eq(MEMBER), any(), any(),
                any(), eq(PuzzleScorePolicy.skippedScore()), eq(PuzzleScorePolicy.COMPLETION_SKIPPED),
                eq(RewardPolicy.XP_BADGE_ONLY), any(), any(), any(), any(), any());
    }

    @Test
    void unknownActionIsRejected() {
        givenAuthorisedRun(LADDER_ANSWER);

        PlayHelpApplication.HelpRefusal refused = assertThrows(PlayHelpApplication.HelpRefusal.class,
                () -> application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 1, "REVEAL_ALL", "req-x"));

        assertEquals("参数有误", refused.getMessage());
    }

    // ------------------------------------------------------------------ fixtures

    private void givenAuthorisedRun(String configSnapshotJson) {
        when(sessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(session(0, configSnapshotJson));
        CmsTopicNode node = node(TOPIC);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(playNodeContext.resolveCtx(MEMBER, null, String.valueOf(TOPIC), node)).thenReturn(ctx());
    }

    private void givenCharge(PlayHelpCostApplication.Charge charge) {
        // 有 hintLadder 但没有显式 N = 整局 N 默认 0（新规则不送免费次数），仍走 G7 额度入口。
        when(quotaApplication.use(eq(MEMBER), any(), anyInt(), eq(0), anyInt(), anyInt(), any()))
                .thenReturn(new PlayHelpRunQuotaApplication.QuotaOutcome(charge, false, false, 0));
    }

    private static String factKey(int level) {
        return PlayHelpUsagePolicy.levelFactKey(MEMBER, factKeyPrefix(), level);
    }

    private static String factKeyPrefix() {
        return PlayHelpUsagePolicy.scopeKey(new PlayHelpUsagePolicy.Scope(TOPIC, null, NODE, INSTANCE));
    }

    private static PlayNodeContext.PlayCtx ctx() {
        PlayNodeContext.PlayCtx ctx = new PlayNodeContext.PlayCtx();
        ctx.actId = 0L;
        ctx.topicId = TOPIC;
        return ctx;
    }

    private static TopicRouteSession session(int status, String configSnapshotJson) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(RUN);
        session.setMemberId(MEMBER);
        session.setTopicId(TOPIC);
        session.setPlayInstanceId(INSTANCE);
        session.setStatus(status);
        session.setConfigSnapshotJson(configSnapshotJson);
        return session;
    }

    private static CmsTopicNode node(long topicId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(topicId);
        return node;
    }

    private static PlayAttemptLog fact(int level) {
        PlayAttemptLog log = new PlayAttemptLog();
        log.setMemberId(MEMBER);
        log.setNodeId(NODE);
        log.setTopicId(TOPIC);
        log.setAttemptType(PlayHelpCostApplication.ATTEMPT_TYPE_HELP);
        log.setResult(level);
        log.setIdempotencyKey("test-fact-" + level);
        return log;
    }
}
