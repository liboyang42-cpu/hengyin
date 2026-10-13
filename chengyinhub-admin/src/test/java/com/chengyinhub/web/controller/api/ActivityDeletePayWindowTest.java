package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W9 · 活动删除的支付窗口闸 —— 与主题侧同病同治。
 *
 * <p><b>病</b>:{@code ApiActivityController.activityDelete} 只问 {@code countPaidRegistrations},
 * 而它只查 {@code payment_status=2} = 钱已到账。但玩家「拉起支付、回调还没到」的单是 <b>0</b>
 * ⇒ 此刻删活动照样放行 ⇒ 回调到达把单置 2 = <b>钱收了、单成了、活动没了</b>,且活动侧的
 * 「取消活动 → 全额退」这条补救路也随之查不到了。
 *
 * <p><b>为什么测到 controller 而不是只测 service</b>:闸放在哪层不算数,<b>入口会不会经过它</b>才算。
 * 只测 {@code countPendingRegistrations} 返回值,拆掉 controller 里的 if 照样全绿。
 * 故这里真串 controller → CmsActivityServiceImpl → mapper,只桩最外面的 mapper。
 *
 * <p><b>判据 = 无条件拦 {@code payment_status=0}</b>,不按 {@code pay_expire_time} 放过过期弃单 ——
 * 该字段后端无强制力,过期单照样付得了。弃单解堵靠 {@code orderCloseTask} 关单成 3。
 * 完整理由见 {@code CmsTopicServiceImpl.assertTopicDeletable}。
 *
 * <p>⚠️ 负控(已实测):删 controller 里的待支付 if ⇒ {@code pendingOrder_cannotDelete} 红。
 * 两道闸都返回 error(),故断言文案区分是哪道拦的,防「拆一道另一道接住」。
 */
class ActivityDeletePayWindowTest {

    private static final long ME = 9L;
    private static final long ACTIVITY = 300L;

    private CmsActivityMapper activityMapper;
    private CmsRegistrationMapper registrationMapper;
    private ApiActivityController controller;

    @BeforeEach
    void setUp() {
        activityMapper = mock(CmsActivityMapper.class);
        registrationMapper = mock(CmsRegistrationMapper.class);

        CmsActivityServiceImpl service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);

        controller = org.mockito.Mockito.spy(new ApiActivityController());
        org.mockito.Mockito.doReturn(ME).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsActivityService", service);

        // #224 起活动写操作统一要求俱乐部主理人身份;把 ME 桩成主理人,让付费窗口/IDOR 判据得以执行。
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        IRoleService roleService = mock(IRoleService.class);
        UmsMember me = new UmsMember();
        me.setId(ME);
        when(umsMemberService.selectUmsMemberById(ME)).thenReturn(me);
        when(roleService.resolveRole(ArgumentMatchers.any())).thenReturn(IRoleService.ROLE_CLUB);
        ReflectionTestUtils.setField(controller, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(controller, "roleService", roleService);

        CmsActivity a = new CmsActivity();
        a.setId(ACTIVITY);
        a.setMemberId(ME);
        a.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(a);
        when(activityMapper.updateCmsActivity(ArgumentMatchers.any())).thenReturn(1);
    }

    private void givenRegistrationRows(Integer status, List<CmsRegistration> rows) {
        when(registrationMapper.countCmsRegistrationList(ArgumentMatchers.argThat(
                q -> q != null && status.equals(q.getPaymentStatus())
                        && Long.valueOf(2L).equals(q.getOwnerType())
                        && Long.valueOf(ACTIVITY).equals(q.getOwnerId()))))
                .thenReturn(rows.size());
    }

    /** 下单主通路真实写出的待支付单:status=0 + pay_expire_time=下单后 30 分钟。 */
    private CmsRegistration pendingOrderLike(int payExpireMinutesFromNow) {
        CmsRegistration r = new CmsRegistration();
        r.setPaymentStatus(0);
        r.setPayExpireTime(DateUtils.addMinutes(new Date(), payExpireMinutesFromNow));
        return r;
    }

    private AjaxResult delete() {
        return controller.activityDelete(String.valueOf(ACTIVITY), null);
    }

    /** ★★ 洞本身:有人正在支付(status=0)⇒ 拒删,且一行都不能改。 */
    @Test
    void pendingOrder_cannotDelete() {
        givenRegistrationRows(0, Collections.singletonList(pendingOrderLike(30)));

        AjaxResult r = delete();

        assertEquals(500, ((Integer) r.get("code")).intValue(), "有待支付单必须拒删");
        assertTrue(String.valueOf(r.get("msg")).contains("待支付"),
                "得指明是待支付这道闸拦的。实际:" + r.get("msg"));
        verify(activityMapper, never()).updateCmsActivity(any());
    }

    /**
     * ★★ 窗口已过的弃单 ⇒ <b>仍然拒</b>:pay_expire_time 后端无强制力,过期单照样付得了,
     * 放行它就是把洞挪到陈年单上。解堵靠 orderCloseTask 关单成 3。
     */
    @Test
    void expiredPendingOrder_stillCannotDelete() {
        givenRegistrationRows(0, Collections.singletonList(pendingOrderLike(-1)));

        AjaxResult r = delete();

        assertEquals(500, ((Integer) r.get("code")).intValue(), "过期弃单照样能付,必须拒删");
        assertTrue(String.valueOf(r.get("msg")).contains("待支付"), "实际:" + r.get("msg"));
        verify(activityMapper, never()).updateCmsActivity(any());
    }

    /** ★ 已关单的(3)⇒ 放行。弃单的唯一出口:orderCloseTask 把 0 关成 3。 */
    @Test
    void closedOrdersOnly_canDelete() {
        givenRegistrationRows(3, Collections.singletonList(new CmsRegistration()));

        AjaxResult r = delete();

        assertEquals(200, ((Integer) r.get("code")).intValue(), "已关单不该堵死删除,实际:" + r.get("msg"));
        verify(activityMapper).updateCmsActivity(any());
    }

    /** 已支付 ⇒ 拒。原有那道闸不能被我改丢。 */
    @Test
    void paidActivity_cannotDelete() {
        givenRegistrationRows(2, Collections.singletonList(new CmsRegistration()));

        AjaxResult r = delete();

        assertEquals(500, ((Integer) r.get("code")).intValue());
        assertTrue(String.valueOf(r.get("msg")).contains("付费报名"), "实际:" + r.get("msg"));
        verify(activityMapper, never()).updateCmsActivity(any());
    }

    /** 无报名 ⇒ 放行(正常删草稿路径,拦了就是打挂功能)。 */
    @Test
    void noRegistration_canDelete() {
        AjaxResult r = delete();

        assertEquals(200, ((Integer) r.get("code")).intValue(), "实际:" + r.get("msg"));
        verify(activityMapper).updateCmsActivity(any());
    }

    /** 他人活动 ⇒ 拒。原有 IDOR 校验不能被我改丢。 */
    @Test
    void othersActivity_cannotDelete() {
        CmsActivity other = new CmsActivity();
        other.setId(ACTIVITY);
        other.setMemberId(999L);
        other.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(other);

        AjaxResult r = delete();

        assertEquals(500, ((Integer) r.get("code")).intValue());
        assertTrue(String.valueOf(r.get("msg")).contains("无权"), "实际:" + r.get("msg"));
        verify(activityMapper, never()).updateCmsActivity(any());
    }
}
