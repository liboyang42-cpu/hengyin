package com.chengyinhub.web.job;

import com.chengyinhub.business.service.IAccountDeregistrationService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AccountDeregistrationJobTest {

    @Test
    void jobExecutesDueDeregistrations() {
        IAccountDeregistrationService service = mock(IAccountDeregistrationService.class);
        AccountDeregistrationJob job = new AccountDeregistrationJob(service);

        job.executeDueDeregistrations();

        verify(service).executeDue(any(java.util.Date.class));
    }
}
