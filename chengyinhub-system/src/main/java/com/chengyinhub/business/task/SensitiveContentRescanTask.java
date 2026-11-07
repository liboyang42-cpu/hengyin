package com.chengyinhub.business.task;

import com.chengyinhub.business.service.ISensitiveContentRescanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Round5D:词库变化后复查公开文本(默认只报告,开关打开才自动下架)。 */
@Component("sensitiveContentRescanTask")
public class SensitiveContentRescanTask {
    private static final Logger logger = LoggerFactory.getLogger(SensitiveContentRescanTask.class);
    private final ISensitiveContentRescanService rescanService;

    public SensitiveContentRescanTask(ISensitiveContentRescanService rescanService) {
        this.rescanService = rescanService;
    }

    public void run() {
        try {
            rescanService.runOnce();
        } catch (Exception batchFailure) {
            logger.error("敏感词存量复查批次失败，等待下一轮", batchFailure);
        }
    }
}
