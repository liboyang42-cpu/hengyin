package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IClubPostMediaReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 独立恢复俱乐部动态图片送审，避免外部 HTTP 超时阻塞候补与通知维护。 */
@Component("clubPostMediaReviewTask")
public class ClubPostMediaReviewTask {
    private static final Logger logger = LoggerFactory.getLogger(ClubPostMediaReviewTask.class);
    private final IClubPostMediaReviewService mediaReviewService;

    public ClubPostMediaReviewTask(IClubPostMediaReviewService mediaReviewService) {
        this.mediaReviewService = mediaReviewService;
    }

    public void run() {
        try {
            mediaReviewService.retryDue();
        } catch (Exception batchFailure) {
            logger.error("俱乐部动态图片送审恢复批次失败，等待下一轮重试", batchFailure);
        }
    }
}
