package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MarketingReferralCredential;
import com.chengyinhub.business.domain.dto.PlayerAffiliateBucketRow;
import com.chengyinhub.business.domain.dto.PlayerAffiliateCampaignRow;
import com.chengyinhub.business.domain.dto.PlayerAffiliateCredentialRow;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import com.chengyinhub.business.domain.vo.PlayerAffiliateSummaryVO;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.mapper.PlayerAffiliateMapper;
import com.chengyinhub.business.service.impl.PlayerAffiliateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 玩家推广收入只读契约：
 * <ul>
 *   <li>身份只取调用者；查询参数永远是调用者 id，不会读他人；</li>
 *   <li>退款冲销体现在 reversed 桶的 reversedFen/netFen 与 campaign 的 refundedOrders；</li>
 *   <li>已支付未应计的单只进 held 计数，不给金额，不给可提现能力。</li>
 * </ul>
 */
class PlayerAffiliateServiceTest {

    private static final long PLAYER = 7L;

    private PlayerAffiliateMapper mapper;
    private PlayerAffiliateServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(PlayerAffiliateMapper.class);
        service = new PlayerAffiliateServiceImpl(mapper);
    }

    @Test
    void unsignedCallerIsRejectedBeforeAnyRead() {
        MarketingRevenueException rejection = assertThrows(MarketingRevenueException.class,
                () -> service.summary(0L, 20));

        assertEquals(401, rejection.getHttpStatus());
        assertEquals("LOGIN_REQUIRED", rejection.getReasonCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void everyQueryIsScopedToTheVerifiedCaller() {
        when(mapper.listOwnCredentials(eq(PLAYER), anyInt())).thenReturn(Collections.<PlayerAffiliateCredentialRow>emptyList());
        when(mapper.listOwnCampaigns(eq(PLAYER), anyInt())).thenReturn(Collections.<PlayerAffiliateCampaignRow>emptyList());
        when(mapper.listReferrerBuckets(eq(PLAYER))).thenReturn(Collections.<PlayerAffiliateBucketRow>emptyList());

        service.summary(PLAYER, 20);

        verify(mapper).listOwnCredentials(eq(PLAYER), eq(20));
        verify(mapper).listOwnCampaigns(eq(PLAYER), eq(20));
        verify(mapper).listReferrerBuckets(eq(PLAYER));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void refundReversalIsReflectedInBucketsAndCampaignCounts() {
        PlayerAffiliateBucketRow accrue = bucketRow(MarketingRevenueAllocation.STATUS_REVERSED,
                MarketingRevenueAllocationLine.ENTRY_ACCRUE, 1000L, 1L);
        PlayerAffiliateBucketRow reverse = bucketRow(MarketingRevenueAllocation.STATUS_REVERSED,
                MarketingRevenueAllocationLine.ENTRY_REVERSE, -400L, 1L);
        when(mapper.listReferrerBuckets(PLAYER)).thenReturn(Arrays.asList(accrue, reverse));
        when(mapper.listOwnCampaigns(eq(PLAYER), anyInt())).thenReturn(Arrays.asList(refundedCampaign()));

        PlayerAffiliateSummaryVO out = service.summary(PLAYER, 20);

        PlayerAffiliateSummaryVO.Bucket reversed = out.getAllocations().get("reversed");
        assertEquals(1000L, reversed.getAccruedFen());
        assertEquals(400L, reversed.getReversedFen());
        assertEquals(600L, reversed.getNetFen());
        assertEquals(new BigDecimal("6.00"), reversed.getNetAmount());
        assertEquals("CNY", reversed.getCurrency());
        assertEquals(1L, reversed.getAllocationCount());
        assertEquals(1L, out.getCampaigns().get(0).getRefundedOrders());
        assertEquals(new BigDecimal("10.00"), out.getCampaigns().get(0).getRefundedAmount());
    }

    @Test
    void paidButNotAccruedOrdersAreHeldWithoutInventedAmounts() {
        PlayerAffiliateCampaignRow campaign = refundedCampaign();
        campaign.setHeldOrders(2L);
        when(mapper.listOwnCampaigns(eq(PLAYER), anyInt())).thenReturn(Arrays.asList(campaign));
        when(mapper.listReferrerBuckets(PLAYER)).thenReturn(Collections.<PlayerAffiliateBucketRow>emptyList());

        PlayerAffiliateSummaryVO out = service.summary(PLAYER, 20);

        assertEquals(2L, out.getHeld().getOrders());
        assertNull(out.getHeld().getAmountFen());
        assertEquals("ALLOCATION_NOT_YET_ACCRUED", out.getHeld().getReason());
    }

    @Test
    void payoutStaysUnavailableWithoutFakeAvailableToWithdraw() {
        when(mapper.listReferrerBuckets(PLAYER)).thenReturn(Collections.<PlayerAffiliateBucketRow>emptyList());

        PlayerAffiliateSummaryVO out = service.summary(PLAYER, 20);

        assertFalse(out.getPayout().isAvailable());
        assertEquals("NO_PLAYER_PAYOUT_PATH", out.getPayout().getReason());
        assertNull(out.getPayout().getEndpoint());
        assertEquals("/api/marketing/referral/token", out.getLinkIssueEndpoint());
        assertTrue(out.getAllocations().containsKey("pending"));
        assertTrue(out.getAllocations().containsKey("confirmed"));
        assertTrue(out.getAllocations().containsKey("paid"));
    }

    @Test
    void credentialWindowIsReportedAgainstServerClock() {
        Date now = new Date();
        PlayerAffiliateCredentialRow active = credentialRow(MarketingReferralCredential.STATUS_ACTIVE,
                new Date(now.getTime() + TimeUnit.DAYS.toMillis(3)));
        PlayerAffiliateCredentialRow expired = credentialRow(MarketingReferralCredential.STATUS_ACTIVE,
                new Date(now.getTime() - TimeUnit.DAYS.toMillis(1)));
        PlayerAffiliateCredentialRow revoked = credentialRow(MarketingReferralCredential.STATUS_REVOKED,
                new Date(now.getTime() + TimeUnit.DAYS.toMillis(3)));
        when(mapper.listOwnCredentials(eq(PLAYER), anyInt())).thenReturn(Arrays.asList(active, expired, revoked));
        when(mapper.listReferrerBuckets(PLAYER)).thenReturn(Collections.<PlayerAffiliateBucketRow>emptyList());

        PlayerAffiliateSummaryVO out = service.summary(PLAYER, 20);

        assertEquals("active", out.getCredentials().get(0).getStatusText());
        assertTrue(out.getCredentials().get(0).isActive());
        assertEquals("expired", out.getCredentials().get(1).getStatusText());
        assertFalse(out.getCredentials().get(1).isActive());
        assertEquals("revoked", out.getCredentials().get(2).getStatusText());
        assertFalse(out.getCredentials().get(2).isActive());
        // 凭据读面不携带 token 摘要/明文（类型本身没有该字段），且没有写操作。
    }

    @Test
    void limitIsClampedToAServerBound() {
        when(mapper.listReferrerBuckets(PLAYER)).thenReturn(Collections.<PlayerAffiliateBucketRow>emptyList());

        service.summary(PLAYER, 500);
        service.summary(PLAYER, -3);
        service.summary(PLAYER, null);

        verify(mapper).listOwnCredentials(eq(PLAYER), eq(100));
        verify(mapper, org.mockito.Mockito.times(2)).listOwnCredentials(eq(PLAYER),
                eq(20));
    }

    // ------------------------------------------------------------------ fixtures

    private static PlayerAffiliateBucketRow bucketRow(int status, int entryType, long amountFen, long count) {
        PlayerAffiliateBucketRow row = new PlayerAffiliateBucketRow();
        row.setAllocationStatus(status);
        row.setEntryType(entryType);
        row.setAmountFen(amountFen);
        row.setAllocationCount(count);
        row.setCurrency("CNY");
        return row;
    }

    private static PlayerAffiliateCampaignRow refundedCampaign() {
        PlayerAffiliateCampaignRow row = new PlayerAffiliateCampaignRow();
        row.setCampaignKey("share");
        row.setTargetType(MarketingReferralCredential.TARGET_TOPIC);
        row.setTargetId(42L);
        row.setTargetTitle("老城谜案");
        row.setAttributedOrders(3L);
        row.setPaidOrders(2L);
        row.setPaidAmount(new BigDecimal("20.00"));
        row.setRefundedOrders(1L);
        row.setRefundedAmount(new BigDecimal("10.00"));
        return row;
    }

    private static PlayerAffiliateCredentialRow credentialRow(int status, Date expiresAt) {
        PlayerAffiliateCredentialRow row = new PlayerAffiliateCredentialRow();
        row.setCampaignKey("share");
        row.setTargetType(MarketingReferralCredential.TARGET_TOPIC);
        row.setTargetId(42L);
        row.setTargetTitle("老城谜案");
        row.setStatus(status);
        row.setIssuedAt(new Date(expiresAt.getTime() - TimeUnit.DAYS.toMillis(30)));
        row.setExpiresAt(expiresAt);
        return row;
    }
}
