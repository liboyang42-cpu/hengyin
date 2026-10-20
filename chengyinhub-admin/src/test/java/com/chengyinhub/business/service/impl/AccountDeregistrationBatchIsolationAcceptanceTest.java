package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AccountDeregistration;
import com.chengyinhub.business.domain.CmsRegistration;
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
import com.chengyinhub.business.service.IRoamHangoutService;
import com.chengyinhub.business.service.IUmsMemberWithdrawalService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收 SC-DEREG-08(F-L5S-05)可单测部分:执行抛错的申请当批即转 FAILED 并解冻账号,不再卡在 PENDING;
 * 且它排在前面也不拖住同批后续申请。计数阈值与通知会员两项在 master 上不存在(见 PR 证据表 DEFECT_FOUND);
 * 撤销与旧批次的行锁互斥需真库(AccountDeregistrationRaceMySqlIT)。
 */
class AccountDeregistrationBatchIsolationAcceptanceTest {

    private static AccountDeregistration due(long id, long memberId) {
        AccountDeregistration d = new AccountDeregistration();
        d.setId(id);
        d.setMemberId(memberId);
        d.setStatus("PENDING");
        d.setExecuteAfter(new Date(0));
        return d;
    }

    @Test
    void failingRequestLeavesPendingAndDoesNotBlockLaterRequestsInTheBatch() {
        AccountDeregistrationMapper d = mock(AccountDeregistrationMapper.class);
        UmsMemberMapper m = mock(UmsMemberMapper.class);
        OmsOrderMapper o = mock(OmsOrderMapper.class);
        CmsRegistrationMapper r = mock(CmsRegistrationMapper.class);
        ICashWithdrawService c = mock(ICashWithdrawService.class);
        PlatformTransactionManager t = mock(PlatformTransactionManager.class);
        AlarmService a = mock(AlarmService.class);
        AccountDeregistration broken = due(9L, 1001L);
        AccountDeregistration healthy = due(10L, 1002L);
        when(d.selectDue(any(Date.class))).thenReturn(Arrays.asList(broken, healthy));
        when(d.selectByIdForUpdate(9L)).thenReturn(broken);
        when(d.selectByIdForUpdate(10L)).thenReturn(healthy);
        when(m.selectUmsMemberById(1001L)).thenThrow(new IllegalStateException("synthetic-failure"));
        UmsMember member = new UmsMember();
        member.setId(1002L);
        member.setBalance(BigDecimal.ZERO);
        member.setDelFlag(0);
        when(m.selectUmsMemberById(1002L)).thenReturn(member);
        when(o.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        when(r.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        when(d.markFailed(eq(9L), anyString(), any(Date.class))).thenReturn(1);
        when(d.complete(eq(10L), any(Date.class))).thenReturn(1);
        when(t.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        AccountDeregistrationServiceImpl service = wired(d, m, o, r, c, t, a);

        assertEquals(1, service.executeDue(new Date()));
        verify(d).markFailed(eq(9L), eq("执行异常:IllegalStateException"), any(Date.class));
        verify(d).markMemberNormal(1001L);
        verify(d, never()).anonymizeMember(1001L);
        verify(d, never()).complete(eq(9L), any(Date.class));
        verify(d).anonymizeMember(1002L);
        verify(d).complete(eq(10L), any(Date.class));
        verify(a).alarm(eq("注销执行异常"), anyString());
    }

    private AccountDeregistrationServiceImpl wired(AccountDeregistrationMapper d, UmsMemberMapper m,
            OmsOrderMapper o, CmsRegistrationMapper r, ICashWithdrawService c,
            PlatformTransactionManager t, AlarmService a) {
        AccountDeregistrationServiceImpl s = new AccountDeregistrationServiceImpl();
        ReflectionTestUtils.setField(s, "clubMapper", mock(ClubMapper.class));
        ReflectionTestUtils.setField(s, "merchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(s, "deregistrationMapper", d);
        ReflectionTestUtils.setField(s, "memberMapper", m);
        ReflectionTestUtils.setField(s, "orderMapper", o);
        ReflectionTestUtils.setField(s, "registrationMapper", r);
        ReflectionTestUtils.setField(s, "cashWithdrawService", c);
        ReflectionTestUtils.setField(s, "memberWithdrawalService", mock(IUmsMemberWithdrawalService.class));
        ReflectionTestUtils.setField(s, "transactionManager", t);
        ReflectionTestUtils.setField(s, "alarmService", a);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.beginMemberMutation(anyLong())).thenAnswer(inv -> "mutation-" + inv.getArgument(0));
        ReflectionTestUtils.setField(s, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(s, "communityPostMapper", mock(CommunityPostMapper.class));
        ReflectionTestUtils.setField(s, "roamHangoutService", mock(IRoamHangoutService.class));
        return s;
    }
}
