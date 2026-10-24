package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayPredictBet;
import com.chengyinhub.business.domain.PlayPredictResult;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.PlayPredictMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.web.controller.api.ApiRoamController;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
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
 * COUPON-RPT-4:券已失效(3)/已停发(4)时,宾果格、竞猜结算、漫游掉落三条非回滚发券链路
 * 必须跳过发券(不算失败、不误导人工补发);只有券不存在/库存耗尽才是真失败。
 *
 * <p>节点/彩蛋链路覆盖在 {@code ApiPlayProgressCouponGrantFailureTest},据点核销在
 * {@code ApiVerifyControllerTest}。四条链路共用 {@code isCouponNotIssuable} 服务闸门。
 */
class CouponNotIssuableSkipContractTest {

    private static final Long MEMBER = 501L;
    private static final Long DROP_LOG = 9_701L;
    private static final Long COUPON = 77L;
    private static final Long NODE = 29L;
    private static final Long TEMPLATE = 39L;
    private static final Date PLAY_DAY = new java.sql.Date(1_775_000_000_000L);

    // ── 宾果格子 ──

    private PlayBingoServiceImpl bingoWith(ISmsCouponHistoryService couponService) {
        PlayBingoServiceImpl service = new PlayBingoServiceImpl();
        ReflectionTestUtils.setField(service, "couponHistoryService", couponService);
        return service;
    }

    private void grantCell(PlayBingoServiceImpl service, Long couponId) {
        String rule = "{\"bingo\":{\"enabled\":true,\"cells\":[{\"couponId\":" + couponId + "},"
                + "{}, {}, {}, {}, {}, {}, {}, {}]}}";
        // 位置 0 的奖励位 = 1 << (3 + 0) = 8(reward_mask 第 3 位起是九格奖)
        ReflectionTestUtils.invokeMethod(service, "grantCellRewards", MEMBER, 1 << 3, rule);
    }

    @Test
    void bingoCellSkipsDisabledOrStoppedCouponWithoutManualTopUpNoise() {
        ISmsCouponHistoryService couponService = mock(ISmsCouponHistoryService.class);
        when(couponService.isCouponNotIssuable(COUPON)).thenReturn(true);

        grantCell(bingoWith(couponService), COUPON);

        verify(couponService, never()).grantCouponById(anyLong(), anyLong());
    }

    /** 负控:券还在、只是库存耗尽 → 仍必须真的尝试发券(false 才留「需人工补发」)。 */
    @Test
    void bingoCellStillAttemptsGrantWhenCouponIssuable() {
        ISmsCouponHistoryService couponService = mock(ISmsCouponHistoryService.class);
        when(couponService.isCouponNotIssuable(COUPON)).thenReturn(false);
        when(couponService.grantCouponById(MEMBER, COUPON)).thenReturn(false);

        grantCell(bingoWith(couponService), COUPON);

        verify(couponService).grantCouponById(MEMBER, COUPON);
    }

    @Test
    void bingoConfigurationDeadlockStopsBeforeCardOrCouponEffects() {
        var topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        var cards = mock(com.chengyinhub.business.mapper.PlayBingoCardMapper.class);
        var coupons = mock(ISmsCouponHistoryService.class);
        PlayBingoServiceImpl service = bingoWith(coupons);
        ReflectionTestUtils.setField(service, "topicMapper", topics);
        ReflectionTestUtils.setField(service, "cardMapper", cards);
        var failure = new org.springframework.dao.CannotAcquireLockException("original-transaction-aborted");
        when(topics.selectCmsTopicById(901L)).thenThrow(failure);

        assertSame(failure, assertThrows(org.springframework.dao.CannotAcquireLockException.class,
                () -> service.onNodeDone(MEMBER, 901L)));
        verifyNoInteractions(cards, coupons);
    }

    @Test
    void bingoCouponEligibilityLockFailureReachesTheOuterTransaction() {
        var coupons = mock(ISmsCouponHistoryService.class);
        var failure = new org.springframework.dao.CannotAcquireLockException("original-coupon-lock");
        when(coupons.isCouponNotIssuable(COUPON)).thenThrow(failure);

        assertSame(failure, assertThrows(org.springframework.dao.CannotAcquireLockException.class,
                () -> grantCell(bingoWith(coupons), COUPON)));
        verify(coupons, never()).grantCouponById(anyLong(), anyLong());
    }

