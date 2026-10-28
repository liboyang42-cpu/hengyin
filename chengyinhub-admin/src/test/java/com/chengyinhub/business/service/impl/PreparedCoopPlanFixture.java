package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsRegistration;
import java.util.*;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.dto.CoopGroupSettlementBasis;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.domain.dto.CoopSettlementOriginLine;
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
        // Composition-only fixture: production producer/ledger persistence has its own explicit tests.
        ReflectionTestUtils.setField(service, "coopSettlementOriginalProducer", mock(CoopSettlementOriginalProducer.class));
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
            snapshot.setBeneficiaryRole(club ? "CLUB" : "MERCHANT");
            snapshot.setBeneficiaryId(order.getToId());
            snapshot.setServiceUnit(club ? "ORGANIZER_LEAD" : "MERCHANT_PARTICIPATION");
            snapshot.setCommercialContextType("TOPIC_EDITION"); snapshot.setCommercialContextId(topicId);
            snapshot.setScopeType("TOPIC"); snapshot.setScopeId(topicId);
            snapshot.setCompensationMode(Integer.valueOf(0).equals(order.getShareMode()) ? "NONE"
                    : Integer.valueOf(1).equals(order.getShareMode()) ? "SALES_PERCENT" : "FIXED_PER_FULFILLMENT");
            snapshot.setShareRate(order.getShareRate());
            snapshot.setFixedFee(order.getFixedFee());
            // Explicit composition inputs, not fabricated scan/seat/audit receipts.
            // Keep the original scalar head-count oracle (including zero) and its
            // amount; attach the declared original registration scope and exact
            // attribution so the real cross-order invariant is exercised too.
            List<Long> scopeIds = registrations.selectVerifiedRegistrationIdsByTopicId(topicId);
            List<CoopSettlementOriginLine> scope = new ArrayList<>();
            if (scopeIds != null) for (Long registrationId : scopeIds) {
                CoopSettlementOriginLine line = new CoopSettlementOriginLine(registrationId,
                        scope.isEmpty() ? amount : BigDecimal.ZERO);
                line.setEligibleSalesSnapshot(scope.isEmpty() ? sales : BigDecimal.ZERO);
                line.setSettlementRoute("COOP_ORDER"); line.setSourceContractType("COOP_ORDER");
                line.setSourceContractId(order.getId());
                line.setCommercialContextType(snapshot.getCommercialContextType()); line.setCommercialContextId(topicId);
                line.setBeneficiaryRole(snapshot.getBeneficiaryRole()); line.setBeneficiaryId(snapshot.getBeneficiaryId());
                line.setServiceUnit(snapshot.getServiceUnit()); scope.add(line);
            }
            CoopSettlementPlan plan = mock(CoopSettlementPlan.class);
            when(plan.getRoute()).thenReturn("NONE".equals(snapshot.getCompensationMode()) ? "NONE" : "COOP_ORDER");
            when(plan.getSnapshot()).thenReturn(snapshot);
            when(plan.getOriginLines()).thenReturn(List.copyOf(scope));
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
        CoopSettlementEvidenceMapper evidence = mock(CoopSettlementEvidenceMapper.class);
        when(evidence.selectTopicCashRowsForUpdate(any())).thenAnswer(call -> {
            Long topicId = call.getArgument(0);
            BigDecimal total = currentSales.get();
            if (total == null) total = registrations.selectTotalSalesByTopicId(topicId);
            if (total == null) total = BigDecimal.ZERO;
            List<CmsRegistration> rows = new ArrayList<>();
            CmsRegistration cash = cashRow(900000L, topicId, total, 2, false, null);
            rows.add(cash);
            // 显式组合夹具仍消费旧 stub 输入；生产代码不再读取这些普通聚合。
            // status=3 核销行只重建旧审计 SUM/COUNT；现金仍仅由上面的 status=2 行决定。
            BigDecimal verified = registrations.selectVerifiedSalesByTopicId(topicId);
            if (verified == null) verified = BigDecimal.ZERO;
            int members = registrations.selectVerifiedHeadsByTopicId(topicId);
            List<Long> ids = registrations.selectVerifiedRegistrationIdsByTopicId(topicId);
            if (ids == null || ids.isEmpty()) {
                ids = new ArrayList<>();
                for (int i=0; i<Math.max(members,verified.signum()==0?0:1); i++) ids.add(10000L+i);
            }
            for (int i=0; i<ids.size(); i++) {
                rows.add(cashRow(ids.get(i),topicId,i==0?verified:BigDecimal.ZERO,3,true,
                        members==0?null:100L+(i%members)));
            }
            return rows;
        });
        ReflectionTestUtils.setField(service, "coopSettlementEvidenceMapper", evidence);
    }
    static void settle(CmsTopicServiceImpl service, CmsTopic topic, BigDecimal total) {
        // These old combination scenarios explicitly contain no approved registration refunds.
        OriginalCloseRefundHistoryFixture.install(service, topic.getId(), List.of());
        currentSales.set(total);
        try { service.settleCoopTopic(topic,total); } finally { currentSales.remove(); }
    }
    private static CmsRegistration cashRow(Long id, Long topic, BigDecimal amount, int status,
            boolean verified, Long member) {
        CmsRegistration row = new CmsRegistration(); row.setId(id); row.setOwnerType(1L); row.setOwnerId(topic);
        row.setPaymentStatus(2); row.setRegistrationStatus(status); row.setVerificationStatus(verified?1:0);
        row.setPayableAmount(amount); row.setMemberId(member); return row;
    }
    private static final ThreadLocal<BigDecimal> currentSales = new ThreadLocal<>();
    private static final ThreadLocal<Long> currentTopic = new ThreadLocal<>();
    static void topic(Long topicId) { currentTopic.set(topicId); }
}
