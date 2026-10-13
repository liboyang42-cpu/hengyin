package com.chengyinhub.web.job;

import com.chengyinhub.business.service.IAccountDeregistrationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class AccountDeregistrationJob {
    private final IAccountDeregistrationService deregistrationService;

    public AccountDeregistrationJob(IAccountDeregistrationService deregistrationService) {
        this.deregistrationService = deregistrationService;
    }

    @Scheduled(cron = "0 15 3 * * ?")
    public void executeDueDeregistrations() {
        deregistrationService.executeDue(new Date());
    }
}
