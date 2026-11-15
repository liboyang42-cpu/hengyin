package com.chengyinhub.business.service.rewardpledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Policy;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Stock;
import com.chengyinhub.business.mapper.rewardpledge.RewardPledgeAdminMapper;
import com.chengyinhub.business.mapper.rewardpledge.RewardPledgeMapper;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.common.exception.ServiceException;

class RewardPledgeAdminServiceTest
{
    private RewardPledgeAdminMapper admin;
    private RewardPledgeMapper ledger;
    private OpsConsoleAudit audit;
    private RewardPledgeAdminService service;

    @BeforeEach
    void setUp()
    {
        admin = mock(RewardPledgeAdminMapper.class);
        ledger = mock(RewardPledgeMapper.class);
        audit = mock(OpsConsoleAudit.class);
        service = new RewardPledgeAdminService(admin, ledger, audit, CashPayoutSwitch.off());
    }

    private void open(String code, String version)
    {
        Policy p = new Policy();
        p.policyCode = code;
        p.ruleVersion = version;
        p.decision = "OPEN";
        when(ledger.findPolicy(code, version)).thenReturn(p);
    }

    private Map<String, Object> confirmed()
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("versionId", "v2");
        v.put("pledgeId", "p1");
        v.put("state", "CONFIRMED");
        v.put("quantity", 30);
        v.put("payloadHash", "abc");
        when(admin.lockVersion("v2")).thenReturn(v);
        when(admin.latestVersionId("p1")).thenReturn("v2");
        return v;
    }

    @Test
    void platformReviewIsClosedByDefault()
    {
        confirmed();
        open("RULE", "r1");
        ServiceException e = assertThrows(ServiceException.class, () -> service.approve(9L, "v2", "r1", null, 1L));
        assertEquals(403, e.getCode());
        verify(admin, never()).freezeByPlatform(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void approveNeedsTheRuleVersionOpenToo()
    {
        confirmed();
        open("PLATFORM_REVIEW", "");
        assertThrows(ServiceException.class, () -> service.approve(9L, "v2", "r1", null, 1L));
        verify(admin, never()).freezeByPlatform(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void approveFreezesStocksAndRecordsNoteAndAudit()
    {
        confirmed();
        open("PLATFORM_REVIEW", "");
        open("RULE", "r1");
        when(admin.freezeByPlatform(eq("v2"), anyString(), eq("r1"), eq(5L))).thenReturn(1);
        Map<String, Object> out = service.approve(9L, "v2", "r1", "看过券面", 5L);
        assertEquals("FROZEN", out.get("state"));
        verify(ledger).insertStock(any(Stock.class));
        verify(ledger).updatePledgeState("p1", "FROZEN");
        verify(admin).insertNote("v2", "p1", "APPROVE", "看过券面", 9L);
        verify(audit).record(eq(9L), eq("REWARD_PLEDGE_APPROVE"), anyString(), any(), any(), any(), eq("看过券面"));
    }

    @Test
    void staleVersionIsAConflict()
    {
        confirmed();
        when(admin.latestVersionId("p1")).thenReturn("v3");
        open("PLATFORM_REVIEW", "");
        ServiceException e = assertThrows(ServiceException.class, () -> service.reject(9L, "v2", "超出范围"));
        assertEquals(409, e.getCode());
        verify(admin, never()).rejectVersion(anyString());
    }

    @Test
    void rejectNeedsANoteAndMarksPledgeRejected()
    {
        confirmed();
        open("PLATFORM_REVIEW", "");
        assertThrows(ServiceException.class, () -> service.reject(9L, "v2", " "));
        when(admin.rejectVersion("v2")).thenReturn(1);
        service.reject(9L, "v2", "券面与承诺不符");
        verify(ledger).updatePledgeState("p1", "REJECTED");
        verify(admin).insertNote("v2", "p1", "REJECT", "券面与承诺不符", 9L);
    }

    @Test
    void policyEditorValidatesCodesVersionsAndReviewer()
    {
        assertThrows(ServiceException.class, () -> service.savePolicy(9L, "PAYOUT", "", "OPEN", null, "x"));
        assertThrows(ServiceException.class, () -> service.savePolicy(9L, "RULE", "", "OPEN", null, "x"));
        assertThrows(ServiceException.class, () -> service.savePolicy(9L, "STORE_HOLD", "v1", "OPEN", null, "x"));
        assertThrows(ServiceException.class, () -> service.savePolicy(9L, "REVIEWER", "", "OPEN", null, "x"));
        assertThrows(ServiceException.class, () -> service.savePolicy(9L, "RULE", "r1", "MAYBE", null, "x"));
        assertThrows(ServiceException.class, () -> service.savePolicy(9L, "RULE", "r1", "OPEN", null, " "));
        service.savePolicy(9L, "RULE", "r1", "OPEN", 5L, "上线第一版规则");
        verify(admin).upsertPolicy("RULE", "r1", "OPEN", null);
        verify(audit).record(eq(9L), eq("REWARD_POLICY_SAVE"), anyString(), any(), any(), any(), eq("上线第一版规则"));
    }
}
