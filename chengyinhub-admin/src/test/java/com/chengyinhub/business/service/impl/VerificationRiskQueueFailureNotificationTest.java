package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsRegistrationScanMapper;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationRiskQueueFailureNotificationTest {
    @Test
    void failedRiskQueueReportsAfterCommitWithoutReplacingVerifiedBusinessResult() {
        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        CmsRegistrationScanMapper scans = mock(CmsRegistrationScanMapper.class);
        when(scans.selectCmsRegistrationScanList(any())).thenReturn(Collections.emptyList());
        VerificationRiskService risk = mock(VerificationRiskService.class);
        when(risk.inspectAndQueue(7L, 31L)).thenThrow(new IllegalStateException("private-db-detail"));
        CommittedBusinessNotice notices = mock(CommittedBusinessNotice.class);
        AlarmService alarms = mock(AlarmService.class);
        when(alarms.deliver(anyString(), anyString())).thenReturn(AlarmService.AlarmDelivery.SUCCESS);
        ReflectionTestUtils.setField(service, "cmsRegistrationScanMapper", scans);
        ReflectionTestUtils.setField(service, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
        ReflectionTestUtils.setField(service, "verificationRiskService", risk);
        ReflectionTestUtils.setField(service, "committedBusinessNotice", notices); ReflectionTestUtils.setField(service, "alarmService", alarms);
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "writeScanLedger", 31L, 7L, Integer.valueOf(1)));
        ArgumentCaptor<Supplier<Boolean>> dispatch = ArgumentCaptor.forClass(Supplier.class);
        verify(notices).executeAfterCommit(eq("verification.risk.queue-failed"), eq(31L), dispatch.capture());
        verifyNoInteractions(alarms);
        assertTrue(dispatch.getValue().get());
        verify(alarms).deliver("核销异常抽检入队失败", "registrationId=31 type=IllegalStateException");
        doThrow(new IllegalStateException("scheduler unavailable")).when(notices).executeAfterCommit(anyString(), anyLong(), any());
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "writeScanLedger", 31L, 7L, Integer.valueOf(1)));
    }
}
