package com.chengyinhub.quartz.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.service.ISysJobService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SysJobControllerTest
{
    private ISysJobService jobService;
    private SysJobController controller;

    @BeforeEach
    void setUp()
    {
        jobService = mock(ISysJobService.class);
        controller = new SysJobController();
        ReflectionTestUtils.setField(controller, "jobService", jobService);
    }

    @Test
    void runUsesDatabaseJobInsteadOfClientSuppliedGroup() throws Exception
    {
        SysJob request = request(99L, "FORGED");
        SysJob stored = request(99L, "DEFAULT");
        when(jobService.selectJobById(99L)).thenReturn(stored);
        when(jobService.run(stored)).thenReturn(true);

        AjaxResult result = controller.run(request);

        assertTrue(result.isSuccess());
        verify(jobService).run(same(stored));
    }

    @Test
    void changeStatusRejectsMissingUnknownAndIllegalInput() throws Exception
    {
        SysJob missingId = request(null, "FORGED");
        SysJob unknown = request(99L, "FORGED");
        SysJob illegalStatus = request(100L, "FORGED");
        illegalStatus.setStatus("unexpected");
        when(jobService.selectJobById(99L)).thenReturn(null);
        when(jobService.selectJobById(100L)).thenReturn(request(100L, "DEFAULT"));

        assertFalse(success(controller.changeStatus(missingId)));
        assertFalse(success(controller.changeStatus(unknown)));
        assertFalse(success(controller.changeStatus(illegalStatus)));

        verify(jobService, never()).changeStatus(org.mockito.ArgumentMatchers.any(SysJob.class));
        verify(jobService, never()).selectJobById(100L);
    }

    @Test
    void runRejectsMissingAndUnknownJobId() throws Exception
    {
        SysJob missingId = request(null, "FORGED");
        SysJob unknown = request(99L, "FORGED");
        when(jobService.selectJobById(99L)).thenReturn(null);

        assertFalse(success(controller.run(missingId)));
        assertFalse(success(controller.run(unknown)));

        verify(jobService, never()).run(org.mockito.ArgumentMatchers.any(SysJob.class));
    }

    @Test
    void changeStatusUsesStoredJobAndOnlyAcceptsNormalOrPause() throws Exception
    {
        SysJob request = request(99L, "FORGED");
        SysJob stored = request(99L, "DEFAULT");
        request.setStatus(ScheduleConstants.Status.PAUSE.getValue());
        when(jobService.selectJobById(99L)).thenReturn(stored);
        when(jobService.changeStatus(stored)).thenReturn(1);

        AjaxResult result = controller.changeStatus(request);

        assertTrue(success(result));
        assertEquals(ScheduleConstants.Status.PAUSE.getValue(), stored.getStatus());
        verify(jobService).changeStatus(same(stored));
    }

    @Test
    void stateChangingEntrypointsRequireDedicatedPermission() throws Exception
    {
        assertEquals("@ss.hasPermi('monitor:job:changeStatus')", SysJobController.class
                .getDeclaredMethod("changeStatus", SysJob.class).getAnnotation(PreAuthorize.class).value());
        assertEquals("@ss.hasPermi('monitor:job:changeStatus')", SysJobController.class
                .getDeclaredMethod("run", SysJob.class).getAnnotation(PreAuthorize.class).value());
    }

    private SysJob request(Long jobId, String jobGroup)
    {
        SysJob job = new SysJob();
        job.setJobId(jobId);
        job.setJobGroup(jobGroup);
        job.setStatus(ScheduleConstants.Status.NORMAL.getValue());
        return job;
    }

    private boolean success(AjaxResult result)
    {
        return result.isSuccess();
    }
}
