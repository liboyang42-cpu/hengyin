package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.dto.CoopGroupSettlementBasis;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ICoopSettlementClaimService;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 旧五分项组合测试的显式输入计划替身：这些测试只验证组合、守恒、收款人与重复保护。
 * 不证明此处模拟的 CLUB/团等合同已获 runtime 支持；真实 reader/prepare 的拒结另有 WP04 测试。
 */
final class PreparedCoopPlanFixture {
    private PreparedCoopPlanFixture() {}
    static void install(CmsTopicServiceImpl service, CmsRegistrationMapper registrations,
            CoopOrderMapper orders, CoopGroupVerifyMapper groups, CoopSettlementMapper heads) {
        ICoopSettlementClaimService claims = mock(ICoopSettlementClaimService.class);
        when(claims.prepare(any(), any())).thenAnswer(call -> {
            CoopOrder order = call.getArgument(0);
            Long topicId = order.getTopicId();
            // 旧夹具没有 order.topicId；从本次显式的组合测试方法参数读取。
            if (topicId == null) topicId = currentTopic.get();
            boolean club = "club".equals(order.getToType());
            boolean organizer = orders.selectEffectiveOrdersByTopicId(topicId).stream()
                    .anyMatch(row -> "club".equals(row.getToType()));
            int count;
            BigDecimal sales;
            if (club) {
                count = registrations.selectVerifiedHeadsByTopicId(topicId);
                sales = registrations.selectVerifiedSalesByTopicId(topicId);
            } else if (organizer && groups != null) {
                CoopGroupSettlementBasis basis = groups.selectSettlementBasisByTopicAndMerchant(topicId, order.getToId());
                count = basis == null || basis.getVerifiedHeads() == null ? 0 : basis.getVerifiedHeads();
                sales = basis == null ? BigDecimal.ZERO : basis.getVerifiedSales();
            } else {
                count = registrations.selectVerifiedHeadsByTopicAndMerchant(topicId, order.getToId());
                sales = registrations.selectVerifiedSalesByTopicAndMerchant(topicId, order.getToId());
            }
            if (sales == null) sales = BigDecimal.ZERO;
            BigDecimal amount = Integer.valueOf(1).equals(order.getShareMode())
                    ? sales.multiply(order.getShareRate()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)
                    : Integer.valueOf(2).equals(order.getShareMode())
                        ? order.getFixedFee().multiply(BigDecimal.valueOf(count)).setScale(2) : BigDecimal.ZERO;
            CoopSettlementSnapshot snapshot = new CoopSettlementSnapshot();
            snapshot.setOrderId(order.getId());
            snapshot.setTopicId(topicId);
            snapshot.setInitiatorMemberId(900001L);
            snapshot.setCompensationMode(Integer.valueOf(1).equals(order.getShareMode()) ? "SALES_PERCENT" : "FIXED_PER_FULFILLMENT");
            snapshot.setShareRate(order.getShareRate());
            snapshot.setFixedFee(order.getFixedFee());
            CoopSettlementPlan plan = mock(CoopSettlementPlan.class);
            when(plan.getRoute()).thenReturn("COOP_ORDER");
            when(plan.getSnapshot()).thenReturn(snapshot);
            when(plan.getVerifiedHeads()).thenReturn(count);
            when(plan.getEligibleSales()).thenReturn(sales);
            when(plan.getOriginAmount()).thenReturn(amount);
            return plan;
        });
        doAnswer(call -> {
            CoopSettlement head = call.getArgument(1);
            if (head != null && heads.insertCoopSettlement(head) != 1) throw new IllegalStateException("fixture insert failed");
            return null;
        }).when(claims).persist(any(), any());
        ReflectionTestUtils.setField(service, "coopSettlementClaimService", claims);
        ReflectionTestUtils.setField(service, "coopSettlementEvidenceMapper", mock(CoopSettlementEvidenceMapper.class));
    }
    private static final ThreadLocal<Long> currentTopic = new ThreadLocal<>();
    static void topic(Long topicId) { currentTopic.set(topicId); }
}
