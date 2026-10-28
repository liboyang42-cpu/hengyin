package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 订单详情 /info 展示 enrich：手机号脱敏、支付方式文案、退款截止展示、权益章节名。
 */
class RegistrationInfoDisplayEnrichTest {

    private static final Long MEMBER = 42L;
    private static final Long REG = 900L;
    private static final Long ACTIVITY = 800L;

    private ApiRegistrationController controller;
    private ICmsRegistrationService regSvc;
    private ICmsActivityService activitySvc;
    private IPerkLedgerService perkSvc;
    private IRefundService refundSvc;
    private ICmsTopicChapterService chapterSvc;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationService.class);
        activitySvc = mock(ICmsActivityService.class);
        perkSvc = mock(IPerkLedgerService.class);
        refundSvc = mock(IRefundService.class);
        chapterSvc = mock(ICmsTopicChapterService.class);

        controller = spy(new ApiRegistrationController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", regSvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", activitySvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "perkLedgerService", perkSvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refundSvc);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "topicChapterService", chapterSvc);
        when(refundSvc.selectRefundList(any(RefundApplication.class))).thenReturn(Collections.emptyList());

        CmsActivity act = new CmsActivity();
        act.setId(ACTIVITY);
        when(activitySvc.selectCmsActivityById(ACTIVITY)).thenReturn(act);
    }

    private CmsRegistration baseReg() {
        CmsRegistration r = new CmsRegistration();
        r.setId(REG);
        r.setMemberId(MEMBER);
        r.setOwnerType(2L);
        r.setOwnerId(ACTIVITY);
        r.setPaymentStatus(2);
        r.setPaymentType(2);
        r.setPhone("13800138000");
        r.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        return r;
    }

    @Test
    void info_masksPhoneAndSetsPaymentTypeLabel() {
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(baseReg());
        when(perkSvc.listEntitlements(REG)).thenReturn(Collections.emptyList());

        AjaxResult result = RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG));
        CmsRegistration data = (CmsRegistration) result.get("data");

        assertEquals("138****8000", data.getPhone());
        assertEquals("微信+积分", data.getPaymentTypeLabel());
    }

    @Test
    void info_paymentTypeLabel_mapsAllThree() {
        assertEquals("积分", invokeLabel(0));
        assertEquals("微信", invokeLabel(1));
        assertEquals("微信+积分", invokeLabel(2));
    }

    private String invokeLabel(int paymentType) {
        CmsRegistration r = baseReg();
        r.setPaymentType(paymentType);
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(r);
        when(perkSvc.listEntitlements(REG)).thenReturn(Collections.emptyList());
        CmsRegistration data = (CmsRegistration) RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG)).get("data");
        return data.getPaymentTypeLabel();
    }

    @Test
    void info_refundDeadlineDisplay_whenRefundable() {
        CmsRegistration r = baseReg();
        // ★ 截止时间必须相对「现在」算,不能写死日历日。
        //   原来这里钉的是 2026-08-20 14:30 —— 那天一到,deadline 就成了过去时,
        //   展示文案翻成「已错过活动开始时间,不可退款」,测试当场爆炸(2026-08-20 实发,挡住了当天所有 PR)。
        //   写死未来日期的测试不是通过,只是还没到期。
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        cal.add(Calendar.DAY_OF_MONTH, 30);
        cal.set(Calendar.HOUR_OF_DAY, 14);
        cal.set(Calendar.MINUTE, 30);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date deadline = cal.getTime();
        String expectedMonthDay = (cal.get(Calendar.MONTH) + 1) + "月" + cal.get(Calendar.DAY_OF_MONTH) + "日";
        r.setRefundDeadline(deadline);
        r.setPaymentStatus(2);
        r.setVerificationStatus(0);
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(r);
        when(perkSvc.listEntitlements(REG)).thenReturn(Collections.emptyList());

        CmsRegistration data = (CmsRegistration) RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG)).get("data");
        assertNotNull(data.getRefundDeadlineDisplay());
        assertTrue(data.getRefundDeadlineDisplay().contains("可免费取消至"),
                "应展示可免费取消至: " + data.getRefundDeadlineDisplay());
        assertTrue(data.getRefundDeadlineDisplay().contains(expectedMonthDay),
                "应含月日 " + expectedMonthDay + ": " + data.getRefundDeadlineDisplay());
    }

    @Test
    void explorePass_entitlementsCarryChapterNameAndStatusLabel() {
        CmsRegistration r = baseReg();
        when(regSvc.selectCmsRegistrationById(REG)).thenReturn(r);

        RegistrationEntitlement e0 = new RegistrationEntitlement();
        e0.setChapterId(101L);
        e0.setStatus(0);
        RegistrationEntitlement e1 = new RegistrationEntitlement();
        e1.setChapterId(102L);
        e1.setStatus(1);
        when(perkSvc.listEntitlements(REG)).thenReturn(Arrays.asList(e0, e1));

        CmsTopicChapter c101 = new CmsTopicChapter();
        c101.setId(101L);
        c101.setName("第一章·街巷");
        CmsTopicChapter c102 = new CmsTopicChapter();
        c102.setId(102L);
        c102.setName("第二章·码头");
        when(chapterSvc.selectCmsTopicChapterById(101L)).thenReturn(c101);
        when(chapterSvc.selectCmsTopicChapterById(102L)).thenReturn(c102);

        CmsRegistration data = (CmsRegistration) RegistrationQueryTestFixtures.wire(controller).getRegistrationDetail(String.valueOf(REG)).get("data");
        List<RegistrationEntitlement> ents = data.getEntitlements();
        assertNotNull(ents);
        assertEquals("第一章·街巷", ents.get(0).getChapterName());
        assertEquals("待核销", ents.get(0).getStatusLabel());
        assertEquals("第二章·码头", ents.get(1).getChapterName());
        assertEquals("已核销", ents.get(1).getStatusLabel());
    }
}
