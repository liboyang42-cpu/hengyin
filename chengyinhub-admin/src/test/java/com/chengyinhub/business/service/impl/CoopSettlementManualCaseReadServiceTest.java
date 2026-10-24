package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CoopSettlementManualCaseReadMapper;
import com.chengyinhub.business.domain.vo.CoopSettlementManualCaseView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoopSettlementManualCaseReadServiceTest {
    final CoopSettlementManualCaseReadMapper rows=mock(CoopSettlementManualCaseReadMapper.class);
    final CoopSettlementManualCaseReadService reader=new CoopSettlementManualCaseReadService(rows);
    @Test void listUsesTheExplicitTopicAndManualStateWithoutLoadingSourceSnapshots() {
        var record=new CoopSettlementManualCaseView();record.setId(1L);record.setTopicId(11L);
        when(rows.selectList(11L,"MANUAL_REQUIRED")).thenReturn(List.of(record));
        assertEquals(List.of(record),reader.list(11L,"MANUAL_REQUIRED"));
        verify(rows).selectList(11L,"MANUAL_REQUIRED");verifyNoMoreInteractions(rows);
    }
    @Test void detailProjectsBusinessSourcesAndNeverReturnsPaymentIdentifiersOrRawJson() throws Exception {
        var record=record();record.setSourceSnapshot("""
            {"version":"CLASSIC_ORIGINAL_LARGEST_REMAINDER_V2","payerRegistrationId":31,"originalCash":10.01,"allocatedCost":12.01,
             "paymentFactId":991,"paymentFactHash":"private-payment-hash","transactionId":"private-tx","mchid":"private-mch","appid":"private-app",
             "originalComponents":[{"sourceRegistrationId":31,"sourceClaimId":51,"settlementId":61,"lineType":"MERCHANT_VERIFY","originalAmount":12.01,"paymentFactHash":"private-payment-hash"}]}
            """);
        when(rows.selectById(1L)).thenReturn(record);
        var detail=reader.detail(1L);assertEquals(11L,detail.getTopicId());assertEquals("MANUAL_REQUIRED",detail.getState());
        assertEquals(31L,detail.getSource().getPayerRegistrationId());assertEquals(new BigDecimal("10.01"),detail.getSource().getOriginalCash());
        assertEquals(51L,detail.getSource().getLines().get(0).getSourceClaimId());
        String json=new ObjectMapper().writeValueAsString(detail);
        for(String forbidden:List.of("private-payment-hash","private-tx","private-mch","private-app","paymentFactId","sourceSnapshot"))assertFalse(json.contains(forbidden),forbidden);
    }
    @Test void originalHeadFeeTermsAreShownAsFactsWithoutAddingARefundabilityDecision() {
        var record=record();record.setSourceSnapshot("""
            {"version":"CLASSIC_ORIGINAL_LARGEST_REMAINDER_V2","sales":10.00,"platform":1.00,"guarantee":0.50,"merchant":12.00,
             "originalMerchantHeads":[{"coopOrderId":71,"lineType":"MERCHANT_VERIFY","amount":12.00,"shareMode":2,"fixedFee":3.00,"depositOutTradeNo":"private-provider-no"}]}
            """);
        when(rows.selectById(1L)).thenReturn(record);var source=reader.detail(1L).getSource();
        assertEquals(0,new BigDecimal("12.00").compareTo(source.getMerchantAmount()));
        assertEquals(71L,source.getLines().get(0).getCoopOrderId());assertEquals(2,source.getLines().get(0).getShareMode());
        assertEquals(0,new BigDecimal("3.00").compareTo(source.getLines().get(0).getFixedFee()));
    }
    @Test void malformedSourcePreservesVisibleTopicReasonAndStateWithoutReturningParserContent() throws Exception {
        var record=record();record.setSourceSnapshot("{private-provider-no");when(rows.selectById(1L)).thenReturn(record);
        var detail=reader.detail(1L);assertEquals(11L,detail.getTopicId());assertEquals("NEGATIVE_ORGANIZER_MARGIN",detail.getReason());assertFalse(detail.getSource().isAvailable());
        assertFalse(new ObjectMapper().writeValueAsString(detail).contains("private-provider-no"));
    }
    @Test void invalidScopeOrStatusIsRejectedBeforeAnyReadAndMissingDetailStaysMissing() {
        assertThrows(IllegalArgumentException.class,()->reader.list(0L,"MANUAL_REQUIRED"));
        assertThrows(IllegalArgumentException.class,()->reader.list(11L,"DONE"));
        assertThrows(IllegalArgumentException.class,()->reader.detail(0L));verifyNoInteractions(rows);
        assertNull(reader.detail(99L));
    }
    @Test void readOwnerIsExplicitlyReadOnlyAndPersistenceSnapshotIsNotSerializable() throws Exception {
        assertTrue(CoopSettlementManualCaseReadService.class.getAnnotation(Transactional.class).readOnly());
        var row=record();row.setSourceSnapshot("private-tx");assertFalse(new ObjectMapper().writeValueAsString(row).contains("private-tx"));
    }
    private CoopSettlementManualCaseReadMapper.Record record(){var r=new CoopSettlementManualCaseReadMapper.Record();r.setId(1L);r.setTopicId(11L);r.setReason("NEGATIVE_ORGANIZER_MARGIN");r.setState("MANUAL_REQUIRED");r.setNegativeAmount(new BigDecimal("-2.00"));return r;}
}
