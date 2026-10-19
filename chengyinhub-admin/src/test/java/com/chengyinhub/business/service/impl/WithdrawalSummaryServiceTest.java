package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberWithdrawalMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.common.config.ComplianceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** F0422-F0425：提现页回显只读本人，可提现额与建单扣减同口径，收款资料只出脱敏值。 */
class WithdrawalSummaryServiceTest {
    private static final Long MEMBER = 7L;

    private UmsMemberMapper members;
    private UmsMemberWithdrawalMapper withdrawals;
    private MerchantReferralBountyService bounty;
    private IPlayerWithdrawalCooldownService cooldown;
    private IConsentRecordService consents;
    private WithdrawalSummaryService service;

    @BeforeEach
    void setUp() {
        members = mock(UmsMemberMapper.class);
        withdrawals = mock(UmsMemberWithdrawalMapper.class);
        bounty = mock(MerchantReferralBountyService.class);
        cooldown = mock(IPlayerWithdrawalCooldownService.class);
        consents = mock(IConsentRecordService.class);
        ComplianceProperties compliance = new ComplianceProperties();
        compliance.setDocVersions(Collections.singletonMap("bank_account_collection", "v1.0"));
        service = new WithdrawalSummaryService(members, withdrawals,
                new WithdrawalFrozenAmountResolver(bounty, cooldown), consents, compliance);
        when(bounty.frozenAmount(anyLong())).thenReturn(BigDecimal.ZERO);
        when(cooldown.frozenAmount(anyLong())).thenReturn(BigDecimal.ZERO);
    }

    private void balance(String value) {
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setBalance(value == null ? null : new BigDecimal(value));
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
    }

    @Test
    void availableExcludesFrozenPartsAndChannelStaysCustomerService() {
        balance("100.00");
        when(bounty.frozenAmount(MEMBER)).thenReturn(new BigDecimal("30.00"));
        when(cooldown.frozenAmount(MEMBER)).thenReturn(new BigDecimal("20.50"));

        Map<String, Object> out = service.summaryFor(MEMBER);

        assertEquals("CUSTOMER_SERVICE", out.get("channel"));
        assertEquals(Boolean.FALSE, out.get("legacyFormOpen"));
        assertEquals(new BigDecimal("100.00"), out.get("balance"));
        assertEquals(new BigDecimal("50.50"), out.get("frozen"));
        assertEquals(new BigDecimal("49.50"), out.get("available"));
        assertTrue(String.valueOf(out.get("frozenNote")).contains("30.00"));
    }

    @Test
    void availableNeverNegativeAndNullBalanceIsZero() {
        balance(null);
        when(cooldown.frozenAmount(MEMBER)).thenReturn(new BigDecimal("5.00"));

        Map<String, Object> out = service.summaryFor(MEMBER);

        assertEquals(BigDecimal.ZERO, out.get("available"));
        assertEquals(BigDecimal.ZERO, out.get("balance"));
    }

    @Test
    void frozenLookupFailureFailsClosedInsteadOfShowingTotalBalance() {
        balance("100.00");
        when(bounty.frozenAmount(MEMBER)).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> service.summaryFor(MEMBER));
    }

    @Test
    void consentVersionAndAgreementAreReadForCurrentDocumentOnly() {
        balance("10.00");
        when(consents.hasCurrentAgreement(MEMBER, "bank_account_collection", "withdrawal", "v1.0"))
                .thenReturn(true);

        Map<String, Object> out = service.summaryFor(MEMBER);

        assertEquals("bank_account_collection", out.get("consentDocument"));
        assertEquals("v1.0", out.get("consentVersion"));
        assertEquals(Boolean.TRUE, out.get("consentAgreed"));
    }

    @Test
    void latestRequestShowsFrozenSnapshotMaskAndBoundConsentVersionWithoutPlainPii() {
        balance("10.00");
        UmsMemberWithdrawal row = new UmsMemberWithdrawal();
        row.setId(91L);
        row.setMemberId(MEMBER);
        row.setStatus(0L);
        row.setWithdrawalAmount(new BigDecimal("25.00"));
        row.setBankAccount("6222020000001234");
        row.setRealname("张三");
        row.setBankAccountMask("6222********1234");
        row.setConsentVersion("v0.9");
        when(withdrawals.selectLatestByMember(MEMBER)).thenReturn(row);

        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) service.summaryFor(MEMBER).get("latest");

        assertEquals(91L, latest.get("id"));
        assertEquals(0L, latest.get("status"));
        assertEquals("6222********1234", latest.get("bankAccountMask"));
        assertEquals("v0.9", latest.get("consentVersion"));
        assertFalse(latest.containsKey("bankAccount"));
        assertFalse(latest.containsKey("realname"));
        assertFalse(latest.containsKey("mobilephone"));
    }

    @Test
    void foreignRowIsNeverEchoedAndAnonymousIsRejectedBeforeReads() {
        balance("10.00");
        UmsMemberWithdrawal foreign = new UmsMemberWithdrawal();
        foreign.setId(92L);
        foreign.setMemberId(8L);
        when(withdrawals.selectLatestByMember(MEMBER)).thenReturn(foreign);

        assertNull(service.summaryFor(MEMBER).get("latest"));

        UmsMemberMapper untouched = mock(UmsMemberMapper.class);
        WithdrawalSummaryService anonymous = new WithdrawalSummaryService(untouched, withdrawals,
                new WithdrawalFrozenAmountResolver(bounty, cooldown), consents, new ComplianceProperties());
        assertThrows(IllegalArgumentException.class, () -> anonymous.summaryFor(null));
        assertThrows(IllegalArgumentException.class, () -> anonymous.summaryFor(0L));
        verifyNoInteractions(untouched);
        verify(withdrawals, never()).selectLatestByMember(8L);
    }
}
