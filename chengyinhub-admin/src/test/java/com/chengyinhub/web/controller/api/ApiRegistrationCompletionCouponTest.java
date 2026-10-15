package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.RedemptionMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IExploreCollectibleService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.ITopicCompletionAwardService;
import com.chengyinhub.business.service.ITopicCompletionCouponRewardService;
import com.chengyinhub.business.service.IXpBudgetService;
import com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2-4 公共核销入口契约:前三店不终结,第四店的票侧完成事件恰好发奖一次。
 *
 * <p>这里从 {@link ApiRegistrationController#scanQrCode(String, String)} 进入,不直接调用私有收尾方法。
 * 通关判定只观察 {@link IPerkLedgerService#isExploreCompleted(Long, Long)} 的票侧结果;
 * badge 的唯一键是勋章/积分/成长/券/evidence/XP 的共同原子闸。
 */
class ApiRegistrationCompletionCouponTest
{
    private static final Long MERCHANT = 20L;
    private static final Long PLAYER = 21L;
    private static final Long REGISTRATION = 22L;
    private static final Long ACTIVITY = 23L;
    private static final Long TOPIC = 24L;
    private static final Long COUPON = 25L;
    private static final String CODE = "SHOP-DAY-CODE";

    private ICmsRegistrationService registrationService;
    private IPerkLedgerService perkLedgerService;
    private IPointsAwardService pointsAwardService;
    private IGrowthService growthService;
    private RedemptionMapper redemptionMapper;
    private IExploreCollectibleService collectibleService;
    private PlayerBadgeMapper badgeMapper;
    private IPlayerProgressionService progressionService;
    private ITopicCompletionCouponRewardService couponRewardService;
    private IOfficialEventV2Service officialEventService;
    private IXpBudgetService xpBudgetService;
    private IMerchantAccessService merchantAccessService;
    private ApiRegistrationController controller;
    private CmsRegistration registration;

    @BeforeEach
    void setUp()
    {
        registrationService = mock(ICmsRegistrationService.class);
        perkLedgerService = mock(IPerkLedgerService.class);
        pointsAwardService = mock(IPointsAwardService.class);
        growthService = mock(IGrowthService.class);
        redemptionMapper = mock(RedemptionMapper.class);
        collectibleService = mock(IExploreCollectibleService.class);
        badgeMapper = mock(PlayerBadgeMapper.class);
        progressionService = mock(IPlayerProgressionService.class);
        couponRewardService = mock(ITopicCompletionCouponRewardService.class);
        officialEventService = mock(IOfficialEventV2Service.class);
        xpBudgetService = mock(IXpBudgetService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        ICmsTopicService topicService = mock(ICmsTopicService.class);

        TopicCompletionAwardServiceImpl completionAwardService = new TopicCompletionAwardServiceImpl();
        ReflectionTestUtils.setField(completionAwardService, "topicService", topicService);
        ReflectionTestUtils.setField(completionAwardService, "playerBadgeMapper", badgeMapper);
        ReflectionTestUtils.setField(completionAwardService, "pointsAwardService", pointsAwardService);
        ReflectionTestUtils.setField(completionAwardService, "playerProgressionService", progressionService);
        ReflectionTestUtils.setField(completionAwardService, "couponRewardService", couponRewardService);
        ReflectionTestUtils.setField(completionAwardService, "officialEventV2Service", officialEventService);
        ReflectionTestUtils.setField(completionAwardService, "xpBudgetService", xpBudgetService);
        ReflectionTestUtils.setField(completionAwardService, "growthService", growthService);
        ReflectionTestUtils.setField(completionAwardService, "umsMemberService",
                mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(completionAwardService, "growthMissionProgressMapper",
                mock(com.chengyinhub.business.mapper.GrowthMissionProgressMapper.class));
        ReflectionTestUtils.setField(completionAwardService, "xpAwardMapper",
                mock(com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper.class));

        controller = spy(new ApiRegistrationController());
        doReturn(MERCHANT).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "registrationService", registrationService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(controller, "topicService", topicService);
        ReflectionTestUtils.setField(controller, "perkLedgerService", perkLedgerService);
        ReflectionTestUtils.setField(controller, "pointsAwardService", pointsAwardService);
        ReflectionTestUtils.setField(controller, "growthService", growthService);
        ReflectionTestUtils.setField(controller, "redemptionMapper", redemptionMapper);
        ReflectionTestUtils.setField(controller, "topicCompletionAwardService", completionAwardService);
        ReflectionTestUtils.setField(controller, "exploreCollectibleService", collectibleService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        when(merchantAccessService.require(MERCHANT, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MERCHANT, 11L, MERCHANT,
                        MerchantRoleCode.MERCHANT_OWNER));

        registration = new CmsRegistration();
        registration.setId(REGISTRATION);
        registration.setMemberId(PLAYER);
        registration.setOwnerType(2L);
        registration.setOwnerId(ACTIVITY);
        registration.setPaymentStatus(2);
        registration.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(registrationService.selectByverificationCode(CODE)).thenReturn(registration);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setEndDate(new java.util.Date(System.currentTimeMillis() + 86400000L));
        activity.setMemberId(99L);
        ICmsActivityService activityService = (ICmsActivityService) ReflectionTestUtils.getField(
                controller, "cmsActivityService");
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setName("四店探店日");
        topic.setFinishMedalName("四店通关");
        topic.setCompleteRewardCouponId(COUPON);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(progressionService.onTopicComplete(PLAYER, TOPIC)).thenReturn(Collections.emptyList());
        when(xpBudgetService.computeBudget(TOPIC)).thenReturn(Collections.<String, Object>singletonMap("remain", 40));
    }

    @Test
    void controllerNeverRunsCompletionSideEffectsOutsideTransactionalService()
    {
        when(perkLedgerService.resolveRedeemableChapters(REGISTRATION, TOPIC, MERCHANT))
                .thenReturn(Collections.singletonList(31L), Collections.singletonList(32L),
                        Collections.singletonList(33L), Collections.singletonList(34L),
                        Collections.singletonList(34L));
        when(registrationService.redeemExploreChapterAndRecord(eq(registration), anyLong(), eq(TOPIC),
                eq(MERCHANT), eq(MERCHANT), any())).thenReturn(
                        new ExploreChapterRedemptionResult(101L, 0),
                        new ExploreChapterRedemptionResult(102L, 0),
                        new ExploreChapterRedemptionResult(103L, 0),
                        new ExploreChapterRedemptionResult(104L, 40),
                        new ExploreChapterRedemptionResult(104L, 0));
        when(perkLedgerService.isExploreCompleted(REGISTRATION, TOPIC))
                .thenReturn(false, false, false, true, true);

        Date completionTime = new Date(1_755_244_320_000L);
        Redemption completion = new Redemption();
        completion.setId(104L);
        completion.setCreateTime(completionTime);
        when(redemptionMapper.selectById(104L)).thenReturn(completion);

        PlayerBadge storedBadge = new PlayerBadge();
        storedBadge.setId(501L);
        storedBadge.setMemberId(PLAYER);
        storedBadge.setBadgeCode("TOPIC_FINISH_" + TOPIC);
        doAnswer(invocation -> {
            PlayerBadge inserted = invocation.getArgument(0);
            inserted.setId(storedBadge.getId());
            return 1;
        }).doThrow(new DuplicateKeyException("uk_member_badge"))
                .when(badgeMapper).insertPlayerBadge(any(PlayerBadge.class));
        when(badgeMapper.selectOne(PLAYER, "TOPIC_FINISH_" + TOPIC, TOPIC)).thenReturn(storedBadge);

        for (int i = 0; i < 3; i++) {
            assertEquals(200, controller.scanQrCode("activity", CODE).get("code"));
            verify(badgeMapper, never()).insertPlayerBadge(any(PlayerBadge.class));
            verify(pointsAwardService, never()).award(eq(PLAYER), eq(13L), anyLong(), any());
        }

        assertEquals(200, controller.scanQrCode("activity", CODE).get("code"));
        verify(pointsAwardService, never()).award(eq(PLAYER), eq(13L), anyLong(), any());
        verify(progressionService, never()).onTopicComplete(PLAYER, TOPIC);
        verify(couponRewardService, never()).grantOnce(TOPIC, PLAYER, COUPON);

        assertEquals(200, controller.scanQrCode("activity", CODE).get("code"));
        verify(badgeMapper, never()).insertPlayerBadge(any(PlayerBadge.class));
        verify(collectibleService).record(PLAYER, TOPIC, 31L, MERCHANT);
        verify(collectibleService).record(PLAYER, TOPIC, 32L, MERCHANT);
        verify(collectibleService).record(PLAYER, TOPIC, 33L, MERCHANT);
        verify(collectibleService, times(2)).record(PLAYER, TOPIC, 34L, MERCHANT);
        verify(pointsAwardService, never()).award(eq(PLAYER), eq(13L), anyLong(), any());
        verify(progressionService, never()).onTopicComplete(PLAYER, TOPIC);
        verify(couponRewardService, never()).grantOnce(TOPIC, PLAYER, COUPON);
        verify(officialEventService, never()).recordThemeCompletion(anyLong(), anyLong(), any(), any());
        verify(growthService, never()).addExp(anyLong(), anyInt(), any());
        verify(perkLedgerService, never()).isExploreCompleted(REGISTRATION, TOPIC);
    }

    @Test
    void refundedTicketCannotReachEntitlementCompletionOrAwards()
    {
        registration.setPaymentStatus(4);

        AjaxResult result = controller.scanQrCode("activity", CODE);

        assertEquals(500, result.get("code"));
        assertTrue(String.valueOf(result.get("msg")).contains("退款"));
        verify(perkLedgerService, never()).resolveRedeemableChapters(anyLong(), anyLong(), anyLong());
        verify(perkLedgerService, never()).isExploreCompleted(anyLong(), anyLong());
        verify(badgeMapper, never()).insertPlayerBadge(any(PlayerBadge.class));
    }

    @Test
    void missingPendingEntitlementCannotRedeemOrTriggerAwards()
    {
        when(perkLedgerService.resolveRedeemableChapters(REGISTRATION, TOPIC, MERCHANT))
                .thenReturn(Collections.emptyList());

        AjaxResult result = controller.scanQrCode("activity", CODE);

        assertEquals(500, result.get("code"));
        verify(registrationService, never()).redeemExploreChapterAndRecord(
                any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(perkLedgerService, never()).isExploreCompleted(anyLong(), anyLong());
        verify(badgeMapper, never()).insertPlayerBadge(any(PlayerBadge.class));
    }
}
