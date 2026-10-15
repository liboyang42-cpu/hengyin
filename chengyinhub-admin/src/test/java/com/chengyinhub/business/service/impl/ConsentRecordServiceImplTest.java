package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
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

        ConsentRecord original = originalEvent();
        original.setEventType("REVOKE");
        original.setRequestId("consent-request-002");
        when(mapper.selectByRequestIdForShare("consent-request-002")).thenReturn(original);
        assertEquals(0, service.record(1001L, "privacy_policy", "v1.1", "roam_location",
                "REVOKE", "consent-request-002", "127.0.0.2"));
        verify(mapper).selectByRequestIdForShare("consent-request-002");
    }

    private static ConsentRecord originalEvent() {
        ConsentRecord original = new ConsentRecord();
        original.setMemberId(1001L);
        original.setDocType("privacy_policy");
        original.setDocVersion("v1.1");
        original.setScene("roam_location");
        original.setEventType("AGREE");
        original.setRequestId("same-key");
        return original;
    }

    @Test
    void duplicateRequestRejectsAnyDifferentSemanticFieldWithoutExposingOriginal() {
        List<Consumer<ConsentRecord>> changes = Arrays.asList(
                r -> r.setMemberId(2002L), r -> r.setDocType("other_doc"),
                r -> r.setDocVersion("other_version"), r -> r.setScene("other_scene"),
                r -> r.setScopeType("MERCHANT"), r -> r.setScopeId(3003L),
                r -> r.setEventType("REVOKE"));
        for (Consumer<ConsentRecord> change : changes) {
            ConsentRecord original = originalEvent();
            change.accept(original);
            ConsentRecordMapper mapper = mock(ConsentRecordMapper.class);
            when(mapper.insertConsentRecord(any(ConsentRecord.class))).thenThrow(new DuplicateKeyException("private constraint details"));
            when(mapper.selectByRequestIdForShare("same-key")).thenReturn(original);
            ConsentRecordServiceImpl service = new ConsentRecordServiceImpl();
            ReflectionTestUtils.setField(service, "consentRecordMapper", mapper);
            ServiceException conflict = assertThrows(ServiceException.class, () -> service.record(
                    1001L, "privacy_policy", "v1.1", "roam_location", "AGREE", "same-key", "127.0.0.1"));
            assertEquals(Integer.valueOf(HttpStatus.CONFLICT), conflict.getCode());
            assertEquals("请求标识已用于其他同意事件", conflict.getMessage());
        }
    }

    @Test
    void duplicateWithoutOriginalFailsClosed() {
        ConsentRecordMapper mapper = mock(ConsentRecordMapper.class);
        when(mapper.insertConsentRecord(any(ConsentRecord.class))).thenThrow(new DuplicateKeyException("duplicate"));
        ConsentRecordServiceImpl service = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(service, "consentRecordMapper", mapper);
        ServiceException conflict = assertThrows(ServiceException.class, () -> service.record(
                1001L, "privacy_policy", "v1.1", "roam_location", "AGREE", "same-key", "127.0.0.1"));
        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), conflict.getCode());
    }

    @Test
    void scopedDuplicateComparesOriginalMerchantAndPreservesIdempotentResult() {
        ConsentRecord original = originalEvent();
        original.setScopeType("MERCHANT");
        original.setScopeId(3003L);
        ConsentRecordMapper mapper = mock(ConsentRecordMapper.class);
        when(mapper.insertConsentRecord(any(ConsentRecord.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectByRequestIdForShare("same-key")).thenReturn(original);
        ConsentRecordServiceImpl service = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(service, "consentRecordMapper", mapper);
        assertEquals(0, service.record(1001L, "privacy_policy", "v1.1", "roam_location",
                "MERCHANT", 3003L, "AGREE", "same-key", "127.0.0.1"));
        assertThrows(ServiceException.class, () -> service.record(1001L, "privacy_policy", "v1.1", "roam_location",
                "MERCHANT", 3004L, "AGREE", "same-key", "127.0.0.1"));
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
