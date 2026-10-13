package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.domain.dto.MerchantCrmMarketingConsentRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmConsentMerchantVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;
import com.chengyinhub.business.service.impl.MerchantCrmMarketingConsentServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantCrmMarketingConsentServiceImplTest {
    private static final long MEMBER = 41L;
    private static final long OWNER = 900L;
    private static final long MERCHANT_ROW = 77L;
    private MerchantCrmMarketingConsentMapper mapper;
    private BusinessAuditEventMapper auditMapper;
    private MerchantCrmMarketingConsentServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(MerchantCrmMarketingConsentMapper.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        service = new MerchantCrmMarketingConsentServiceImpl();
        ReflectionTestUtils.setField(service, "consentMapper", mapper);
        ReflectionTestUtils.setField(service, "auditMapper", auditMapper);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
    }

    @Test
    void absentConsentIsReturnedAsFalseAndNeverImplicitlyOptedIn() {
        MerchantCrmConsentMerchantVO merchant = merchant();
        merchant.setInAppOptedIn(false);
        merchant.setCouponOptedIn(false);
        when(mapper.selectEligibleMerchants(MEMBER)).thenReturn(Collections.singletonList(merchant));

        MerchantCrmConsentMerchantVO out = service.list(MEMBER).get(0);

        assertFalse(out.getInAppOptedIn());
        assertFalse(out.getCouponOptedIn());
        verify(mapper, never()).insertConsent(any());
    }

    @Test
    void explicitOptInRequiresARealRegistrationRelationshipAndAppendsAudit() {
        when(mapper.selectEligibleMerchant(MEMBER, MERCHANT_ROW)).thenReturn(merchant());
        when(mapper.insertConsent(any())).thenAnswer(invocation -> {
            ((MerchantCrmMarketingConsent) invocation.getArgument(0)).setId(8L);
            return 1;
        });

        MerchantCrmMarketingConsent out = service.update(MEMBER, request(true, "IN_APP", "consent-in-1"));

        assertEquals(MerchantCrmMarketingConsent.OPT_IN, out.getEventType());
        assertEquals(OWNER, out.getMerchantOwnerMemberId());
        ArgumentCaptor<MerchantCrmMarketingConsent> saved = ArgumentCaptor.forClass(MerchantCrmMarketingConsent.class);
        verify(mapper).insertConsent(saved.capture());
        assertEquals(MEMBER, saved.getValue().getMemberId());
        verify(auditMapper).insertBusinessAuditEvent(any());
    }

    @Test
    void auditReceiptFailureFailsTheConsentTransactionClosed() {
        when(mapper.selectEligibleMerchant(MEMBER, MERCHANT_ROW)).thenReturn(merchant());
        when(mapper.insertConsent(any())).thenAnswer(invocation -> {
            ((MerchantCrmMarketingConsent) invocation.getArgument(0)).setId(8L);
            return 1;
        });
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.update(MEMBER, request(true, "IN_APP", "consent-audit-0")));

        assertEquals(409, error.getCode());
    }

    @Test
    void missingRelationshipFailsClosedBeforeConsentOrAudit() {
        when(mapper.selectEligibleMerchant(MEMBER, MERCHANT_ROW)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.update(MEMBER, request(true, "COUPON", "consent-in-2")));

        assertEquals(403, error.getCode());
        verify(mapper, never()).insertConsent(any());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void optOutIsAnAppendOnlyEventAndChangedReplayFails() {
        when(mapper.selectEligibleMerchant(MEMBER, MERCHANT_ROW)).thenReturn(merchant());
        when(mapper.insertConsent(any())).thenReturn(1);
        MerchantCrmMarketingConsent out = service.update(MEMBER, request(false, "IN_APP", "consent-out-1"));
        assertEquals(MerchantCrmMarketingConsent.OPT_OUT, out.getEventType());

        MerchantCrmMarketingConsent replay = new MerchantCrmMarketingConsent();
        replay.setChannel("IN_APP");
        replay.setEventType("OPT_OUT");
        when(mapper.selectByRequestId(MEMBER, MERCHANT_ROW, "consent-out-1")).thenReturn(replay);
        assertThrows(ServiceException.class,
                () -> service.update(MEMBER, request(true, "IN_APP", "consent-out-1")));
    }

    @Test
    void clientOwnerCannotRetargetAnExactMerchantRow() {
        MerchantCrmConsentMerchantVO exact = merchant();
        when(mapper.selectEligibleMerchant(MEMBER, MERCHANT_ROW)).thenReturn(exact);
        MerchantCrmMarketingConsentRequest request = request(true, "IN_APP", "consent-scope-1");
        request.setMerchantOwnerMemberId(OWNER + 1);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.update(MEMBER, request));

        assertEquals(403, error.getCode());
        verify(mapper, never()).insertConsent(any());
    }

    private MerchantCrmMarketingConsentRequest request(boolean optedIn, String channel, String requestId) {
        MerchantCrmMarketingConsentRequest request = new MerchantCrmMarketingConsentRequest();
        request.setMerchantRowId(MERCHANT_ROW);
        request.setMerchantOwnerMemberId(OWNER);
        request.setChannel(channel);
        request.setOptedIn(optedIn);
        request.setRequestId(requestId);
        return request;
    }

    private MerchantCrmConsentMerchantVO merchant() {
        MerchantCrmConsentMerchantVO out = new MerchantCrmConsentMerchantVO();
        out.setMerchantRowId(77L);
        out.setMerchantOwnerMemberId(OWNER);
        out.setMerchantName("旧城咖啡");
        return out;
    }
}
