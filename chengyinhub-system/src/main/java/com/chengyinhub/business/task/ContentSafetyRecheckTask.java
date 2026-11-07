package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IContentSafetyRecheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Round5C:机审未完成内容的自动复检(通过自动发布、违规自动下架、耗尽自动驳回并通知作者)。 */
@Component("contentSafetyRecheckTask")
public class ContentSafetyRecheckTask {
    private static final Logger logger = LoggerFactory.getLogger(ContentSafetyRecheckTask.class);
    private final IContentSafetyRecheckService recheckService;

    public ContentSafetyRecheckTask(IContentSafetyRecheckService recheckService) {
        this.recheckService = recheckService;
    }

    public void run() {
        try {
            recheckService.runDue();
        } catch (Exception batchFailure) {
            logger.error("内容安全自动复检批次失败，等待下一轮", batchFailure);
        }
    }
}
