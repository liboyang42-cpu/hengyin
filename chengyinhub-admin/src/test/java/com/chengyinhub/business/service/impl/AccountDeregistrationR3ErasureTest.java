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
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HIGH-S6:注销执行必须覆盖 r3 个人数据表(领地/城市大富翁/属性/游戏内频道/外部游戏/随机下发),
 * 且老库缺某张 r3 表时只跳过该表,不中止整个注销。
 */
class AccountDeregistrationR3ErasureTest {

    private static final long MEMBER = 1001L;

    @Test
    void executeDueErasesEveryR3PersonalTable() {
        AccountDeregistrationMapper d = mock(AccountDeregistrationMapper.class);
        AccountDeregistrationServiceImpl service = wired(d);

        assertEquals(1, service.executeDue(new Date()));

        verify(d).deleteTerritoryCheckins(MEMBER);       // roam_territory_checkin
        verify(d).revokeTerritories(MEMBER);             // roam_territory → REVOKED + 清照片
        verify(d).scrubTerritoryAudits(MEMBER);          // roam_territory_audit 清坐标
        verify(d).deleteClaimEvidence(MEMBER);           // city_claim_evidence
        verify(d).deleteOnsiteTaskSubmissions(MEMBER);   // city_onsite_task_submission
        verify(d).deleteDeviceAccounts(MEMBER);          // city_device_account
        verify(d).deleteMemberAttributes(MEMBER);        // member_attribute
        verify(d).deleteMemberTitles(MEMBER);            // member_title
        verify(d).deleteMemberArchetype(MEMBER);         // member_archetype
        verify(d).deleteInGameChannelMemberships(MEMBER); // im_conversation_member(游戏内频道)
        verify(d).deleteNodeTaskDispatch(MEMBER);        // node_task_dispatch
        verify(d).scrubMonopolyQuotes(MEMBER);           // city_monopoly_quote
        verify(d).scrubMonopolyReceipts(MEMBER);         // city_monopoly_receipt
        verify(d).scrubYieldEventDevices(MEMBER);        // city_yield_event.device_key
        verify(d).scrubAbuseSignals(MEMBER);             // city_abuse_signal.detail_json
        verify(d).scrubExternalGamePlaySessions(MEMBER); // external_game_play_session.client_ip_hash
        verify(d).complete(eq(9L), any(Date.class));

        // r3 擦除在会话吊销前、同一 mutation 内完成
        IAppSessionRegistryService registry = (IAppSessionRegistryService)
                ReflectionTestUtils.getField(service, "appSessionRegistryService");
        var order = inOrder(d, registry);
        order.verify(d).anonymizeMember(MEMBER);
        order.verify(d).scrubExternalGamePlaySessions(MEMBER);
        order.verify(registry).revokeAll(MEMBER);
    }

    @Test
    void missingR3TableOnOlderDatabaseDoesNotAbortDeregistration() {
        AccountDeregistrationMapper d = mock(AccountDeregistrationMapper.class);
        when(d.deleteClaimEvidence(anyLong())).thenThrow(new BadSqlGrammarException("deleteClaimEvidence",
                "delete from city_claim_evidence", new SQLException("Table doesn't exist", "42S02", 1146)));
        AccountDeregistrationServiceImpl service = wired(d);

        assertEquals(1, service.executeDue(new Date()));

        verify(d).deleteMemberAttributes(MEMBER);
        verify(d).scrubExternalGamePlaySessions(MEMBER);
        verify(d).complete(eq(9L), any(Date.class));
    }

    private AccountDeregistrationServiceImpl wired(AccountDeregistrationMapper d) {
        AccountDeregistration due = new AccountDeregistration();
        due.setId(9L);
        due.setMemberId(MEMBER);
        due.setStatus("PENDING");
        due.setExecuteAfter(new Date(0));
        when(d.selectByIdForUpdate(9L)).thenReturn(due);
        when(d.selectDue(any(Date.class))).thenReturn(Collections.singletonList(due));
        when(d.complete(eq(9L), any(Date.class))).thenReturn(1);

        UmsMemberMapper m = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setBalance(BigDecimal.ZERO);
        member.setDelFlag(0);
        when(m.selectUmsMemberById(MEMBER)).thenReturn(member);
        OmsOrderMapper o = mock(OmsOrderMapper.class);
        when(o.selectOmsOrderList(any(OmsOrder.class))).thenReturn(Collections.emptyList());
        CmsRegistrationMapper r = mock(CmsRegistrationMapper.class);
        when(r.selectCmsRegistrationList(any(CmsRegistration.class))).thenReturn(Collections.emptyList());
        PlatformTransactionManager t = mock(PlatformTransactionManager.class);
        when(t.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.beginMemberMutation(anyLong())).thenAnswer(inv -> "mutation-" + inv.getArgument(0));

        AccountDeregistrationServiceImpl s = new AccountDeregistrationServiceImpl();
        ReflectionTestUtils.setField(s, "clubMapper", mock(ClubMapper.class));
        ReflectionTestUtils.setField(s, "merchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(s, "deregistrationMapper", d);
        ReflectionTestUtils.setField(s, "memberMapper", m);
        ReflectionTestUtils.setField(s, "orderMapper", o);
        ReflectionTestUtils.setField(s, "registrationMapper", r);
        ReflectionTestUtils.setField(s, "cashWithdrawService", mock(ICashWithdrawService.class));
        ReflectionTestUtils.setField(s, "memberWithdrawalService", mock(IUmsMemberWithdrawalService.class));
        ReflectionTestUtils.setField(s, "transactionManager", t);
        ReflectionTestUtils.setField(s, "alarmService", mock(AlarmService.class));
        ReflectionTestUtils.setField(s, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(s, "communityPostMapper", mock(CommunityPostMapper.class));
        ReflectionTestUtils.setField(s, "roamHangoutService", mock(IRoamHangoutService.class));
        return s;
    }
}
