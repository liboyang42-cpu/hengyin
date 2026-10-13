package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.DateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 · 后台活动删除必须走与 App 删除相同的已付/待支付闸。
 *
 * <p><b>病</b>:{@code CmsActivityController.remove} 原先直接 {@code delFlag=2},
 * 不看报名。App 删除({@code ApiActivityController.activityDelete})已有
 * {@code countPaidRegistrations} / {@code countPendingRegistrations} 两道闸;
 * 后台没有 ⇒ 运营可把付费单做成「活动没了、钱还在」。
 *
 * <p><b>为什么测到 controller</b>:闸放在哪层不算数,入口会不会经过它才算。
 * 只测 count 返回值,拆掉 controller 里的 if 照样全绿。故这里真串
 * controller → CmsActivityServiceImpl → mapper,只桩最外面的 mapper。
 *
 * <p>判据与 App / W9 一致:拦 {@code payment_status=2} 与无条件拦 {@code 0},
 * 不按 {@code pay_expire_time} 放过过期弃单。批量里只要一条碰过钱,整批拒、一行都不改。
 *
 * <p>⚠️ 负控:删付费 if ⇒ {@code paidActivity_cannotDelete} 红;删待支付 if ⇒
 * {@code pendingOrder_cannotDelete} + {@code expiredPendingOrder_stillCannotDelete} 红;
 * 先改后检 ⇒ {@code batchWithOnePaid_rejectsWholeBatch} 红。
 */
class CmsActivityAdminDeleteGateTest {

    private static final long ACTIVITY = 300L;
    private static final long OTHER = 301L;

    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private CmsActivityController controller;

    @BeforeEach
    void setUp() {
        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);

        CmsActivityServiceImpl service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);

        controller = new CmsActivityController();
        ReflectionTestUtils.setField(controller, "cmsActivityService", service);

        when(activityMapper.updateCmsActivity(ArgumentMatchers.any())).thenReturn(1);
    }

    private void givenRegistrationRows(long activityId, Integer status, List<CmsRegistration> rows) {
        when(registrationMapper.countCmsRegistrationList(ArgumentMatchers.argThat(
                q -> q != null && status.equals(q.getPaymentStatus())
                        && Long.valueOf(2L).equals(q.getOwnerType())
                        && Long.valueOf(activityId).equals(q.getOwnerId()))))
                .thenReturn(rows.size());
    }

    private CmsRegistration pendingOrderLike(int payExpireMinutesFromNow) {
        CmsRegistration r = new CmsRegistration();
        r.setPaymentStatus(0);
        r.setPayExpireTime(DateUtils.addMinutes(new Date(), payExpireMinutesFromNow));
        return r;
    }

    private AjaxResult delete(Long... ids) {
        return controller.remove(ids);
    }

    @Test
    void pendingOrder_cannotDelete() {
        givenRegistrationRows(ACTIVITY, 0, Collections.singletonList(pendingOrderLike(30)));

        AjaxResult r = delete(ACTIVITY);

        assertEquals(500, ((Integer) r.get("code")).intValue(), "有待支付单必须拒删");
        assertTrue(String.valueOf(r.get("msg")).contains("待支付"),
                "得指明是待支付这道闸拦的。实际:" + r.get("msg"));
        verify(activityMapper, never()).updateCmsActivity(any());
    }

    @Test
    void expiredPendingOrder_stillCannotDelete() {
        givenRegistrationRows(ACTIVITY, 0, Collections.singletonList(pendingOrderLike(-1)));

        AjaxResult r = delete(ACTIVITY);

        assertEquals(500, ((Integer) r.get("code")).intValue(), "过期弃单照样能付,必须拒删");
        assertTrue(String.valueOf(r.get("msg")).contains("待支付"), "实际:" + r.get("msg"));
        verify(activityMapper, never()).updateCmsActivity(any());
    }

    @Test
    void closedOrdersOnly_canDelete() {
        givenRegistrationRows(ACTIVITY, 3, Collections.singletonList(new CmsRegistration()));

        AjaxResult r = delete(ACTIVITY);

        assertEquals(200, ((Integer) r.get("code")).intValue(), "已关单不该堵死删除,实际:" + r.get("msg"));
        verify(activityMapper).updateCmsActivity(any());
    }

    @Test
    void paidActivity_cannotDelete() {
        givenRegistrationRows(ACTIVITY, 2, Collections.singletonList(new CmsRegistration()));

        AjaxResult r = delete(ACTIVITY);

        assertEquals(500, ((Integer) r.get("code")).intValue());
        assertTrue(String.valueOf(r.get("msg")).contains("付费报名"), "实际:" + r.get("msg"));
        verify(activityMapper, never()).updateCmsActivity(any());
    }

    @Test
    void noRegistration_canDelete() {
        AjaxResult r = delete(ACTIVITY);

        assertEquals(200, ((Integer) r.get("code")).intValue(), "实际:" + r.get("msg"));
        verify(activityMapper).updateCmsActivity(any());
    }

    @Test
    void batchWithOnePaid_rejectsWholeBatch() {
        givenRegistrationRows(ACTIVITY, 2, Collections.singletonList(new CmsRegistration()));

        AjaxResult r = delete(OTHER, ACTIVITY);

        assertEquals(500, ((Integer) r.get("code")).intValue());
        assertTrue(String.valueOf(r.get("msg")).contains("付费报名"), "实际:" + r.get("msg"));
        verify(activityMapper, never()).updateCmsActivity(any());
    }

    @Test
    void batchAllClean_deletesAll() {
        AjaxResult r = delete(ACTIVITY, OTHER);

        assertEquals(200, ((Integer) r.get("code")).intValue(), "实际:" + r.get("msg"));
        verify(activityMapper, times(2)).updateCmsActivity(any());
    }
}
