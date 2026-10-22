package com.chengyinhub.web.task;

import com.chengyinhub.business.service.ContentSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/** O-14 统一搜索投影全量对齐(sys_job 默认暂停)。幂等，可随时重跑。 */
@Component("contentSearchProjectionTask")
public class ContentSearchProjectionTask {
    private static final Logger log = LoggerFactory.getLogger(ContentSearchProjectionTask.class);

    @Autowired
    private ContentSearchService contentSearchService;

    public void refresh() {
        Map<String, Integer> result = contentSearchService.refreshProjection();
        log.info("统一搜索投影对齐完成 {}", result);
    }
}
