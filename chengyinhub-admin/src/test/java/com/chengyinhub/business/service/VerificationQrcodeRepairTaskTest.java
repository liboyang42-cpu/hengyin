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
        task = new VerificationQrcodeRepairTask();
        ReflectionTestUtils.setField(task, "cmsRegistrationService", cmsRegistrationService);
    }

    @Test
    void repairsEachMissingOne() {
        when(cmsRegistrationService.listPaidMissingQrcodeIds()).thenReturn(Arrays.asList(1L, 2L));

        task.repairMissing();

        verify(cmsRegistrationService).generateVerificationQrcode(1L);
        verify(cmsRegistrationService).generateVerificationQrcode(2L);
    }

    @Test
    void singleFailureDoesNotStopOthers() {
        when(cmsRegistrationService.listPaidMissingQrcodeIds()).thenReturn(Arrays.asList(1L, 2L, 3L));
        when(cmsRegistrationService.generateVerificationQrcode(2L)).thenThrow(new RuntimeException("boom"));

        task.repairMissing(); // 不应向外抛

        verify(cmsRegistrationService).generateVerificationQrcode(1L);
        verify(cmsRegistrationService).generateVerificationQrcode(3L); // 2 失败后 3 仍被补
    }

    @Test
    void emptyListNoop() {
        when(cmsRegistrationService.listPaidMissingQrcodeIds()).thenReturn(Collections.emptyList());

        task.repairMissing();

        verify(cmsRegistrationService, never()).generateVerificationQrcode(anyLong());
    }
}
