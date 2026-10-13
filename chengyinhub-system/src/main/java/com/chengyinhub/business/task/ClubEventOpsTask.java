package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IClubEventNotificationService;
import com.chengyinhub.business.service.IClubWaitlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;

/** 候补终态与通知失败项的有界维护任务；服务层 CAS 保证可安全重入。 */
@Component("clubEventOpsTask")
public class ClubEventOpsTask {
    private static final Logger logger = LoggerFactory.getLogger(ClubEventOpsTask.class);
    private final IClubWaitlistService waitlistService;
    private final IClubEventNotificationService notificationService;

    public ClubEventOpsTask(IClubWaitlistService waitlistService,
                            IClubEventNotificationService notificationService) {
        this.waitlistService = waitlistService;
        this.notificationService = notificationService;
    }

    public void run() {
        try {
            waitlistService.maintain(new Date());
        } catch (Exception waitlistFailure) {
            logger.error("候补维护批次失败，继续执行俱乐部通知重试", waitlistFailure);
        }
        try {
            notificationService.retryDue();
        } catch (Exception notificationFailure) {
            logger.error("俱乐部通知重试批次失败，已隔离本轮任务", notificationFailure);
        }
    }
}
