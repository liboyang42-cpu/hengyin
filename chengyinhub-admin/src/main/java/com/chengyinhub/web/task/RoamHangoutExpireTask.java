package com.chengyinhub.web.task;

import com.chengyinhub.business.service.IRoamHangoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 附近的局过期关闭:有时间的局 start_at+24h,常驻局 14 天无群消息。只关局 + 关群,零资金流。
 * 注册 SQL 在 migration_roam_hangout_20260905.sql(invoke_target = roamHangoutExpireTask.run());没注册 = 永不调度。
 */
@Component("roamHangoutExpireTask")
public class RoamHangoutExpireTask {
    private static final Logger log = LoggerFactory.getLogger(RoamHangoutExpireTask.class);

    @Autowired private IRoamHangoutService roamHangoutService;

    public void run() {
        int closed = roamHangoutService.expireDue(new Date());
        if (closed > 0) log.info("附近的局过期扫描完成,关闭 {} 个", closed);
    }
}
