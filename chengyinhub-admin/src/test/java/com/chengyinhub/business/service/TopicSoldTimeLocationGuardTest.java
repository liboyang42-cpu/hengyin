package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-63(2026-09-15 裁决 15):主题编辑会把档期与地点刷到自办活动壳上
 * ({@code CmsTopicServiceImpl#refreshSelfHostActivity}),已售出(有用户下单)时必须拦下 ——
 * 否则主题编辑就成了绕过「活动编辑锁时间地点」的后门。
 *
 * <p>只锁时间与地点:文字/图片照常刷新。闸按秒比较,且只有确有时/地差异才去查报名
 * (正常编辑不额外打库,这条也在用例里钉住)。
 *
 * <p>P1-2(审查D):「已售出」= payment_status 0 待支付/1 支付中/2 已支付;
 * 3 支付失败(含关单)/4 已退款不算。
 *
 * <p>⚠️ 负控:把判据放宽为「任何报名」(把 0/1/2 之外也数进来)⇒
 * {@code closedOrRefundedRegistrationsDoNotBlock} 红;删掉 assert ⇒
 * {@code soldActivityBlocksTimeChange/PlaceChange/pendingPayment*} 红。
 */
class TopicSoldTimeLocationGuardTest {

    private CmsTopicServiceImpl svc;
    private CmsRegistrationMapper registrationMapper;

    @BeforeEach
    void setUp() {
        svc = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        registrationMapper = mock(CmsRegistrationMapper.class);
        ReflectionTestUtils.setField(svc, "cmsRegistrationMapper", registrationMapper);
    }

    private CmsTopic topic(Date start, Date end, String addressName) {
        CmsTopic t = new CmsTopic();
        t.setId(300L);
        t.setStartDate(start);
        t.setEndDate(end);
        t.setAddressName(addressName);
        return t;
    }

    private CmsActivity activity(Date start, Date end, String addressName) {
        CmsActivity a = new CmsActivity();
        a.setId(700L);
        a.setStartDate(start);
        a.setEndDate(end);
        a.setAddressName(addressName);
        return a;
    }

    /** 只桩指定状态这一口 —— 判据写错(少查一个状态/多查一个状态)的桩匹配不上,用例自然红。 */
    private void givenRegistrationsInStatus(long activityId, int paymentStatus) {
        CmsRegistration row = new CmsRegistration();
        row.setPaymentStatus(paymentStatus);
        when(registrationMapper.selectCmsRegistrationList(argThat(q -> q != null
                && Long.valueOf(2L).equals(q.getOwnerType())
                && Long.valueOf(activityId).equals(q.getOwnerId())
                && Integer.valueOf(paymentStatus).equals(q.getPaymentStatus()))))
                .thenReturn(Collections.singletonList(row));
    }

    private void invoke(CmsTopic fresh, CmsActivity activity) {
        ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicPublicationTestWiring.owner(svc, "assertSoldActivityTimeLocationUntouched"), "assertSoldActivityTimeLocationUntouched", fresh, activity);
    }

    @Test
    void soldActivityBlocksTimeChange() {
        givenRegistrationsInStatus(700L, 2);
        ServiceException error = assertThrows(ServiceException.class,
                () -> invoke(topic(new Date(1_800_000_000_000L), null, "外滩"),
                        activity(new Date(1_900_000_000_000L), null, "外滩")));
        assertTrue(error.getMessage().contains("时间与地点"), "实际:" + error.getMessage());
    }

    @Test
    void soldActivityBlocksPlaceChange() {
        givenRegistrationsInStatus(700L, 2);
        ServiceException error = assertThrows(ServiceException.class,
                () -> invoke(topic(null, null, "新集合点"), activity(null, null, "外滩")));
        assertTrue(error.getMessage().contains("时间与地点"), "实际:" + error.getMessage());
    }

    /** P1-2:钱还在路上(待支付/支付中)也算「有人下单」,不能改期。 */
    @Test
    void pendingOrPayingRegistrationsAlsoBlockTimeChange() {
        for (int status : new int[] { 0, 1 }) {
            setUp();
            givenRegistrationsInStatus(700L, status);
            ServiceException error = assertThrows(ServiceException.class,
                    () -> invoke(topic(new Date(1_800_000_000_000L), null, "外滩"),
                            activity(new Date(1_900_000_000_000L), null, "外滩")),
                    "payment_status=" + status + " 也该拦");
            assertTrue(error.getMessage().contains("时间与地点"), "实际:" + error.getMessage());
        }
    }

    /** 弃单/退款不是「已售出」,不能把主题编辑一起冻死。 */
    @Test
    void closedOrRefundedRegistrationsDoNotBlock() {
        for (int status : new int[] { 3, 4 }) {
            setUp();
            givenRegistrationsInStatus(700L, status);
            assertDoesNotThrow(() -> invoke(topic(new Date(1_800_000_000_000L), null, "外滩"),
                    activity(new Date(1_900_000_000_000L), null, "外滩")),
                    "payment_status=" + status + " 不该拦");
        }
    }

    @Test
    void soldActivityAllowsTextOnlyEditAndDoesNotQueryRegistrations() {
        givenRegistrationsInStatus(700L, 2);
        Date same = new Date(1_800_000_000_000L);

        assertDoesNotThrow(() -> invoke(topic(same, same, "外滩"), activity(same, same, "外滩")));

        verify(registrationMapper, never()).selectCmsRegistrationList(any());
    }

    @Test
    void unsoldActivityAllowsTimeChange() {
        assertDoesNotThrow(() -> invoke(topic(new Date(1_800_000_000_000L), null, "外滩"),
                activity(new Date(1_900_000_000_000L), null, "外滩")));
    }
}
