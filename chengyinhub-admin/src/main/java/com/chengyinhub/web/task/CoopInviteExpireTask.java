package com.chengyinhub.web.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.service.ICoopInviteService;

/**
 * 双死线① · 邀约过期定时任务(报告 B1-6)。
 *
 * 在后台「定时任务」中配置 invokeTarget 为 coopInviteExpireTask.run(),cron 建议 0 0/10 * * * ?(每10分钟),
 * 即可自动扫描:
 * 挂起邀约(status=0)超过 expire_time 未处理 → 5已过期
 * 已接受(status=1)邀约不再按 config_deadline 过期(阻断#8 / 台账 1-11):主题级没有配置步骤,
 * 过期它只会让邀约与仍有效的 coop_order 分叉。
 */
@Component("coopInviteExpireTask")
public class CoopInviteExpireTask {
    private static final Logger logger = LoggerFactory.getLogger(CoopInviteExpireTask.class);

    @Autowired
    private ICoopInviteService coopInviteService;

    public void run() {
        try {
            int expired = coopInviteService.expireStaleInvites();
            logger.info("双死线-邀约过期扫描完成,共过期 {} 条邀约", expired);
        } catch (Exception e) {
            logger.error("双死线-邀约过期扫描执行失败", e);
        }
    }
}