    @Test
    void bingoCouponGrantTimeoutReachesTheOuterTransaction() {
        var coupons = mock(ISmsCouponHistoryService.class);
        var failure = new org.springframework.dao.QueryTimeoutException("original-coupon-timeout");
        when(coupons.grantCouponById(MEMBER, COUPON)).thenThrow(failure);

        assertSame(failure, assertThrows(org.springframework.dao.QueryTimeoutException.class,
                () -> grantCell(bingoWith(coupons), COUPON)));
        verify(coupons).grantCouponById(MEMBER, COUPON);
    }

    @Test
    void bingoOrdinaryCouponFailureKeepsTheExistingBestEffortBehaviour() {
        var coupons = mock(ISmsCouponHistoryService.class);
        when(coupons.isCouponNotIssuable(COUPON)).thenThrow(new IllegalStateException("not-a-database-abort"));
        assertDoesNotThrow(() -> grantCell(bingoWith(coupons), COUPON));
        verify(coupons, never()).grantCouponById(anyLong(), anyLong());
    }

    // ── 竞猜结算 ──

    private PlayPredictServiceImpl predictWith(ISmsCouponHistoryService couponService) {
        PlayPredictServiceImpl service = new PlayPredictServiceImpl();
        PlayPredictMapper predictMapper = mock(PlayPredictMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        IGrowthService growthService = mock(IGrowthService.class);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTemplateId(TEMPLATE);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);

        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE);
        template.setAdvancedConfigJson("{\"predict\":{\"enabled\":true,\"question\":\"q\","
                + "\"options\":[{\"key\":\"a\"},{\"key\":\"b\"}]}}");
        template.setRewardRulesJson("[{\"outcomeCode\":\"PREDICT_WON\",\"xp\":30,\"couponId\":" + COUPON + "}]");
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE)).thenReturn(template);

        Date day = PLAY_DAY;
        when(predictMapper.selectResult(NODE, day)).thenReturn(new PlayPredictResult());
        when(predictMapper.settleRound(eq(NODE), eq(day), eq("a"),
                eq(PlayPredictResult.SETTLE_DONE), any(), any(Date.class))).thenReturn(1);

        PlayPredictBet bet = new PlayPredictBet();
        bet.setId(900L);
        bet.setNodeId(NODE);
        bet.setMemberId(MEMBER);
        bet.setOptionKey("a");
        bet.setRewardStatus(PlayPredictBet.REWARD_PENDING);
        when(predictMapper.selectBetsForRound(NODE, day)).thenReturn(Collections.singletonList(bet));
        when(predictMapper.markBetSettled(eq(900L), anyInt(), any(Date.class))).thenReturn(1);

        ReflectionTestUtils.setField(service, "predictMapper", predictMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "growthService", growthService);
        ReflectionTestUtils.setField(service, "couponHistoryService", couponService);
        return service;
    }

    private Map<String, Object> settlePredict(PlayPredictServiceImpl service) {
        return service.settle(NODE, PLAY_DAY, "a", 1L);
    }

    @Test
    void predictSettleSkipsDisabledOrStoppedCoupon() {
        ISmsCouponHistoryService couponService = mock(ISmsCouponHistoryService.class);
        when(couponService.isCouponNotIssuable(COUPON)).thenReturn(true);

        Map<String, Object> summary = settlePredict(predictWith(couponService));

        assertEquals(0, summary.get("couponsGranted"), "不可发放的券不该计入已发券数");
        verify(couponService, never()).grantCouponById(anyLong(), anyLong());
    }

    /** 负控:券可发放时仍要发;这保证跳过只对 status 3/4 生效。 */
    @Test
    void predictSettleStillGrantsIssuableCoupon() {
        ISmsCouponHistoryService couponService = mock(ISmsCouponHistoryService.class);
        when(couponService.isCouponNotIssuable(COUPON)).thenReturn(false);
        when(couponService.grantCouponById(MEMBER, COUPON)).thenReturn(true);

        Map<String, Object> summary = settlePredict(predictWith(couponService));

        assertEquals(1, summary.get("couponsGranted"));
        verify(couponService).grantCouponById(MEMBER, COUPON);
    }

    // ── 漫游掉落 ──

    /** C包：漫游掉落券经 RoamRewardDeliveryService 按 A 的原来源合同交付；这里接真实交付服务，仅 mapper/券服务为夹具。 */
    private ApiRoamController roamWith(ISmsCouponHistoryService couponService) {
        ApiRoamController controller = new ApiRoamController();
        ReflectionTestUtils.setField(controller, "smsCouponHistoryService", couponService);
        // A包 grantCouponBySource 合同：未发出返回 null(Mockito 对 Long 默认 0L 会被当成无效收据)；用例内显式 stub 优先。
        if (org.mockito.Mockito.mockingDetails(couponService).getStubbings().stream()
                .noneMatch(st -> st.getInvocation().getMethod().getName().equals("grantCouponBySource"))) {
            when(couponService.grantCouponBySource(anyLong(), anyLong(), anyString(), anyLong())).thenReturn(null);
        }
        com.chengyinhub.business.mapper.RoamRewardDeliveryMapper deliveries =
                mock(com.chengyinhub.business.mapper.RoamRewardDeliveryMapper.class);
        Map<String, Object> original = new java.util.HashMap<>();
        original.put("dropType", 3); original.put("paramJson", "{\"couponTemplateId\":" + COUPON + "}");
        when(deliveries.selectOriginalRewardDrop(DROP_LOG, MEMBER)).thenReturn(original);
        Map<String, Object> intent = new java.util.HashMap<>();
        intent.put("memberId", MEMBER); intent.put("couponId", COUPON); intent.put("state", "DUE"); intent.put("rewardKind", "COUPON");
        when(deliveries.selectIntent(DROP_LOG)).thenReturn(intent);
        org.springframework.transaction.PlatformTransactionManager tx = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        ReflectionTestUtils.setField(controller, "roamRewardDeliveryService", new RoamRewardDeliveryService(deliveries, couponService, tx,
                mock(com.chengyinhub.business.service.IRewardDropService.class), mock(IGrowthService.class)));
        return controller;
    }

    private void applyDrop(ApiRoamController controller, Long couponId) {
        Map<String, Object> drop = new java.util.LinkedHashMap<>();
        drop.put("dropType", 3);
        drop.put("couponTemplateId", couponId);
        drop.put("dropLogId", DROP_LOG); // A包 rollDrop 真实回包带原掉落ID
        ReflectionTestUtils.invokeMethod(controller, "applyRoamDrop", MEMBER, drop);
    }

    @Test
    void roamDropSkipsDisabledOrStoppedCoupon() {
        ISmsCouponHistoryService couponService = mock(ISmsCouponHistoryService.class);
        when(couponService.isCouponNotIssuable(COUPON)).thenReturn(true);

        applyDrop(roamWith(couponService), COUPON);

        verify(couponService, never()).grantCouponById(anyLong(), anyLong());
        verify(couponService).grantCouponBySource(MEMBER, COUPON, "reward_drop", DROP_LOG); // 原来源无收据 → 停发即跳过
    }

    /** 负控:券不存在/库存耗尽时仍必须尝试发放并原样暴露失败。 */
    @Test
    void roamDropStillAttemptsGrantWhenCouponIssuableButEmpty() {
        ISmsCouponHistoryService couponService = mock(ISmsCouponHistoryService.class);
        when(couponService.isCouponNotIssuable(COUPON)).thenReturn(false);
        when(couponService.grantCouponBySource(MEMBER, COUPON, "reward_drop", DROP_LOG)).thenReturn(null);

        applyDrop(roamWith(couponService), COUPON);

        // C包改经原来源交付：可发但库存空时仍必须真正尝试发放(原来源合同)，失败保持待交付。
        verify(couponService).grantCouponBySource(MEMBER, COUPON, "reward_drop", DROP_LOG);
    }
}
