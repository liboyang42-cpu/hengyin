package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AccountDeregistration;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AccountDeregistrationMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.ICashWithdrawService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IRoamHangoutService;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.util.ReflectionUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SC-DEREG-08(F-L5S-05)修复:申请转 FAILED 时给会员发一条系统站内信;
 * 解冻事务本身失败时,申请仍在独立事务里移出 PENDING 并告警,不再占住 selectDue 的 limit 批次。
 */
class AccountDeregistrationFailureNoticeTest {

    private static final String FAILED_NOTICE =
            "你的账号注销申请未能完成，本次注销已终止。如仍需注销，可在设置中重新申请；如账号使用异常，请联系客服。";

    private AccountDeregistrationMapper d;
    private UmsMemberMapper m;
    private AlarmService a;
    private IImService im;
    private AccountDeregistrationServiceImpl service;

    private static AccountDeregistration due(long id, long memberId) {
        AccountDeregistration x = new AccountDeregistration();
        x.setId(id);
        x.setMemberId(memberId);
        x.setStatus("PENDING");
        x.setExecuteAfter(new Date(0));
        return x;
    }

    private static UmsMember member(long id, BigDecimal balance) {
        UmsMember u = new UmsMember();
        u.setId(id);
        u.setBalance(balance);
        u.setDelFlag(0);
        return u;
    }

    @BeforeEach
    void setUp() {
        d = mock(AccountDeregistrationMapper.class);
        m = mock(UmsMemberMapper.class);
        a = mock(AlarmService.class);
        im = mock(IImService.class);
        OmsOrderMapper o = mock(OmsOrderMapper.class);
        CmsRegistrationMapper r = mock(CmsRegistrationMapper.class);
        PlatformTransactionManager t = mock(PlatformTransactionManager.class);
        when(o.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(r.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        when(t.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(im.sendSystem(anyLong(), any(), anyString(), any())).thenReturn(1L);
        service = new AccountDeregistrationServiceImpl();
        ReflectionTestUtils.setField(service, "clubMapper", mock(ClubMapper.class));
        ReflectionTestUtils.setField(service, "merchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(service, "deregistrationMapper", d);
        ReflectionTestUtils.setField(service, "memberMapper", m);
        ReflectionTestUtils.setField(service, "orderMapper", o);
        ReflectionTestUtils.setField(service, "registrationMapper", r);
        ReflectionTestUtils.setField(service, "cashWithdrawService", mock(ICashWithdrawService.class));
        ReflectionTestUtils.setField(service, "memberWithdrawalService", mock(IUmsMemberWithdrawalService.class));
        ReflectionTestUtils.setField(service, "transactionManager", t);
        ReflectionTestUtils.setField(service, "alarmService", a);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.beginMemberMutation(anyLong())).thenAnswer(inv -> "mutation-" + inv.getArgument(0));
        ReflectionTestUtils.setField(service, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(service, "communityPostMapper", mock(CommunityPostMapper.class));
        ReflectionTestUtils.setField(service, "roamHangoutService", mock(IRoamHangoutService.class));
        // 修复前服务没有站内信通道;只在字段存在时注入,让修复前的运行落在断言上而不是装配上
        if (ReflectionUtils.findField(AccountDeregistrationServiceImpl.class, "imService") != null) {
            ReflectionTestUtils.setField(service, "imService", im);
        }
    }

    @Test
    void blockedAtExecutionNotifiesMemberOnce() {
        AccountDeregistration blocked = due(9L, 1001L);
        when(d.selectDue(any(Date.class))).thenReturn(Collections.singletonList(blocked));
        when(d.selectByIdForUpdate(9L)).thenReturn(blocked);
        when(m.selectUmsMemberById(1001L)).thenReturn(member(1001L, new BigDecimal("5.00")));
        when(d.markFailed(eq(9L), anyString(), any(Date.class))).thenReturn(1);

        assertEquals(0, service.executeDue(new Date()));

        verify(d).markMemberNormal(1001L);
        verify(im).sendSystem(eq(1001L), eq(ImMessage.MSG_TEXT),
                eq(FAILED_NOTICE), isNull());
    }

    @Test
    void executionErrorNotifiesFailedMemberButNotCompletedOne() {
        AccountDeregistration broken = due(9L, 1001L);
        AccountDeregistration healthy = due(10L, 1002L);
        when(d.selectDue(any(Date.class))).thenReturn(Arrays.asList(broken, healthy));
        when(d.selectByIdForUpdate(9L)).thenReturn(broken);
        when(d.selectByIdForUpdate(10L)).thenReturn(healthy);
        when(m.selectUmsMemberById(1001L)).thenThrow(new IllegalStateException("synthetic-failure"));
        when(m.selectUmsMemberById(1002L)).thenReturn(member(1002L, BigDecimal.ZERO));
        when(d.markFailed(eq(9L), anyString(), any(Date.class))).thenReturn(1);
        when(d.complete(eq(10L), any(Date.class))).thenReturn(1);

        assertEquals(1, service.executeDue(new Date()));

        verify(im).sendSystem(eq(1001L), eq(ImMessage.MSG_TEXT),
                eq(FAILED_NOTICE), isNull());
        verify(im, never()).sendSystem(eq(1002L), any(), anyString(), any());
    }

    @Test
    void unfreezeFailureStillMovesRequestOutOfDueSetWithAlarm() {
        AccountDeregistration broken = due(9L, 1001L);
        when(d.selectDue(any(Date.class))).thenReturn(Collections.singletonList(broken));
        when(d.selectByIdForUpdate(9L)).thenReturn(broken);
        when(m.selectUmsMemberById(1001L)).thenThrow(new IllegalStateException("synthetic-failure"));
        when(d.markFailed(eq(9L), anyString(), any(Date.class))).thenReturn(1);
        doThrow(new IllegalStateException("unfreeze-failure")).when(d).markMemberNormal(1001L);

        assertEquals(0, service.executeDue(new Date()));

        verify(a).alarm(eq("注销异常解冻失败"), anyString());
        verify(d).markFailed(eq(9L), eq("执行异常且解冻失败:IllegalStateException"), any(Date.class));
        verify(im).sendSystem(eq(1001L), eq(ImMessage.MSG_TEXT),
                eq(FAILED_NOTICE), isNull());
    }

    @Test
    void noticeFailureIsAlarmedAndDoesNotThrow() {
        AccountDeregistration blocked = due(9L, 1001L);
        when(d.selectDue(any(Date.class))).thenReturn(Collections.singletonList(blocked));
        when(d.selectByIdForUpdate(9L)).thenReturn(blocked);
        when(m.selectUmsMemberById(1001L)).thenReturn(member(1001L, new BigDecimal("5.00")));
        when(d.markFailed(eq(9L), anyString(), any(Date.class))).thenReturn(1);
        when(im.sendSystem(anyLong(), any(), anyString(), any())).thenThrow(new IllegalStateException("im-down"));

        assertEquals(0, service.executeDue(new Date()));

        verify(a).alarm(eq("注销失败站内信未送达"), startsWith("member=1001"));
    }
}
