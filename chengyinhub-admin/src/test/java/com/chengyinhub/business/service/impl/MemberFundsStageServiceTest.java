package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.MemberActivityFundsRow;
import com.chengyinhub.business.mapper.ActivitySettlementHoldMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 9-16 晚拍板第 5 条:余额三段的分段口径必须与结算闸同源。 */
class MemberFundsStageServiceTest {

    private static final long MEMBER = 8801L;
    private static final long DAY = 24L * 3600 * 1000;

    private ActivitySettlementHoldMapper holdMapper;
    private ICmsActivityService activityService;
    private CoopSettlementMapper coopSettlementMapper;
    private CoopComplaintMapper complaintMapper;
    private MemberFundsStageService service;
    private final Date now = new Date();

    @BeforeEach
    void setUp() {
        holdMapper = mock(ActivitySettlementHoldMapper.class);
        activityService = mock(ICmsActivityService.class);
        // 费率 90%,估算与结算同一入口
        when(activityService.estimateMerchantShare(any(), any()))
                .thenAnswer(i -> ((BigDecimal) i.getArgument(1)).multiply(new BigDecimal("0.9")).setScale(2));
        coopSettlementMapper = mock(CoopSettlementMapper.class);
        complaintMapper = mock(CoopComplaintMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember();
        member.setBalance(new BigDecimal("50.00"));
        when(memberMapper.selectUmsMemberById(MEMBER)).thenReturn(member);
        MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
        when(bounty.frozenAmount(MEMBER)).thenReturn(new BigDecimal("20.00"));
        service = new MemberFundsStageService(holdMapper, activityService, coopSettlementMapper,
                mock(CoopSettlementAdjustmentMapper.class), complaintMapper, memberMapper,
                new WithdrawalFrozenAmountResolver(bounty, mock(IPlayerWithdrawalCooldownService.class)));
    }

    @Test
    void splitsPendingComplaintPeriodDisputedAndWithdrawable() {
        MemberActivityFundsRow notEnded = row(1L, now.getTime() + DAY, "100", "0", null);
        MemberActivityFundsRow ended = row(2L, now.getTime() - 2 * DAY, "200", "60", null);
        // 结束 2 天但最晚一张票退款截止在 10 天后 ⇒ 可提现日取较晚的那个
        MemberActivityFundsRow lateDeadline = row(3L, now.getTime() - 2 * DAY, "10", "0", new Date(now.getTime() + 10 * DAY));
        when(holdMapper.selectUnsettledActivityFundsByMember(MEMBER)).thenReturn(Arrays.asList(notEnded, ended, lateDeadline));
        when(holdMapper.selectHeldActivityFundsByMember(MEMBER))
                .thenReturn(Collections.singletonList(row(4L, now.getTime() - 9 * DAY, "0", "40", null)));
        CoopSettlement coop = new CoopSettlement();
        coop.setId(9L);
        coop.setTopicId(77L);
        coop.setStatus(0);
        coop.setAmount(new BigDecimal("30.00"));
        coop.setPayableTime(new Date(now.getTime() + DAY));
        when(coopSettlementMapper.selectByMerchant(MEMBER)).thenReturn(Collections.singletonList(coop));

        Map<String, Object> d = service.stages(MEMBER, now);

        assertMoney("90.00", d.get("pendingSettlement"));
        assertMoney("90.00", d.get("disputed")); // 60×0.9 + 40×0.9
        assertMoney("30.00", d.get("withdrawable")); // 余额 50 − 拓展赏金冻结 20
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> period = (List<Map<String, Object>>) d.get("complaintPeriod");
        assertEquals(3, period.size(), String.valueOf(period));
        assertEquals(day(now.getTime() + DAY), period.get(0).get("availableDate"));
        assertMoney("30.00", period.get(0).get("amount"));
        assertEquals(day(now.getTime() + 5 * DAY), period.get(1).get("availableDate"));
        assertMoney("180.00", period.get(1).get("amount"));
        assertEquals(day(now.getTime() + 10 * DAY), period.get(2).get("availableDate"));
        assertMoney("9.00", period.get(2).get("amount"));
    }

    @Test
    void coopLineUnderOpenComplaintCountsAsDisputed() {
        CoopSettlement coop = new CoopSettlement();
        coop.setId(9L);
        coop.setTopicId(77L);
        coop.setStatus(0);
        coop.setAmount(new BigDecimal("30.00"));
        when(coopSettlementMapper.selectByMerchant(MEMBER)).thenReturn(Collections.singletonList(coop));
        when(complaintMapper.countOpenByTopicId(77L)).thenReturn(1);

        Map<String, Object> d = service.stages(MEMBER, now);

        assertMoney("30.00", d.get("disputed"));
        assertEquals(Collections.emptyList(), d.get("complaintPeriod"));
    }

    @Test
    void unknownRateHidesActivityAmountsInsteadOfGuessing() {
        org.mockito.Mockito.doReturn(null).when(activityService).estimateMerchantShare(any(), any());
        when(holdMapper.selectUnsettledActivityFundsByMember(MEMBER))
                .thenReturn(Collections.singletonList(row(1L, now.getTime() + DAY, "100", "0", null)));

        Map<String, Object> d = service.stages(MEMBER, now);

        assertEquals(false, d.get("amountsKnown"));
        assertMoney("0", d.get("pendingSettlement"));
    }

    private static MemberActivityFundsRow row(Long id, long endMs, String normal, String disputed, Date deadline) {
        MemberActivityFundsRow r = new MemberActivityFundsRow();
        r.setActivityId(id);
        r.setEndDate(new Date(endMs));
        r.setNormalSales(new BigDecimal(normal));
        r.setDisputedSales(new BigDecimal(disputed));
        r.setLastRefundDeadline(deadline);
        return r;
    }

    private static String day(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date(ms));
    }

    private static void assertMoney(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual), expected + " vs " + actual);
    }
}
