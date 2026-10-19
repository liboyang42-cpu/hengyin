package com.chengyinhub.business.service;

import com.chengyinhub.business.task.VerificationQrcodeRepairTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * 核销码补偿任务单测:逐单补跑、单笔失败不影响其它、空列表不动。
 */
class VerificationQrcodeRepairTaskTest {

    private ICmsRegistrationService cmsRegistrationService;
    private VerificationQrcodeRepairTask task;

    @BeforeEach
    void setUp() {
        cmsRegistrationService = mock(ICmsRegistrationService.class);
        when(cmsRegistrationService.registrationScanUpperBound()).thenReturn(Long.MAX_VALUE);
        task = new VerificationQrcodeRepairTask();
        ReflectionTestUtils.setField(task, "cmsRegistrationService", cmsRegistrationService);
    }

    @Test
    void repairsEachMissingOne() {
<<<<<<< public
        when(cmsRegistrationService.getPaidMissingQrcodeScanUpperBound()).thenReturn(100L);
        when(cmsRegistrationService.listPaidMissingQrcodeIdsAfter(0L, 100L, 100)).thenReturn(Arrays.asList(1L, 2L));
=======
        when(cmsRegistrationService.listPaidMissingQrcodeIds(0L)).thenReturn(Arrays.asList(1L, 2L));
>>>>>>> private-codex/batch1

        task.repairMissing();

        verify(cmsRegistrationService).generateVerificationQrcode(1L);
        verify(cmsRegistrationService).generateVerificationQrcode(2L);
    }

    @Test
    void singleFailureDoesNotStopOthers() {
<<<<<<< public
        when(cmsRegistrationService.getPaidMissingQrcodeScanUpperBound()).thenReturn(100L);
        when(cmsRegistrationService.listPaidMissingQrcodeIdsAfter(0L, 100L, 100)).thenReturn(Arrays.asList(1L, 2L, 3L));
=======
        when(cmsRegistrationService.listPaidMissingQrcodeIds(0L)).thenReturn(Arrays.asList(1L, 2L, 3L));
>>>>>>> private-codex/batch1
        when(cmsRegistrationService.generateVerificationQrcode(2L)).thenThrow(new RuntimeException("boom"));

        task.repairMissing(); // 不应向外抛

        verify(cmsRegistrationService).generateVerificationQrcode(1L);
        verify(cmsRegistrationService).generateVerificationQrcode(3L); // 2 失败后 3 仍被补
    }

    @Test
    void failedPrefixDoesNotStarveLaterRepairAndNewArrivalsDoNotDelayRetryForever() {
        when(cmsRegistrationService.registrationScanUpperBound()).thenReturn(101L, 201L);
        when(cmsRegistrationService.listPaidMissingQrcodeIds(anyLong())).thenAnswer(call -> {
            long after = call.getArgument(0);
            return java.util.stream.LongStream.rangeClosed(after + 1, after + 100)
                    .boxed().collect(java.util.stream.Collectors.toList());
        });
        when(cmsRegistrationService.generateVerificationQrcode(anyLong())).thenThrow(new IllegalStateException("provider unavailable"));
        task.repairMissing();
        task.repairMissing();
        verify(cmsRegistrationService).generateVerificationQrcode(101L);
        verify(cmsRegistrationService, never()).generateVerificationQrcode(102L);
        task.repairMissing();
        verify(cmsRegistrationService, times(2)).generateVerificationQrcode(1L);
        verify(cmsRegistrationService, times(2)).registrationScanUpperBound();
    }

    @Test
    void emptyListNoop() {
<<<<<<< public
        when(cmsRegistrationService.getPaidMissingQrcodeScanUpperBound()).thenReturn(100L);
        when(cmsRegistrationService.listPaidMissingQrcodeIdsAfter(0L, 100L, 100)).thenReturn(Collections.emptyList());
=======
        when(cmsRegistrationService.listPaidMissingQrcodeIds(0L)).thenReturn(Collections.emptyList());
>>>>>>> private-codex/batch1

        task.repairMissing();

        verify(cmsRegistrationService, never()).generateVerificationQrcode(anyLong());
    }
    @Test
    void failuresAdvanceAcrossPageAndResumeNextRound() {
        when(cmsRegistrationService.getPaidMissingQrcodeScanUpperBound()).thenReturn(501L);
        when(cmsRegistrationService.listPaidMissingQrcodeIdsAfter(anyLong(), org.mockito.ArgumentMatchers.eq(501L),
                org.mockito.ArgumentMatchers.eq(100))).thenAnswer(invocation -> {
            long after = (Long) invocation.getArgument(0);
            java.util.List<Long> ids = new java.util.ArrayList<>();
            for (long id = after + 1; id <= Math.min(after + 100, 501); id++) ids.add(id);
            return ids;
        });
        when(cmsRegistrationService.generateVerificationQrcode(1L)).thenThrow(new IllegalStateException("permanent"));
        task.repairMissing();
        verify(cmsRegistrationService).generateVerificationQrcode(500L);
        verify(cmsRegistrationService, never()).generateVerificationQrcode(501L);
        task.repairMissing();
        verify(cmsRegistrationService).generateVerificationQrcode(501L);
        verify(cmsRegistrationService, org.mockito.Mockito.times(1)).generateVerificationQrcode(1L);
    }
}
