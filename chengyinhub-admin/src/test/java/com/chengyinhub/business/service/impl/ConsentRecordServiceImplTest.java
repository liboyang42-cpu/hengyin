package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsentRecordServiceImplTest {

    @Test
    void consentRecordServiceIsAvailableForConsentEvents() {
        assertDoesNotThrow(() -> Class.forName("com.chengyinhub.business.service.impl.ConsentRecordServiceImpl"));
    }

    @Test
    void consentRecordExposesDatabaseCreateTime() {
        assertDoesNotThrow(() -> ConsentRecord.class.getMethod("getCreateTime"));
    }

    @Test
    void recordPersistsCompleteAppendOnlyConsentEvent() {
        ConsentRecordMapper mapper = mock(ConsentRecordMapper.class);
        when(mapper.insertConsentRecord(any(ConsentRecord.class))).thenReturn(1);
        ConsentRecordServiceImpl service = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(service, "consentRecordMapper", mapper);

        int rows = service.record(1001L, "privacy_policy", "v1.1", "roam_location",
                "AGREE", "consent-request-001", "127.0.0.1");

        assertEquals(1, rows);
        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(mapper).insertConsentRecord(captor.capture());
        ConsentRecord recorded = captor.getValue();
        assertEquals(1001L, recorded.getMemberId());
        assertEquals("privacy_policy", recorded.getDocType());
        assertEquals("v1.1", recorded.getDocVersion());
        assertEquals("roam_location", recorded.getScene());
        assertEquals("AGREE", recorded.getEventType());
        assertEquals("consent-request-001", recorded.getRequestId());
        assertEquals("127.0.0.1", recorded.getIpAddress());
        assertNotNull(recorded.getOccurredAt());
        assertNotNull(recorded.getCreateTime());
    }

    @Test
    void recordTreatsDuplicateRequestIdAsIdempotent() {
        ConsentRecordMapper mapper = mock(ConsentRecordMapper.class);
        when(mapper.insertConsentRecord(any(ConsentRecord.class))).thenThrow(new DuplicateKeyException("duplicate"));
        ConsentRecordServiceImpl service = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(service, "consentRecordMapper", mapper);

        assertEquals(0, service.record(1001L, "privacy_policy", "v1.1", "roam_location",
                "REVOKE", "consent-request-002", "127.0.0.1"));
    }

    @Test
    void recordRejectsUnsupportedConsentEventType() {
        ConsentRecordServiceImpl service = new ConsentRecordServiceImpl();

        assertThrows(IllegalArgumentException.class, () -> service.record(1001L, "privacy_policy", "v1.1",
                "roam_location", "UNKNOWN", "consent-request-003", "127.0.0.1"));
    }

    @Test
    void merchantConsentPersistsExactlyOneMerchantScope() {
        ConsentRecordMapper mapper = mock(ConsentRecordMapper.class);
        when(mapper.insertConsentRecord(any(ConsentRecord.class))).thenReturn(1);
        ConsentRecordServiceImpl service = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(service, "consentRecordMapper", mapper);

        service.record(1001L, "merchant_onsite_data_sharing", "v1.0", "merchant_redeem",
                "MERCHANT", 2001L, "AGREE", "merchant-consent-001", "127.0.0.1");

        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(mapper).insertConsentRecord(captor.capture());
        assertEquals("MERCHANT", captor.getValue().getScopeType());
        assertEquals(2001L, captor.getValue().getScopeId());
    }

    @Test
    void merchantAgreementDoesNotAuthorizeAnotherMerchantAndRevokeWins() {
        ConsentRecordMapper mapper = mock(ConsentRecordMapper.class);
        ConsentRecord agreed = new ConsentRecord();
        agreed.setDocVersion("v1.0");
        agreed.setEventType("AGREE");
        agreed.setScopeType("MERCHANT");
        agreed.setScopeId(2001L);
        ConsentRecord revoked = new ConsentRecord();
        revoked.setDocVersion("v1.0");
        revoked.setEventType("REVOKE");
        revoked.setScopeType("MERCHANT");
        revoked.setScopeId(2001L);
        when(mapper.selectLatestScoped(1001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2001L)).thenReturn(agreed, revoked);
        when(mapper.selectLatestScoped(1001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2002L)).thenReturn(null);
        ConsentRecordServiceImpl service = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(service, "consentRecordMapper", mapper);

        assertEquals(true, service.hasCurrentAgreement(1001L, "merchant_onsite_data_sharing",
                "merchant_redeem", "MERCHANT", 2001L, "v1.0"));
        assertEquals(false, service.hasCurrentAgreement(1001L, "merchant_onsite_data_sharing",
                "merchant_redeem", "MERCHANT", 2002L, "v1.0"));
        assertEquals(false, service.hasCurrentAgreement(1001L, "merchant_onsite_data_sharing",
                "merchant_redeem", "MERCHANT", 2001L, "v1.0"));
    }
}
