package com.chengyinhub.business.task;

import java.util.List;
import com.chengyinhub.business.mapper.ImNotificationIntentMapper;
import com.chengyinhub.business.service.IImService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 原站内消息投递：固定本轮最大ID、逐项事务，单条失败不饿死后页。 */
@Component("imNotificationDeliveryTask")
public class ImNotificationDeliveryTask {
    private static final Logger log = LoggerFactory.getLogger(ImNotificationDeliveryTask.class);
    @Autowired private ImNotificationIntentMapper mapper;
    @Autowired private IImService imService;

    public void deliverPending() {
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return;
        Long maxId = mapper.maxPendingId();
        if (maxId == null) return;
        long after = 0;
        while (after < maxId) {
            List<Long> ids = mapper.selectPendingPage(after, maxId);
            if (ids == null || ids.isEmpty()) return;
            long previous = after;
            for (Long id : ids) {
                if (id == null || id <= after || id > maxId) continue;
                after = id;
                try { imService.deliverSystemNotification(id); }
                catch (Exception e) { log.warn("站内消息原意图投递待重试 id={}", id); }
            }
            if (after <= previous) return;
        }
    }
}
