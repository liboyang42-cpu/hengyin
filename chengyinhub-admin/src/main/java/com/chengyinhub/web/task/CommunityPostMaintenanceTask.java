package com.chengyinhub.web.task;

import com.chengyinhub.business.service.ICommunityPostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 广场投影、治理时效与删除擦除的幂等维护任务。 */
@Component("communityPostMaintenanceTask")
public class CommunityPostMaintenanceTask {
    private static final Logger log = LoggerFactory.getLogger(CommunityPostMaintenanceTask.class);

    @Autowired
    private ICommunityPostService communityPostService;

    public void run() {
        Map<String, Integer> result = communityPostService.runMaintenance(50);
        log.info("广场维护完成 enforcements={} outbox={} erasures={}",
                result.get("enforcements"), result.get("outbox"), result.get("erasures"));
    }
}
