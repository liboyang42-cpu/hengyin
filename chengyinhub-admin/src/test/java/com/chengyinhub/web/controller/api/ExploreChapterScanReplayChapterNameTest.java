package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
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
 * SC-SCAN-RACE(F-L3-08)修复:重放回包与首次回包带同一个 chapterName 字段,
 * 商家在回包丢失后重试也能看到核的是哪一章;查名失败降级为「章节 id」,不影响已成立的核销。
 */
class ExploreChapterScanReplayChapterNameTest {

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
    private ICmsTopicChapterService chapterSvc;
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
        chapterSvc = mock(ICmsTopicChapterService.class);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "topicChapterService", chapterSvc);
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
    void replayCarriesTheSameChapterNameAsTheFirstResponse() {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setName("老街第一章");
        when(chapterSvc.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);
        when(regSvc.redeemExploreChapterAndRecord(eq(reg), eq(CHAPTER), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenReturn(new ExploreChapterRedemptionResult(66L, 0, false))
                .thenReturn(new ExploreChapterRedemptionResult(66L, 0, true));

        AjaxResult first = controller.scanQrCodeChapter(CODE, CHAPTER);
        AjaxResult retry = controller.scanQrCodeChapter(CODE, CHAPTER);

        Map<String, Object> firstData = (Map<String, Object>) first.get("data");
        Map<String, Object> retryData = (Map<String, Object>) retry.get("data");
        assertEquals(200, first.get("code"));
        assertEquals(200, retry.get("code"));
        assertEquals(Boolean.TRUE, retryData.get("replayed"));
        assertEquals("老街第一章", firstData.get("chapterName"));
        assertEquals(firstData.get("chapterName"), retryData.get("chapterName"));
        // 重放仍不发第二份奖励
        verify(pointsSvc, times(1)).award(eq(77L), eq(11L), eq(REG), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayStillSucceedsWithFallbackNameWhenChapterLookupFails() {
        when(chapterSvc.selectCmsTopicChapterById(CHAPTER)).thenThrow(new IllegalStateException("db-down"));
        when(regSvc.redeemExploreChapterAndRecord(eq(reg), eq(CHAPTER), eq(TOPIC), eq(MERCHANT), eq(MERCHANT), any()))
                .thenReturn(new ExploreChapterRedemptionResult(66L, 0, true));

        AjaxResult retry = controller.scanQrCodeChapter(CODE, CHAPTER);

        Map<String, Object> data = (Map<String, Object>) retry.get("data");
        assertEquals(200, retry.get("code"));
        assertEquals(66L, data.get("redemptionId"));
        assertEquals("章节 " + CHAPTER, data.get("chapterName"));
        verifyNoInteractions(pointsSvc);
    }
}
