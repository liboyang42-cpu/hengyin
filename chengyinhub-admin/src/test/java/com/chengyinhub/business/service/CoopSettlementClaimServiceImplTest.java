package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.CoopSettlementAdjustment;
import com.chengyinhub.business.domain.CoopSettlementDetail;
import com.chengyinhub.business.domain.SettlementClaim;
import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper;
import com.chengyinhub.business.mapper.CoopSettlementDetailMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.service.impl.CoopSettlementClaimServiceImpl;
import com.chengyinhub.business.service.impl.TopicSettlementEvidenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoopSettlementClaimServiceImplTest {

    @Test
    void threeRegistrationsProduceThreeClaimsThreeDetailsAndHeaderOnlyFromDetails() {
        ICoopOrderSettlementSnapshotReader snapshotReader = mock(ICoopOrderSettlementSnapshotReader.class);
        FulfillmentWindowResolver windowResolver = mock(FulfillmentWindowResolver.class);
        TopicSettlementEvidenceService evidenceService = mock(TopicSettlementEvidenceService.class);
        ISettlementClaimService claimService = mock(ISettlementClaimService.class);
        CoopSettlementMapper headMapper = mock(CoopSettlementMapper.class);
        CoopSettlementDetailMapper detailMapper = mock(CoopSettlementDetailMapper.class);
        CoopSettlementAdjustmentMapper adjustmentMapper = mock(CoopSettlementAdjustmentMapper.class);
        CoopSettlementClaimServiceImpl service = new CoopSettlementClaimServiceImpl(snapshotReader, windowResolver,
                evidenceService, claimService, headMapper, detailMapper, adjustmentMapper);
        wireTopic(service);

        CoopOrder order = order();
        CoopSettlementSnapshot snapshot = snapshot();
        when(snapshotReader.read(order)).thenReturn(snapshot);
        when(windowResolver.endExclusive(snapshot)).thenReturn(new Date(1_000_000L));
        when(evidenceService.lockCandidates(any(), any())).thenReturn(Arrays.asList(
                candidate(3L), candidate(1L), candidate(2L)));
        AtomicLong claimIds = new AtomicLong(10L);
        when(claimService.claim(any())).thenAnswer(invocation -> {
            SettlementClaim claim = invocation.getArgument(0);
            claim.setId(claimIds.getAndIncrement());
            return claim;
        });
        AtomicReference<BigDecimal> amountAtHeadInsert = new AtomicReference<>();
        AtomicReference<Integer> headsAtHeadInsert = new AtomicReference<>();
        when(headMapper.insertCoopSettlement(any())).thenAnswer(invocation -> {
            CoopSettlement head = invocation.getArgument(0);
            amountAtHeadInsert.set(head.getAmount());
            headsAtHeadInsert.set(head.getVerifiedHeads());
            head.setId(501L);
            return 1;
        });
        when(detailMapper.insertIgnoreDetail(any())).thenReturn(1);
        when(detailMapper.refreshOriginAggregate(501L)).thenReturn(1);

        CoopSettlementPlan plan = service.prepare(order, new Date(1_100_000L));
        CoopSettlement head = new CoopSettlement(); head.setTopicId(11L);
        head.setCoopOrderId(order.getId());
        service.persist(plan, head);

        assertAmount("0.02", plan.getOriginAmount(), "头目标额一次 HALF_UP");
        assertAmount("0.00", amountAtHeadInsert.get(), "聚合头插入时必须为 0,不能拿 Java 预估冒充真源");
        assertEquals(Integer.valueOf(0), headsAtHeadInsert.get(), "verifiedHeads 初值必须为 0");
        verify(claimService, times(3)).claim(any(SettlementClaim.class));
        ArgumentCaptor<SettlementClaim> claims = ArgumentCaptor.forClass(SettlementClaim.class);
        verify(claimService, times(3)).claim(claims.capture());
        assertEquals(Arrays.asList(1L, 2L, 3L), claims.getAllValues().stream()
                        .map(SettlementClaim::getFulfillmentId).sorted()
                        .collect(java.util.stream.Collectors.toList()),
                "COOP_ORDER fulfillmentId 固定为 registration.id,不认 scan.id");
        ArgumentCaptor<CoopSettlementDetail> details = ArgumentCaptor.forClass(CoopSettlementDetail.class);
        verify(detailMapper, times(3)).insertIgnoreDetail(details.capture());
        assertEquals(Arrays.asList(10L, 11L, 12L), details.getAllValues().stream()
                        .map(CoopSettlementDetail::getClaimId).sorted()
                        .collect(java.util.stream.Collectors.toList()),
                "每条归属明细必须引用独立 claim_id");
        assertTrue(details.getAllValues().stream().allMatch(detail -> Long.valueOf(501L).equals(detail.getSettlementId())),
                "三条明细必须归属于同一个聚合头");
        verify(detailMapper).refreshOriginAggregate(501L);
        assertAmount("0.02", head.getAmount(), "刷新后内存头与明细和一致");
        assertEquals(Integer.valueOf(3), head.getVerifiedHeads(), "头人头只来自 detail registration 去重数");
        assertEquals("COOP_ORDER", head.getSettlementRoute(), "聚合头保存路线审计列");
        assertEquals(Long.valueOf(701L), head.getSourceContractId(), "聚合头保存来源合同 ID");
    }

    @Test
    void adjustmentAppendsByClaimEventWithoutChangingOriginDetailOrHead() {
        ICoopOrderSettlementSnapshotReader snapshotReader = mock(ICoopOrderSettlementSnapshotReader.class);
        FulfillmentWindowResolver windowResolver = mock(FulfillmentWindowResolver.class);
        TopicSettlementEvidenceService evidenceService = mock(TopicSettlementEvidenceService.class);
        ISettlementClaimService claimService = mock(ISettlementClaimService.class);
        CoopSettlementMapper headMapper = mock(CoopSettlementMapper.class);
        CoopSettlementDetailMapper detailMapper = mock(CoopSettlementDetailMapper.class);
        CoopSettlementAdjustmentMapper adjustmentMapper = mock(CoopSettlementAdjustmentMapper.class);
        CoopSettlementClaimServiceImpl service = new CoopSettlementClaimServiceImpl(snapshotReader, windowResolver,
                evidenceService, claimService, headMapper, detailMapper, adjustmentMapper);
        wireTopic(service);
        CoopSettlementDetail origin = new CoopSettlementDetail();
        origin.setSettlementId(501L);
        origin.setClaimId(11L);
        origin.setRegistrationId(1L);
        origin.setOriginAmount(new BigDecimal("0.01"));
        CoopSettlement originalHead = new CoopSettlement(); originalHead.setId(501L); originalHead.setTopicId(11L);
        when(headMapper.selectByClaimId(11L)).thenReturn(originalHead);
        when(headMapper.selectByIdForUpdate(501L)).thenReturn(originalHead);
        when(detailMapper.selectByClaimIdForUpdate(11L)).thenReturn(origin);
        when(adjustmentMapper.insertIgnoreAdjustment(any())).thenReturn(1);

        service.appendExecutedAdjustment(11L, "REFUND:9001", new BigDecimal("-0.01"));

        ArgumentCaptor<CoopSettlementAdjustment> captured = ArgumentCaptor.forClass(CoopSettlementAdjustment.class);
        verify(adjustmentMapper).insertIgnoreAdjustment(captured.capture());
        assertEquals(Long.valueOf(11L), captured.getValue().getClaimId(), "调整引用原 claim");
        assertEquals("REFUND:9001", captured.getValue().getEventKey(), "eventKey 是不可变业务事件 ID");
        assertAmount("-0.01", captured.getValue().getAmount(), "调整行保留有符号金额");
        assertAmount("0.01", origin.getOriginAmount(), "原始归属明细毛额不得被改写");
        verify(headMapper, never()).updateCoopSettlement(any(CoopSettlement.class));
    }

    private static CoopOrder order() {
        CoopOrder order = new CoopOrder();
        order.setId(701L);
        order.setStatus(0);
        order.setDelFlag(0);
        return order;
    }

    private static CoopSettlementSnapshot snapshot() {
        CoopSettlementSnapshot s = new CoopSettlementSnapshot();
        s.setOrderId(701L);
        s.setTopicId(11L);
        s.setInitiatorMemberId(77L);
        s.setProductType(1);
        s.setTermsVersion("v1");
        s.setCatalogFrozenAt(new Date(0));
        s.setFulfillmentStartInclusive(new Date(0));
        s.setFulfillmentEndExclusive(new Date(1_000_000L));
        s.setCommercialContextType("TOPIC_EDITION");
        s.setCommercialContextId(11L);
        s.setBeneficiaryRole("MERCHANT");
        s.setBeneficiaryId(88L);
        s.setServiceUnit("MERCHANT_PARTICIPATION");
        s.setScopeType("TOPIC");
        s.setScopeId(11L);
        s.setEligiblePurchaseKinds(Arrays.asList("GUIDED_TICKET"));
        s.setFulfillmentEvidenceRule("REGISTRATION_SCAN");
        s.setCompensationMode("SALES_PERCENT");
        s.setShareRate(new BigDecimal("50"));
        return s;
    }

    private static CoopSettlementCandidate candidate(Long id) {
        CoopSettlementCandidate c = new CoopSettlementCandidate();
        c.setRegistrationId(id);
        c.setSourceTopicId(11L);
        c.setOrderQuantity(1L);
        c.setTicketOwnerMatches(true);
        c.setFulfillmentStartInclusive(new Date(0));
        c.setFulfillmentEndExclusive(new Date(1_000_000L));
        c.setOwnerType(1L);
        c.setOwnerId(11L);
        c.setRegistrationStatus(2);
        c.setPaymentStatus(2);
        c.setVerificationStatus(1);
        c.setPurchaseKind("GUIDED_TICKET");
        c.setEligibleSalesSnapshot(new BigDecimal("0.01"));
        c.setScopeCovered(true);
        c.setMerchantScanPresent(true);
        c.setMerchantScanTime(new Date(900_000L));
        return c;
    }

    private static void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + ",实际=" + actual);
    }
    private static void wireTopic(CoopSettlementClaimServiceImpl service) {
        com.chengyinhub.business.mapper.CmsTopicMapper topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic(); topic.setId(11L);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenReturn(topic);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"topicMapper",topics);
    }

}
