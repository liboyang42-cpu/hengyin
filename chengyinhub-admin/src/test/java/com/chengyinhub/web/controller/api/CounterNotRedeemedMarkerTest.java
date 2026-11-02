package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IExploreCollectibleService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * M-02:核销事务开始前就被拒(伪造/过期/本店无此票权益)的回包必须带 data.notRedeemed=true,
 * 柜台据此显示红色「未核销」;事务里抛出的异常不得带此标记(客户端保留「待确认」)。
 * M-06:他日场次的票照常核销,回包带场次日期以便柜台提醒;当天的场次不带。
 */
class CounterNotRedeemedMarkerTest {

    private static final Long MERCHANT = 500L;
    private static final Long PLAYER = 77L;
    private static final Long REG = 900L;
    private static final Long ACTIVITY = 800L;
    private static final Long TOPIC = 700L;
    private static final Long CHAPTER = 101L;
    private static final String SECRET = "test-secret";
    private static final long DAY = 24L * 3600 * 1000;

    private ICmsRegistrationService regSvc;
    private IPerkLedgerService perkSvc;
    private IPointsAwardService pointsSvc;
    private CmsActivity activity;
    private CmsRegistration reg;
    private ApiRegistrationController controller;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationService.class);
        perkSvc = mock(IPerkLedgerService.class);
        pointsSvc = mock(IPointsAwardService.class);
        ICmsActivityService activitySvc = mock(ICmsActivityService.class);
        controller = spy(new ApiRegistrationController());
        doReturn(MERCHANT).when(controller).getAppUserId();
        Object app = RegistrationRedemptionTestFixtures.application(controller);
        ReflectionTestUtils.setField(app, "registrationService", regSvc);
        ReflectionTestUtils.setField(app, "cmsActivityService", activitySvc);
        ReflectionTestUtils.setField(app, "topicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(app, "topicChapterService", mock(ICmsTopicChapterService.class));
        ReflectionTestUtils.setField(app, "perkLedgerService", perkSvc);
        ReflectionTestUtils.setField(app, "pointsAwardService", pointsSvc);
        ReflectionTestUtils.setField(app, "growthService", mock(IGrowthService.class));
        ReflectionTestUtils.setField(app, "exploreCollectibleService", mock(IExploreCollectibleService.class));
        ReflectionTestUtils.setField(app, "clubMemberService", mock(IClubMemberService.class));
        ReflectionTestUtils.setField(app, "registrationMerchantService", mock(ICmsRegistrationMerchantService.class));
        ReflectionTestUtils.setField(app, "dynSecret", SECRET);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        ReflectionTestUtils.setField(app, "merchantAccessService", access);
        when(access.require(MERCHANT, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MERCHANT, 11L, MERCHANT, MerchantRoleCode.MERCHANT_OWNER));

        reg = new CmsRegistration();
        reg.setId(REG);
        reg.setOwnerType(2L);
        reg.setOwnerId(ACTIVITY);
        reg.setMemberId(PLAYER);
        reg.setPaymentStatus(2);
        reg.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(reg);
        activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setStartDate(new Date(System.currentTimeMillis() - 3600_000L));
        activity.setEndDate(new Date(System.currentTimeMillis() + 3600_000L));
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
    }

    private static boolean marked(AjaxResult r) {
        Object data = r.get(AjaxResult.DATA_TAG);
        return data instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) data).get("notRedeemed"));
    }

    private static String validCode() {
        return VerifyDynCodeUtil.issue(REG, "activity", System.currentTimeMillis(), 60_000L, 9L, SECRET);
    }

    @Test
    void forgedCodeIsMarkedNotRedeemed() {
        String forged = VerifyDynCodeUtil.issue(REG, "activity", System.currentTimeMillis(), 60_000L, 9L, "other-secret");
        AjaxResult r = controller.scanDynamicCode(forged);
        assertEquals("签名不匹配", r.get(AjaxResult.MSG_TAG));
        assertTrue(marked(r));
        verify(regSvc, never()).redeemExploreChapterAndRecord(any(), any(), any(), any(), any(), any());
    }

    @Test
    void expiredCodeIsMarkedNotRedeemed() {
        String expired = VerifyDynCodeUtil.issue(REG, "activity", System.currentTimeMillis() - 60_000L, 1_000L, 9L, SECRET);
        AjaxResult r = controller.scanDynamicCode(expired);
        assertEquals("已过期", r.get(AjaxResult.MSG_TAG));
        assertTrue(marked(r));
    }

    @Test
    void wrongShopIsMarkedNotRedeemed() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT)).thenReturn(Collections.emptyMap());
        AjaxResult r = controller.scanDynamicCode(validCode());
        assertTrue(r.isError());
        assertTrue(marked(r));
        verify(regSvc, never()).redeemExploreChapterAndRecord(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(pointsSvc);
    }

    @Test
    void failureInsideRedemptionStaysUnmarked() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT)).thenReturn(Collections.singletonMap(CHAPTER, null));
        when(regSvc.redeemExploreChapterAndRecord(eq(reg), eq(CHAPTER), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenThrow(new RuntimeException("db gone"));
        AjaxResult r = controller.scanDynamicCode(validCode());
        assertTrue(r.isError());
        assertFalse(marked(r), "事务内失败可能已提交，不能标成未核销");
    }

    @Test
    void otherDaySessionIsRedeemedWithSessionDate() {
        activity.setStartDate(new Date(System.currentTimeMillis() + 10 * DAY));
        activity.setEndDate(new Date(System.currentTimeMillis() + 10 * DAY + 3600_000L));
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT)).thenReturn(Collections.singletonMap(CHAPTER, null));
        when(regSvc.redeemExploreChapterAndRecord(eq(reg), eq(CHAPTER), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenReturn(new ExploreChapterRedemptionResult(55L, 0));
        AjaxResult r = controller.scanDynamicCode(validCode());
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        Map<?, ?> d = (Map<?, ?>) r.get(AjaxResult.DATA_TAG);
        assertEquals(Boolean.TRUE, d.get("sessionNotToday"));
        assertTrue(String.valueOf(d.get("sessionDate")).matches("\\d{1,2}月\\d{1,2}日"), String.valueOf(d.get("sessionDate")));
    }

    @Test
    void successNamesThePerkToHandOver() {
        when(perkSvc.livePerkName(CHAPTER, MERCHANT)).thenReturn("手冲一杯");
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT)).thenReturn(Collections.singletonMap(CHAPTER, null));
        when(regSvc.redeemExploreChapterAndRecord(eq(reg), eq(CHAPTER), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenReturn(new ExploreChapterRedemptionResult(55L, 0));
        AjaxResult r = controller.scanDynamicCode(validCode());
        assertEquals("手冲一杯", ((Map<?, ?>) r.get(AjaxResult.DATA_TAG)).get("perkName"));
    }

    @Test
    void perkNameFailureNeverBreaksASuccessfulRedemption() {
        when(perkSvc.livePerkName(CHAPTER, MERCHANT)).thenThrow(new RuntimeException("db"));
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT)).thenReturn(Collections.singletonMap(CHAPTER, null));
        when(regSvc.redeemExploreChapterAndRecord(eq(reg), eq(CHAPTER), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenReturn(new ExploreChapterRedemptionResult(55L, 0));
        AjaxResult r = controller.scanDynamicCode(validCode());
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        assertNull(((Map<?, ?>) r.get(AjaxResult.DATA_TAG)).get("perkName"));
    }

    @Test
    void todaySessionCarriesNoWarning() {
        when(perkSvc.resolveScannableChapters(REG, TOPIC, MERCHANT)).thenReturn(Collections.singletonMap(CHAPTER, null));
        when(regSvc.redeemExploreChapterAndRecord(eq(reg), eq(CHAPTER), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenReturn(new ExploreChapterRedemptionResult(55L, 0));
        AjaxResult r = controller.scanDynamicCode(validCode());
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        assertNull(((Map<?, ?>) r.get(AjaxResult.DATA_TAG)).get("sessionDate"));
    }
}
