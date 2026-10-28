package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IOmsTicketService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IRewardDropService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ITopicCompletionCouponRewardService;
import com.chengyinhub.business.service.IXpBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * advanceProgress 并发幂等性：双层原子闸（节点 + 通关）。
 *
 * <p>节点级：普通 INSERT + DuplicateKeyException 精确捕获 + 四列回查验证。
 * <p>通关级：TOPIC_FINISH_* 勋章 INSERT 返回 rows>0 为闸，pointsAward/onTopicComplete 不吞。
 */
class ApiPlayProgressIdempotencyTest {

    private static final Long MEMBER = 88L;
    private static final Long TOPIC = 300L;
    private static final Long NODE = 401L;
    private static final Long ACTIVITY = 500L;

    private IPlayerNodeProgressService progressService;
    private ICmsTopicNodeService nodeService;
    private ICmsTopicService cmsTopicService;
    private IPointsAwardService pointsAwardService;
    private IPlayerProgressionService playerProgressionService;
    private IGrowthService growthService;
    private IXpBudgetService xpBudgetService;
    private IRewardDropService rewardDropService;
    private PlayerBadgeMapper badgeMapper;
    private ITopicCompletionCouponRewardService topicCompletionCouponRewardService;
    private IOfficialEventV2Service officialEventV2Service;
    private IPerkLedgerService perkLedgerService;
    private ICmsRegistrationService cmsRegistrationService;
    private com.chengyinhub.business.service.IShopDayProofService shopDayProofService;
    private ISmsCouponHistoryService smsCouponHistoryService;
    private com.chengyinhub.business.service.ICmsMemberTemplateService cmsMemberTemplateService;
    private com.chengyinhub.business.mapper.MemberCollectibleMapper memberCollectibleMapper;
    private com.chengyinhub.web.service.TopicRouteCompletionCoordinator routeCompletionCoordinator;
    private com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper;
    private ApiPlayProgressController controller;

    @BeforeEach
    void setUp() {
        progressService = mock(IPlayerNodeProgressService.class);
        nodeService = mock(ICmsTopicNodeService.class);
        cmsTopicService = mock(ICmsTopicService.class);
        pointsAwardService = mock(IPointsAwardService.class);
        playerProgressionService = mock(IPlayerProgressionService.class);
        growthService = mock(IGrowthService.class);
        xpBudgetService = mock(IXpBudgetService.class);
        rewardDropService = mock(IRewardDropService.class);
        badgeMapper = mock(PlayerBadgeMapper.class);
        topicCompletionCouponRewardService = mock(ITopicCompletionCouponRewardService.class);
        officialEventV2Service = mock(IOfficialEventV2Service.class);
        perkLedgerService = mock(IPerkLedgerService.class);
        cmsRegistrationService = mock(ICmsRegistrationService.class);
        shopDayProofService = mock(com.chengyinhub.business.service.IShopDayProofService.class);
        smsCouponHistoryService = mock(ISmsCouponHistoryService.class);
        cmsMemberTemplateService = mock(com.chengyinhub.business.service.ICmsMemberTemplateService.class);
        memberCollectibleMapper = mock(com.chengyinhub.business.mapper.MemberCollectibleMapper.class);
        routeCompletionCoordinator = mock(com.chengyinhub.web.service.TopicRouteCompletionCoordinator.class);

        auditMapper = mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);

