package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IExploreCollectibleService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验收 SC-SCAN-WRONG(F-L3-10)。
 *
 * scan_qr_code_station 与 scan_qr_code_chapter 共用的解析器:过期/无效的 v1. 动态码必须透出自己的原因,
 * 不得回落静态码查询而报「核销码无效」;扫错对象(别家据点/章节)被拒,且不消耗码、不发分、不写进度。
 */
class ScanDynamicCodeReasonAcceptanceTest {

    private static final Long MERCHANT = 500L;
    private static final Long PLAYER = 77L;
    private static final Long REG = 900L;
    private static final Long ACTIVITY = 800L;
    private static final Long TOPIC = 700L;
    private static final Long CHAPTER = 101L;
    private static final Long STATION = 702L;
    private static final String SECRET = "test-secret";

    private ICmsRegistrationService regSvc;
    private ICmsActivityService activitySvc;
    private IPointsAwardService pointsSvc;
    private IGrowthService growthSvc;
    private IExploreCollectibleService collectibleSvc;
    private IClubMemberService clubMemberSvc;
    private ApiRegistrationController controller;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationService.class);
        activitySvc = mock(ICmsActivityService.class);
        pointsSvc = mock(IPointsAwardService.class);
        growthSvc = mock(IGrowthService.class);
        collectibleSvc = mock(IExploreCollectibleService.class);
        clubMemberSvc = mock(IClubMemberService.class);

        controller = spy(new ApiRegistrationController());
        doReturn(MERCHANT).when(controller).getAppUserId();
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationService", regSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "cmsActivityService", activitySvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "topicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "perkLedgerService", mock(IPerkLedgerService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "pointsAwardService", pointsSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "growthService", growthSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "exploreCollectibleService", collectibleSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "clubMemberService", clubMemberSvc);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationMerchantService",
                mock(ICmsRegistrationMerchantService.class));
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "dynSecret", SECRET);
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "merchantAccessService", access);
        when(access.require(MERCHANT, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(MERCHANT, 11L, MERCHANT, MerchantRoleCode.MERCHANT_OWNER));
    }

    private static String expiredCode(String type) {
        return VerifyDynCodeUtil.issue(REG, type, System.currentTimeMillis() - 60_000L, 1_000L, 9L, SECRET);
    }

    private void assertNoLookupOrSideEffect() {
        verify(regSvc, never()).selectByverificationCode(anyString());
        verify(regSvc, never()).selectCmsRegistrationById(anyLong());
        verify(regSvc, never()).verifyAndSettleForStation(any(), anyLong(), anyLong());
        verify(regSvc, never()).verifyAndSettleForStation(any(), anyLong(), anyLong(), anyLong(), any());
        verify(regSvc, never()).redeemExploreChapterAndRecord(any(), anyLong(), anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(pointsSvc, growthSvc, collectibleSvc);
    }

    @Test
    void expiredDynamicCodeReportsExpiryOnStationScan() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> controller.scanQrCodeStation(expiredCode("topic"), STATION));
        assertEquals("已过期", e.getMessage());
        assertNoLookupOrSideEffect();
    }

    @Test
    void expiredDynamicCodeReportsExpiryOnChapterScan() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> controller.scanQrCodeChapter(expiredCode("activity"), CHAPTER));
        assertEquals("已过期", e.getMessage());
        assertNoLookupOrSideEffect();
    }

    @Test
    void forgedDynamicCodeReportsSignatureMismatchOnBothEntries() {
        String forged = VerifyDynCodeUtil.issue(REG, "topic", System.currentTimeMillis(), 60_000L, 9L, "other-secret");
        assertEquals("签名不匹配", assertThrows(ServiceException.class,
                () -> controller.scanQrCodeStation(forged, STATION)).getMessage());
        assertEquals("签名不匹配", assertThrows(ServiceException.class,
                () -> controller.scanQrCodeChapter(forged, CHAPTER)).getMessage());
        assertNoLookupOrSideEffect();
    }

    @Test
    void wrongStationIsRefusedWithoutAwardOrGrowth() {
        String code = VerifyDynCodeUtil.issue(REG, "topic", System.currentTimeMillis(), 60_000L, 9L, SECRET);
        CmsRegistration reg = new CmsRegistration();
        reg.setId(REG);
        reg.setOwnerType(1L);
        reg.setOwnerId(TOPIC);
        reg.setMemberId(PLAYER);
        reg.setPaymentStatus(2);
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(reg);
        when(regSvc.verifyAndSettleForStation(reg, MERCHANT, STATION))
                .thenThrow(new IllegalStateException("所选据点不属于当前商家或路线"));

        AjaxResult r = controller.scanQrCodeStation(code, STATION);

        assertEquals(500, r.get("code"));
        assertEquals("所选据点不属于当前商家或路线", r.get("msg"));
        verify(regSvc, never()).selectByverificationCode(anyString());
        verifyNoInteractions(pointsSvc, growthSvc, collectibleSvc);
    }

    @Test
    void wrongChapterIsRefusedWithoutAwardGrowthOrCollectible() {
        String code = VerifyDynCodeUtil.issue(REG, "activity", System.currentTimeMillis(), 60_000L, 9L, SECRET);
        CmsRegistration reg = new CmsRegistration();
        reg.setId(REG);
        reg.setOwnerType(2L);
        reg.setOwnerId(ACTIVITY);
        reg.setMemberId(PLAYER);
        reg.setPaymentStatus(2);
        reg.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(reg);
        CmsActivity act = new CmsActivity();
        act.setId(ACTIVITY);
        act.setTopicId(TOPIC);
        // 移植分支探索票须有场次结束时间(ExploreTicketUsePolicy)，否则在核销前就被拒。
        act.setEndDate(new java.util.Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000));
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(act);
        when(regSvc.redeemExploreChapterAndRecord(eq(reg), eq(CHAPTER), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenThrow(new IllegalStateException("该章节不可由您核销"));

        AjaxResult r = controller.scanQrCodeChapter(code, CHAPTER);

        assertEquals(500, r.get("code"));
        assertEquals("该章节不可由您核销", r.get("msg"));
        verifyNoInteractions(pointsSvc, growthSvc, collectibleSvc);
    }
}
