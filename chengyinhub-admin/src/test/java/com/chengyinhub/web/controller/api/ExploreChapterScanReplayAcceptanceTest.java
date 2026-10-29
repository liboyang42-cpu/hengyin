package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IExploreCollectibleService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验收 SC-SCAN-RACE(F-L3-08)控制器侧:回包丢失后重试 scan_qr_code_chapter,
 * 不再先算候选集(已核销章节会被滤空)而报错,而是直达 service 的幂等重放,返回原 redemptionId,
 * 且控制器不再发分、成长或收藏。服务侧「同票同章只核一次」由 ExploreRedeemRecordTest / ExploreRedeemIdempotentTest
 * 覆盖;并发串行化只能在真库(ExploreScanReplayRealDbIT)验证。
 */
class ExploreChapterScanReplayAcceptanceTest {

    private static final Long MERCHANT = 500L;
    private static final Long REG = 900L;
    private static final Long ACTIVITY = 800L;
    private static final Long TOPIC = 700L;
    private static final Long CHAPTER = 101L;
    private static final String CODE = "STATIC-CODE";

    private ICmsRegistrationService regSvc;
    private IPerkLedgerService perkSvc;
    private IPointsAwardService pointsSvc;
    private IGrowthService growthSvc;
    private IExploreCollectibleService collectibleSvc;
    private ApiRegistrationController controller;
    private CmsRegistration reg;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationService.class);
        perkSvc = mock(IPerkLedgerService.class);
        pointsSvc = mock(IPointsAwardService.class);
        growthSvc = mock(IGrowthService.class);
        collectibleSvc = mock(IExploreCollectibleService.class);
        ICmsActivityService activitySvc = mock(ICmsActivityService.class);

        controller = spy(new ApiRegistrationController());
        doReturn(MERCHANT).when(controller).getAppUserId();
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationService", regSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "cmsActivityService", activitySvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "topicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "perkLedgerService", perkSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "pointsAwardService", pointsSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "growthService", growthSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "exploreCollectibleService", collectibleSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "dynSecret", "test-secret");
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "merchantAccessService", access);
        when(access.require(MERCHANT, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MERCHANT, 11L, MERCHANT, MerchantRoleCode.MERCHANT_OWNER));

        reg = new CmsRegistration();
        reg.setId(REG);
        reg.setOwnerType(2L);
        reg.setOwnerId(ACTIVITY);
        reg.setMemberId(77L);
        reg.setPaymentStatus(2);
        reg.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(regSvc.selectByverificationCode(CODE)).thenReturn(reg);
        CmsActivity act = new CmsActivity();
        act.setId(ACTIVITY);
        act.setTopicId(TOPIC);
        // 移植分支探索票须有场次结束时间(ExploreTicketUsePolicy)，否则在核销前就被拒。
        act.setEndDate(new java.util.Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000));
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(act);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retryAfterLostReplyReturnsOriginalReceiptWithoutSecondReward() {
        when(regSvc.redeemExploreChapterAndRecord(eq(reg), eq(CHAPTER), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenReturn(new ExploreChapterRedemptionResult(66L, 25, false))
                .thenReturn(new ExploreChapterRedemptionResult(66L, 0, true));

        AjaxResult first = controller.scanQrCodeChapter(CODE, CHAPTER);
        assertEquals(200, first.get("code"));
        assertEquals(66L, ((Map<String, Object>) first.get("data")).get("redemptionId"));

        AjaxResult retry = controller.scanQrCodeChapter(CODE, CHAPTER);
        assertEquals(200, retry.get("code"), "重试不能因候选集已空而报错");
        assertEquals("该章节已核销，请勿重复提供权益", retry.get("msg"));
        Map<String, Object> data = (Map<String, Object>) retry.get("data");
        assertEquals(66L, data.get("redemptionId"));
        assertEquals(CHAPTER, data.get("chapterId"));
        assertEquals(Boolean.TRUE, data.get("replayed"));
        assertFalse(data.containsKey("completionEarnedXp"));

        // 首次一份奖励;重放不再发分、成长、收藏,也不经过候选集推断。
        verify(pointsSvc, times(1)).award(eq(77L), eq(11L), eq(REG), any());
        verify(collectibleSvc, times(1)).record(77L, TOPIC, CHAPTER, MERCHANT);
        verifyNoInteractions(perkSvc);
    }
}
