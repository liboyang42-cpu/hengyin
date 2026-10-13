package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsentGuardTest {
    @Test void actualConsentServiceRequiresExactCurrentOwnerDocumentSceneAndLatestAgree() {
        ConsentRecordMapper mapper = mock(ConsentRecordMapper.class);
        ConsentRecordServiceImpl records = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(records, "consentRecordMapper", mapper);
        ComplianceProperties versions = new ComplianceProperties();
        versions.setDocVersions(Map.of("photo_model_processing", "v1.0", "voice_clone_processing", "v1.0"));
        ConsentGuard guard = new ConsentGuard(records, versions);
        assertThrows(ServiceException.class, () -> guard.requireCurrentAgreement(7L, "photo_model_processing", "object_photo"));
        ConsentRecord record = new ConsentRecord(); record.setDocVersion("v1.0"); record.setEventType("AGREE");
        when(mapper.selectLatest(7L,"photo_model_processing","object_photo")).thenReturn(record);
        assertDoesNotThrow(() -> guard.requireCurrentAgreement(7L,"photo_model_processing","object_photo"));
        assertThrows(ServiceException.class, () -> guard.requireCurrentAgreement(8L,"photo_model_processing","object_photo"));
        assertThrows(ServiceException.class, () -> guard.requireCurrentAgreement(7L,"voice_clone_processing","merchant_voice"));
        record.setEventType("REVOKE");
        assertThrows(ServiceException.class, () -> guard.requireCurrentAgreement(7L,"photo_model_processing","object_photo"));
        record.setEventType("AGREE"); record.setDocVersion("old");
        assertThrows(ServiceException.class, () -> guard.requireCurrentAgreement(7L,"photo_model_processing","object_photo"));
        when(mapper.selectLatest(7L,"photo_model_processing","object_photo")).thenThrow(new IllegalStateException("fixture database unavailable"));
        assertThrows(IllegalStateException.class, () -> guard.requireCurrentAgreement(7L,"photo_model_processing","object_photo"));
    }
}