        controller = new ApiPlayProgressController();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "businessAuditEventMapper", auditMapper);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerNodeProgressService", progressService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodeService);
        // 承接方现场层:没有中标商家时返回空对象,取值全部回落主办方模板 ——
        // 用真实现而不是 mock,免得「回落」这条路径在测试里被 mock 假装成功。
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "nodeMerchantOverrideService",
                new com.chengyinhub.business.service.impl.NodeMerchantOverrideServiceImpl(
                        org.mockito.Mockito.mock(com.chengyinhub.business.service.ICmsRegistrationMerchantService.class)));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicService", cmsTopicService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "perkLedgerService", perkLedgerService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", cmsRegistrationService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "shopDayProofService", shopDayProofService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "omsTicketService", mock(IOmsTicketService.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "pointsAwardService", pointsAwardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerProgressionService", playerProgressionService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "growthService", growthService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "xpBudgetService", xpBudgetService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "rewardDropService", rewardDropService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playerBadgeMapper", badgeMapper);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playAttemptLogMapper",
                mock(com.chengyinhub.business.mapper.PlayAttemptLogMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "memberCollectibleMapper", memberCollectibleMapper);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "smsCouponHistoryService", smsCouponHistoryService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionCouponRewardService",
                topicCompletionCouponRewardService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsMemberTemplateService", cmsMemberTemplateService);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "officialEventV2Service", officialEventV2Service);
        com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl completionAwards =
                new com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl();
        ReflectionTestUtils.setField(completionAwards, "topicService", cmsTopicService);
        ReflectionTestUtils.setField(completionAwards, "playerBadgeMapper", badgeMapper);
        ReflectionTestUtils.setField(completionAwards, "pointsAwardService", pointsAwardService);
        ReflectionTestUtils.setField(completionAwards, "playerProgressionService", playerProgressionService);
        ReflectionTestUtils.setField(completionAwards, "couponRewardService", topicCompletionCouponRewardService);
        ReflectionTestUtils.setField(completionAwards, "officialEventV2Service", officialEventV2Service);
        ReflectionTestUtils.setField(completionAwards, "xpBudgetService", xpBudgetService);
        ReflectionTestUtils.setField(completionAwards, "growthService", growthService);
        ReflectionTestUtils.setField(completionAwards, "umsMemberService",
                mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(completionAwards, "growthMissionProgressMapper",
                mock(com.chengyinhub.business.mapper.GrowthMissionProgressMapper.class));
        ReflectionTestUtils.setField(completionAwards, "xpAwardMapper",
                mock(com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper.class));
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicCompletionAwardService", completionAwards);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "topicRouteCompletionCoordinator", routeCompletionCoordinator);

        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(
                Collections.singletonList(nodeEntity()));
        // 默认：probe 空(done=0)、insert 成功
        mockProgressSelect(0); // done=0 by default (completed=false)
        when(progressService.insertPlayerNodeProgress(any())).thenReturn(1);
        when(cmsTopicService.selectCmsTopicById(any())).thenReturn(null);
        when(playerProgressionService.onNodeDone(anyLong())).thenReturn(new ArrayList<>());
        when(playerProgressionService.onTopicComplete(anyLong(), anyLong())).thenReturn(new ArrayList<>());
        when(growthService.addExp(anyLong(), anyInt(), anyString())).thenReturn(1);
    }

    /**
     * 设置 selectPlayerNodeProgressList 的行为：probe(含 nodeId) 固定返现有行模拟重复检测；
     * done 计数(不含 nodeId) 按给定数量返回。
     */
    private void mockProgressSelect(int doneCount) {
        List<PlayerNodeProgress> existing = Collections.singletonList(existingProgressRow());
        List<PlayerNodeProgress> doneRows = new ArrayList<>();
        for (int i = 0; i < doneCount; i++) {
            PlayerNodeProgress r = new PlayerNodeProgress();
            r.setId(1000L + i);
            r.setMemberId(MEMBER);
            r.setStatus(1); // done 计数只认 status==1(到店凭证 status=0 不是完成)
            doneRows.add(r);
        }
        doAnswer(inv -> {
            PlayerNodeProgress arg = inv.getArgument(0);
            if (arg != null && arg.getNodeId() != null) return existing; // probe query
            return doneRows; // done count query
        }).when(progressService).selectPlayerNodeProgressList(any(PlayerNodeProgress.class));
    }

    private CmsTopicNode nodeEntity() {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(NODE);
        n.setTopicId(TOPIC);
        return n;
    }

    private CmsTopicNode node() {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(NODE);
        n.setTopicId(TOPIC);
        return n;
    }

    private CmsTopic topicForCompletion() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setProductType(1);
        t.setName("测试主题");
        t.setFinishMedalName("通关勋章");
        return t;
    }

    /** ③ 自由收集主题：productType=2 → resolveMode 返回 FREE_COLLECT。 */
    private CmsTopic topicForExploreCompletion() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setProductType(2);
        t.setName("自由收集主题");
        t.setFinishMedalName("探索勋章");
        return t;
    }

    private PlayerNodeProgress existingProgressRow() {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setId(999L);
        p.setMemberId(MEMBER);
        p.setNodeId(NODE);
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeAdvance(Long memberId, CmsTopicNode node, Long actId, String picUrl) {
        return (Map<String, Object>) com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "advanceProgress", memberId, node, actId, picUrl);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokePuzzleAdvance(Long memberId, CmsTopicNode node, Long actId,
            int puzzleScore, String completionMode) {
        return (Map<String, Object>) com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "advanceProgress", memberId, node, actId, null, puzzleScore, completionMode);
    }

    // ==================== B5 体验层：mode1 经典定向不受影响（批 2-3 负控）====================

    /**
     * ★mode1 经典定向的「扫码即完成」语义必须原样保留：advanceProgress 撞重复键之后
     * <b>不得调用</b> markShopDayGameDone。
     *
     * <p>B5 收窄（2026-08-15）只对 mode2 开「答题推进」这条路。mode1 的行恒 status=1，
     * 万一被调到，条件更新虽然恒 0 行（第二道保护），但那已经是语义越界 ——
     * 第一道保护是 {@code resolveMode(...) == PlayMode.FREE_COLLECT} 的短路。
     * commit 自述「mode1 不受影响」，此前<b>没有任何一条测试验证它</b>。
     */
    @Test
    void classicOrienteeringNeverRoutesThroughTheShopDayGameDoneBranch() {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry for uk_progress"));
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        // 撞重复键 = 真重复，仍然不是首次完成 ⇒ 扫码即完成语义不变、不重复发奖
        assertEquals(false, result.get("firstTime"));
        verify(shopDayProofService, never()).markGameDone(anyLong(), anyLong(), anyLong(), anyLong());
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(playerProgressionService, never()).onNodeDone(anyLong());
    }

    /**
     * 正控：同一条路在 mode2 上<b>确实</b>会走到 markShopDayGameDone。
     *
     * <p>没有这一条，上面那条 {@code never()} 可能只是因为整条分支根本没接线而恒绿 ——
     * 那是本仓「门禁报 0 先问它扫的是什么范围」的同族假绿。
     */
    @Test
    void shopDayModeDoesRouteThroughTheGameDoneBranchSoTheNeverAssertionAboveIsMeaningful() {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry for uk_progress"));
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(shopDayTopic());
        when(shopDayProofService.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE)).thenReturn(true);

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        verify(shopDayProofService).markGameDone(MEMBER, ACTIVITY, TOPIC, NODE);
        assertEquals(true, result.get("firstTime"), "条件更新命中 = 本次首次答完，照常发奖");
    }

    /** mode2 但条件更新 0 行 = 真重复（已答过）：不得被算成又一次首次完成。 */
    @Test
    void shopDayReplayThatUpdatesNoRowIsStillNotAFirstCompletion() {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry for uk_progress"));
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(shopDayTopic());
        when(shopDayProofService.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE)).thenReturn(false);

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        assertEquals(false, result.get("firstTime"));
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(growthService, never()).addExp(anyLong(), anyInt(), anyString());
    }

    // ============ B5 体验层：③ 答题只发探索值与图鉴，不得继承 ① 的整块发奖 ============

    /**
     * ★ B5(2026-08-15 修订)授权的是「玩完发<b>探索值</b>,纯体验」。
     *
     * <p>而 mode2 的 game-done 分支复用了 {@code advanceProgress} 的整块 firstTime 发奖 ⇒
     * 探店日一路发出:抵扣积分(12L)、模板券/承接商家券、随机掉落(dropType=3 再发一张真金券)、
     * 经典节点徽章。其中抵扣积分尤其致命:裁决表 D2 关掉 ③ 票的积分抵扣,理由是
     * 「不关则 {@code G_cash = 69 × N_cash} 在代码上是假的」—— 买票侧关了、玩法侧发,
     * 这些积分可以花到 ①② 上,同一个经济口径被从另一头捅开。
     */
    @Test
    void shopDayGameCompletionGrantsExploreXpAndCollectibleOnly() {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry for uk_progress"));
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(shopDayTopic());
        when(shopDayProofService.markGameDone(MEMBER, ACTIVITY, TOPIC, NODE)).thenReturn(true);

        Map<String, Object> result = invokeAdvance(MEMBER, gameNodeWithCouponAndFragment(), ACTIVITY, null);

        assertEquals(true, result.get("firstTime"), "条件更新命中 = 本次首次答完");
        // 授权项:探索值 + 图鉴收集
        verify(growthService).addExp(eq(MEMBER), anyInt(), eq("节点探索"));
        verify(memberCollectibleMapper).insertMemberCollectible(any());
        // 未授权项:一个都不许发
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(smsCouponHistoryService, never()).grantCouponById(anyLong(), anyLong());
        verify(rewardDropService, never()).rollDrop(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(playerProgressionService, never()).onNodeDone(anyLong());
    }

    /**
     * 负控:同一块发奖在 ① 上必须<b>原样</b>发全套。
     *
     * <p>没有这一条,「mode2 不发」可以靠把整块删掉来假绿 —— 那会把经典定向的
     * 积分/券/掉落/徽章一起做掉,且没有任何测试会红。
     */
    @Test
    void classicOrienteeringStillGrantsTheFullNodeRewardSet() {
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());

        Map<String, Object> result = invokeAdvance(MEMBER, gameNodeWithCouponAndFragment(), ACTIVITY, null);

        assertEquals(true, result.get("firstTime"));
        verify(pointsAwardService).award(eq(MEMBER), eq(12L), eq(NODE), eq("节点打卡"));
        verify(smsCouponHistoryService).grantCouponById(MEMBER, 909L);
        verify(rewardDropService).rollDrop(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(playerProgressionService).onNodeDone(eq(MEMBER));
        verify(growthService).addExp(eq(MEMBER), anyInt(), eq("节点探索"));
    }

    @Test
    void classicPuzzleCompletionPersistsScoreWithoutChangingTheExistingXpReward()
    {
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());

        Map<String, Object> result = invokePuzzleAdvance(MEMBER, node(), ACTIVITY, 70, "SOLVED");

        ArgumentCaptor<PlayerNodeProgress> inserted = ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progressService).insertPlayerNodeProgress(inserted.capture());
        assertEquals(70, inserted.getValue().getPuzzleScore());
        assertEquals("SOLVED", inserted.getValue().getCompletionMode());
        assertEquals(70, result.get("puzzleScore"));
        assertEquals("SOLVED", result.get("completionMode"));
        verify(growthService).addExp(eq(MEMBER), anyInt(), eq("节点探索"));
    }

    /** 配了券模板 + 碎片文的节点：券与掉落的出口都打开，才验得出「③ 走不到那里」。 */
    /**
     * C1 选项 A:节点玩法只由设备自报成绩(反应毫秒/heldMs/方位…)撑着时,完成照常但只发探索值与徽章 ——
     * 篡改成绩不能换到积分、券或掉落。回包带 rewardCeiling,界面据此说明而不是自行补发。
     */
    @Test
    void selfReportedSensorCompletionCannotUnlockPointsCouponsOrDrops() {
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced =
                mock(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);
        when(advanced.rewardCappedToXpBadge(MEMBER, ACTIVITY, TOPIC, NODE)).thenReturn(true);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "advancedGameRuntimeService", advanced);

        Map<String, Object> result = invokeAdvance(MEMBER, gameNodeWithCouponAndFragment(), ACTIVITY, null);

        assertEquals(true, result.get("firstTime"));
        assertEquals("XP_BADGE_ONLY", result.get("rewardCeiling"));
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(smsCouponHistoryService, never()).grantCouponById(anyLong(), anyLong());
        verify(rewardDropService, never()).rollDrop(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        verify(playerProgressionService).onNodeDone(eq(MEMBER));
        verify(growthService).addExp(eq(MEMBER), anyInt(), eq("节点探索"));
    }

    /** 负控:可信完成(或旧主题没有自报段)照旧发全套,回包不出现 rewardCeiling。 */
    @Test
    void trustedAdvancedCompletionKeepsTheFullNodeRewardSet() {
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced =
                mock(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);
        when(advanced.rewardCappedToXpBadge(MEMBER, ACTIVITY, TOPIC, NODE)).thenReturn(false);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "advancedGameRuntimeService", advanced);

        Map<String, Object> result = invokeAdvance(MEMBER, gameNodeWithCouponAndFragment(), ACTIVITY, null);

        assertFalse(result.containsKey("rewardCeiling"));
        verify(pointsAwardService).award(eq(MEMBER), eq(12L), eq(NODE), eq("节点打卡"));
        verify(smsCouponHistoryService).grantCouponById(MEMBER, 909L);
        verify(rewardDropService).rollDrop(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    // ==================== #478-3:主题通关奖与节点同口径封顶 ====================

    private static final Long EARLIER_NODE = 402L;

    /** 通关链:两站都已完成(本站 + 先前一站),主题配了通关券与通关 XP 预算。 */
    private com.chengyinhub.business.service.IAdvancedGameRuntimeService completionWithTwoDoneNodes(
            boolean currentCapped, boolean earlierCapped) {
        List<PlayerNodeProgress> doneRows = new ArrayList<>();
        for (Long nodeId : new Long[]{EARLIER_NODE, NODE}) {
            PlayerNodeProgress r = new PlayerNodeProgress();
            r.setMemberId(MEMBER);
            r.setNodeId(nodeId);
            r.setStatus(1);
            doneRows.add(r);
        }
        List<PlayerNodeProgress> existing = Collections.singletonList(existingProgressRow());
        doAnswer(inv -> {
            PlayerNodeProgress arg = inv.getArgument(0);
            return arg != null && arg.getNodeId() != null ? existing : doneRows;
        }).when(progressService).selectPlayerNodeProgressList(any(PlayerNodeProgress.class));
        when(nodeService.selectCmsTopicNodeList(any())).thenReturn(java.util.Arrays.asList(node(), node()));
        CmsTopic topic = topicForCompletion();
        topic.setCompleteRewardCouponId(919L);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenReturn(1);
        when(xpBudgetService.computeBudget(TOPIC)).thenReturn(Collections.singletonMap("remain", 30));
        when(growthService.addCompletionExp(MEMBER, 30, TOPIC)).thenReturn(77L);
        com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper xpAwards =
                mock(com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper.class);
        when(xpAwards.insert(eq(MEMBER), eq(TOPIC), eq(30), any())).thenReturn(1);
        when(xpAwards.linkEarnedExp(MEMBER, TOPIC, 77L)).thenReturn(1);
        ReflectionTestUtils.setField(com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.getField(controller, "topicCompletionAwardService"),
                "xpAwardMapper", xpAwards);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced =
                mock(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);
        when(advanced.rewardCappedToXpBadge(MEMBER, ACTIVITY, TOPIC, NODE)).thenReturn(currentCapped);
        when(advanced.rewardCappedToXpBadge(MEMBER, ACTIVITY, TOPIC, EARLIER_NODE)).thenReturn(earlierCapped);
        when(advanced.anyNodeRewardCappedToXpBadge(anyLong(), anyLong(), anyLong(), any())).thenCallRealMethod();
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "advancedGameRuntimeService", advanced);
        return advanced;
    }

    private void assertThemeBonusCappedToXpBadgeMedal(Map<String, Object> result) {
        assertEquals(true, result.get("completed"));
        assertEquals("XP_BADGE_ONLY", result.get("topicRewardCeiling"));
        verify(pointsAwardService, never()).award(eq(MEMBER), eq(13L), anyLong(), anyString());
        verify(topicCompletionCouponRewardService, never()).grantOnce(anyLong(), anyLong(), anyLong());
        // 仍发:通关勋章、通关成长徽章、通关探索值
        verify(badgeMapper).insertPlayerBadge(argThat(b -> ("TOPIC_FINISH_" + TOPIC).equals(b.getBadgeCode())));
        verify(playerProgressionService).onTopicComplete(MEMBER, TOPIC);
        verify(growthService).addCompletionExp(MEMBER, 30, TOPIC);
    }

    /** 先前一站靠自报完成(本站可信):本站照发全套,但整部主题的通关积分与通关券不发。 */
    @Test
    void earlierSelfReportedNodeCapsTheThemeCompletionBonus() {
        completionWithTwoDoneNodes(false, true);

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        assertFalse(result.containsKey("rewardCeiling"), "本站可信,节点奖不封顶");
        verify(pointsAwardService).award(eq(MEMBER), eq(12L), eq(NODE), eq("节点打卡"));
        assertThemeBonusCappedToXpBadgeMedal(result);
    }

    /** 最后一站本身靠自报完成:节点奖与通关奖一起封顶。 */
    @Test
    void currentSelfReportedNodeCapsBothNodeAndThemeBonus() {
        completionWithTwoDoneNodes(true, false);

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        assertEquals("XP_BADGE_ONLY", result.get("rewardCeiling"));
        verify(pointsAwardService, never()).award(eq(MEMBER), eq(12L), anyLong(), anyString());
        assertThemeBonusCappedToXpBadgeMedal(result);
    }

    /** 负控:所有已完成节点都可信 ⇒ 通关积分、通关券照发,回包不带 topicRewardCeiling。 */
    @Test
    void allTrustedNodesKeepTheFullThemeCompletionBonus() {
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced = completionWithTwoDoneNodes(false, false);

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        assertEquals(true, result.get("completed"));
        assertFalse(result.containsKey("topicRewardCeiling"));
        verify(pointsAwardService).award(eq(MEMBER), eq(13L), eq(ACTIVITY), eq("通关主题"));
        verify(topicCompletionCouponRewardService).grantOnce(TOPIC, MEMBER, 919L);
        verify(growthService).addCompletionExp(MEMBER, 30, TOPIC);
        // 两站都被问过 —— 判据覆盖全部已完成节点,不是只看本站
        verify(advanced).rewardCappedToXpBadge(MEMBER, ACTIVITY, TOPIC, EARLIER_NODE);
    }

    /** ③ 自由收集的通关认商家核销(服务端可证),不因节点自报封顶 —— 与核销侧发奖同口径。 */
    @Test
    void freeCollectThemeCompletionStaysOnRedemptionEvidence() {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry for uk_progress"));
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topicForExploreCompletion());
        CmsRegistration exploreReg = new CmsRegistration();
        exploreReg.setId(777L);
        exploreReg.setMemberId(MEMBER);
        exploreReg.setOwnerType(2L);
        exploreReg.setOwnerId(ACTIVITY);
        exploreReg.setPurchaseKind(3);
        exploreReg.setRegistrationStatus(2);
        when(cmsRegistrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(exploreReg));
        when(perkLedgerService.isExploreCompleted(eq(777L), eq(TOPIC))).thenReturn(true);
        when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenReturn(1);
        com.chengyinhub.business.service.IAdvancedGameRuntimeService advanced =
                mock(com.chengyinhub.business.service.IAdvancedGameRuntimeService.class);
        when(advanced.rewardCappedToXpBadge(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(true);
        when(advanced.anyNodeRewardCappedToXpBadge(anyLong(), anyLong(), anyLong(), any())).thenReturn(true);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "advancedGameRuntimeService", advanced);

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        assertEquals(true, result.get("completed"));
        assertFalse(result.containsKey("topicRewardCeiling"));
        verify(pointsAwardService).award(eq(MEMBER), eq(13L), eq(ACTIVITY), eq("通关主题"));
    }

    private CmsTopicNode gameNodeWithCouponAndFragment() {
        CmsTopicNode n = node();
        n.setTemplateId(707L);
        n.setFragmentText("第一家店的碎片");
        com.chengyinhub.business.domain.CmsMemberTemplate tpl =
                new com.chengyinhub.business.domain.CmsMemberTemplate();
        tpl.setId(707L);
        tpl.setCouponId(909L);
        tpl.setMemberId(6006L);   // 出券商家(节点发券审计的归属)
        when(cmsMemberTemplateService.selectCmsMemberTemplateById(707L)).thenReturn(tpl);
        when(smsCouponHistoryService.grantCouponById(anyLong(), anyLong())).thenReturn(true);
        return n;
    }

    private CmsTopic linearTopic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setProductType(com.chengyinhub.common.constant.PlayMode.LINEAR_ORIENTEERING);
        t.setName("经典定向");
        return t;
    }

    private CmsTopic shopDayTopic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setProductType(com.chengyinhub.common.constant.PlayMode.FREE_COLLECT);
        t.setName("探店日");
        return t;
    }

    // ==================== 节点级幂等 ====================

    @Test
    void successfulInsert_triggersNodeRewards() {
        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);
        assertEquals(true, result.get("firstTime"));
        verify(pointsAwardService).award(eq(MEMBER), eq(12L), eq(NODE), eq("节点打卡"));
        verify(playerProgressionService).onNodeDone(eq(MEMBER));
        // 完成落地走 advanceOriginal(原局 sessionId 为空 = 非原局路径,与 advance 等价)
        verify(routeCompletionCoordinator).advanceOriginal(eq(MEMBER), eq(TOPIC), eq(ACTIVITY), eq(NODE),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void growthFailure_propagatesBeforeRouteAdvanceSoTransactionCanRollback() {
        RuntimeException failure = new RuntimeException("探索值发放失败");
        when(growthService.addExp(eq(MEMBER), anyInt(), eq("节点探索"))).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> invokeAdvance(MEMBER, node(), ACTIVITY, null));

        assertSame(failure, thrown);
        verify(routeCompletionCoordinator, never()).advance(anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), any());
    }

    @Test
    void rewardDropFailure_propagatesBeforeRouteAdvanceSoTransactionCanRollback() {
        RuntimeException failure = new RuntimeException("掉落发放失败");
        when(rewardDropService.rollDrop(anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> invokeAdvance(MEMBER, node(), ACTIVITY, null));

        assertSame(failure, thrown);
        verify(routeCompletionCoordinator, never()).advance(anyLong(), anyLong(), anyLong(), anyLong(),
                any(), any(), any(), any(), any());
    }

    @Test
    void duplicateInsertWithExistingRow_noNodeRewards() {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry for uk_progress"));
        mockProgressSelect(1); // done=1 → completed=true → completion block runs
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topicForCompletion());
        doThrow(new DuplicateKeyException("badge dup")).when(badgeMapper)
                .insertPlayerBadge(any(PlayerBadge.class));
        when(badgeMapper.selectOne(anyLong(), anyString(), anyLong())).thenReturn(new PlayerBadge());

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        assertEquals(false, result.get("firstTime"));
        verify(pointsAwardService, never()).award(anyLong(), anyLong(), anyLong(), anyString());
        verify(playerProgressionService, never()).onNodeDone(anyLong());
        verify(growthService, never()).addExp(anyLong(), anyInt(), anyString());
    }

    // ==================== 第二轮拍板第 6 条:游戏码答题的券发给玩家本人 ====================

    /** 玩家扫商家游戏码完成节点:模板券落玩家券包,审计操作人=玩家、归属=出券商家。 */
    @Test
    void nodeTemplateCouponGoesToPlayerAndAuditsPlayerAsActorMerchantAsOwner() {
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());
        CmsTopicNode n = gameNodeWithCouponAndFragment();
        cmsMemberTemplateService.selectCmsMemberTemplateById(707L).setMemberId(6006L);

        Map<String, Object> result = invokeAdvance(MEMBER, n, ACTIVITY, null);

        assertEquals(true, result.get("firstTime"));
        verify(smsCouponHistoryService).grantCouponById(MEMBER, 909L);
        verify(smsCouponHistoryService, never()).grantCouponById(eq(6006L), anyLong());
        ArgumentCaptor<com.chengyinhub.business.domain.BusinessAuditEvent> audit =
                ArgumentCaptor.forClass(com.chengyinhub.business.domain.BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("NODE_COUPON_GRANT", audit.getValue().getAction());
        assertEquals(MEMBER, audit.getValue().getActorMemberId());
        assertEquals("MEMBER", audit.getValue().getActorType());
        assertEquals(Long.valueOf(6006L), audit.getValue().getOwnerMemberId());
        assertEquals(Long.valueOf(909L), audit.getValue().getTargetId());
        assertEquals(ACTIVITY, audit.getValue().getRelationId());
    }

    /** 同一节点再答一次/再扫一次(已完成):不重复发券,也不重复记审计。 */
    @Test
    void repeatedCompletionOfSameNodeGrantsNoSecondCoupon() {
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());
        CmsTopicNode n = gameNodeWithCouponAndFragment();
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry for uk_progress"));

        Map<String, Object> result = invokeAdvance(MEMBER, n, ACTIVITY, null);

        assertEquals(false, result.get("firstTime"));
        verify(smsCouponHistoryService, never()).grantCouponById(anyLong(), anyLong());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    /** 审计写不进必须抛,让整个完成请求回滚:发了券却查不到因为哪一站发给谁 = 没留痕。 */
    @Test
    void nodeCouponAuditFailureRollsBackInsteadOfSilentGrant() {
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());
        CmsTopicNode n = gameNodeWithCouponAndFragment();
        cmsMemberTemplateService.selectCmsMemberTemplateById(707L).setMemberId(6006L);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> invokeAdvance(MEMBER, n, ACTIVITY, null));
        assertEquals("节点发券审计写入失败", thrown.getMessage());
    }

    /**
     * 总控复查裁定:模板主、承接商家、节点商家都查不出(数据损坏)⇒ 节点照常完成、这张券不发、不写审计、不抛。
     * 负控对照是上面 nodeCouponAuditFailureRollsBackInsteadOfSilentGrant:有归属时审计写失败仍整体回滚。
     */
    @Test
    void nodeCouponWithoutAnyOwnerSkipsCouponButNodeStillCompletes() {
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());
        CmsTopicNode n = gameNodeWithCouponAndFragment();
        cmsMemberTemplateService.selectCmsMemberTemplateById(707L).setMemberId(null);

        Map<String, Object> result = invokeAdvance(MEMBER, n, ACTIVITY, null);

        assertEquals(true, result.get("firstTime"), "节点完成不回滚");
        verify(pointsAwardService).award(eq(MEMBER), eq(12L), eq(NODE), eq("节点打卡"));
        verify(smsCouponHistoryService, never()).grantCouponById(anyLong(), anyLong());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    /**
     * release-0917 集成:fix-be-0917 的 C-30 跳过(券已失效 3 / 已停发 4 → isCouponNotIssuable)与
     * fix-club-0917 的归属商家审计同时生效。归属查得到、但券不可发放 ⇒ 节点照常完成、不发券、不写
     * NODE_COUPON_GRANT 审计(审计只记真发出去的券)、不抛。负控对照:上面
     * nodeTemplateCouponGoesToPlayerAndAuditsPlayerAsActorMerchantAsOwner(可发放 ⇒ 发给玩家 + 审计归属商家)。
     */
    @Test
    void notIssuableNodeCouponWithKnownOwnerSkipsGrantAndAuditButNodeStillCompletes() {
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(linearTopic());
        CmsTopicNode n = gameNodeWithCouponAndFragment();
        cmsMemberTemplateService.selectCmsMemberTemplateById(707L).setMemberId(6006L);
        when(smsCouponHistoryService.isCouponNotIssuable(909L)).thenReturn(true);

        Map<String, Object> result = invokeAdvance(MEMBER, n, ACTIVITY, null);

        assertEquals(true, result.get("firstTime"), "节点完成不回滚");
        verify(pointsAwardService).award(eq(MEMBER), eq(12L), eq(NODE), eq("节点打卡"));
        verify(smsCouponHistoryService).isCouponNotIssuable(909L);
        verify(smsCouponHistoryService, never()).grantCouponById(anyLong(), anyLong());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void nonDuplicateException_rethrowsNotFirstTime() {
        RuntimeException dbError = new RuntimeException("Connection refused");
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(dbError);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> invokeAdvance(MEMBER, node(), ACTIVITY, null));
        assertSame(dbError, thrown);
    }

    @Test
    void duplicateInsertButRowNotFound_rethrows() {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry"));
        // 回查四列空 → rethrow
        when(progressService.selectPlayerNodeProgressList(any())).thenReturn(new ArrayList<>());

        assertThrows(DuplicateKeyException.class,
                () -> invokeAdvance(MEMBER, node(), ACTIVITY, null));
    }

    // ==================== 通关级幂等 ====================

    @Test
    void duplicateInsertCompletedTrue_noCompletionRewards() {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry for uk_progress"));
        mockProgressSelect(1); // done=1
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topicForCompletion());
        doThrow(new DuplicateKeyException("Duplicate badge")).when(badgeMapper)
                .insertPlayerBadge(any(PlayerBadge.class));
        when(badgeMapper.selectOne(anyLong(), anyString(), anyLong())).thenReturn(new PlayerBadge());

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        assertEquals(false, result.get("firstTime"));
        assertEquals(true, result.get("completed"));
        verify(pointsAwardService, never()).award(eq(MEMBER), eq(12L), anyLong(), anyString());
        verify(pointsAwardService, never()).award(eq(MEMBER), eq(13L), anyLong(), anyString());
        verify(playerProgressionService, never()).onTopicComplete(anyLong(), anyLong());
        verify(topicCompletionCouponRewardService, never()).grantOnce(anyLong(), anyLong(), anyLong());
        verify(growthService, never()).addExp(anyLong(), anyInt(), anyString());
        verify(officialEventV2Service, never()).recordThemeCompletion(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void successfulInsertWithCompletion_triggersAllRewards() {
        mockProgressSelect(1); // done=1 → completed=true
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topicForCompletion());
        when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenReturn(1);

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        assertEquals(true, result.get("firstTime"));
        assertEquals(true, result.get("completed"));
        verify(pointsAwardService).award(eq(MEMBER), eq(12L), eq(NODE), eq("节点打卡"));
        verify(pointsAwardService).award(eq(MEMBER), eq(13L), eq(ACTIVITY), eq("通关主题"));
        verify(playerProgressionService).onTopicComplete(eq(MEMBER), eq(TOPIC));
    }

    /** badge insert 返回 0 ⇒ 异常失败，@Transactional 回滚保证重试可重入。 */
    @Test
    void badgeRowsZero_throwsAndNoCompletionRewards() {
        mockProgressSelect(1);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topicForCompletion());
        when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenReturn(0);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> invokeAdvance(MEMBER, node(), ACTIVITY, null));
        assertTrue(thrown.getMessage().contains("rows=0"));
        // 通关副作用不应执行
        verify(pointsAwardService, never()).award(eq(MEMBER), eq(13L), anyLong(), anyString());
        verify(playerProgressionService, never()).onTopicComplete(anyLong(), anyLong());
    }

    @Test
    void pointsAwardCompletionException_propagates() {
        mockProgressSelect(1);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topicForCompletion());
        when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenReturn(1);
        doThrow(new RuntimeException("积分发放失败")).when(pointsAwardService)
                .award(eq(MEMBER), eq(13L), anyLong(), anyString());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> invokeAdvance(MEMBER, node(), ACTIVITY, null));
        assertEquals("积分发放失败", thrown.getMessage());
    }

    @Test
    void topicNullInCompletionBlock_throws() {
        mockProgressSelect(1);
        // resolveMode 返回 topic，completion 块直接调用返回 null
        when(cmsTopicService.selectCmsTopicById(TOPIC))
                .thenReturn(topicForCompletion()).thenReturn(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> invokeAdvance(MEMBER, node(), ACTIVITY, null));
        assertTrue(thrown.getMessage().contains("主题不存在"));
    }

    /**
     * ③ 场景：firstTime=false(节点重复) 但勋章尚不存在 ⇒ 通关奖励首次发放。
     * 走真实 ③ 路径：productType=2 → resolveMode→FREE_COLLECT→isTopicCompletedFor
     * → perkLedgerService.isExploreCompleted=true。
     */
    @Test
    void firstTimeFalseButBadgeAbsent_triggersFirstCompletion() {
        when(progressService.insertPlayerNodeProgress(any())).thenThrow(
                new DuplicateKeyException("Duplicate entry for uk_progress"));
        // ③ 走 perkLedger 核销口径，不靠 done>=total
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(topicForExploreCompletion());

        // exploreRegistration: 返回 EXPLORE_PASS 报名单
        CmsRegistration exploreReg = new CmsRegistration();
        exploreReg.setId(777L);
        exploreReg.setMemberId(MEMBER);
        exploreReg.setOwnerType(2L);
        exploreReg.setOwnerId(ACTIVITY);
        exploreReg.setPurchaseKind(3); // PurchaseKind.EXPLORE_PASS
        exploreReg.setRegistrationStatus(2);
        when(cmsRegistrationService.selectCmsRegistrationList(any()))
                .thenReturn(Collections.singletonList(exploreReg));
        when(perkLedgerService.isExploreCompleted(eq(777L), eq(TOPIC))).thenReturn(true);

        when(badgeMapper.insertPlayerBadge(any(PlayerBadge.class))).thenReturn(1);

        Map<String, Object> result = invokeAdvance(MEMBER, node(), ACTIVITY, null);

        assertEquals(false, result.get("firstTime"), "节点重复,firstTime=false");
        assertEquals(true, result.get("completed"));
        verify(pointsAwardService, never()).award(eq(MEMBER), eq(12L), anyLong(), anyString());
        verify(playerProgressionService, never()).onNodeDone(anyLong());
        verify(pointsAwardService).award(eq(MEMBER), eq(13L), eq(ACTIVITY), eq("通关主题"));
        verify(playerProgressionService).onTopicComplete(eq(MEMBER), eq(TOPIC));
    }

    @Test
    void shopDayCrowdedThresholdFallsBackToFourAndUsesTwoServiceRounds() {
        assertEquals(4, com.chengyinhub.web.service.play.PlayNodeQueryApplication.shopDayCrowdedThreshold(null));
        assertEquals(4, com.chengyinhub.web.service.play.PlayNodeQueryApplication.shopDayCrowdedThreshold(2));
        assertEquals(6, com.chengyinhub.web.service.play.PlayNodeQueryApplication.shopDayCrowdedThreshold(3));
        assertEquals(8d, com.chengyinhub.web.service.play.PlayNodeQueryApplication.shopDayServiceCapacityPerHour(4, 30));
        assertEquals(23d, com.chengyinhub.web.service.play.PlayNodeQueryApplication.shopDayExpectedWaitMinutes(3, 8d));
        assertNull(com.chengyinhub.web.service.play.PlayNodeQueryApplication.shopDayServiceCapacityPerHour(4, 0));
    }
}
