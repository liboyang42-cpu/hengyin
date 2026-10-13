package com.chengyinhub.business.task;

import com.chengyinhub.business.service.IMerchantAftercareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 售后商家超时巡检(拍板 2026-09-16 #12):玩家发起售后后商家 24 小时未回应,
 * 自动转平台客服处理并通知玩家。已售订单/已批准退款不受影响,本任务只写一个只增不减的
 * {@code refund_application.platform_takeover_at} 标记 + 一条站内信,不动审核与打款状态。
 *
 * <p>在 RuoYi「定时任务管理」配一条:invokeTarget = merchantAftercareTimeoutTask.run(),
 * cron 建议 0 0/10 * * * ?(每 10 分钟,与双死线任务同频)。幂等:标平台转出用条件更新
 * (platform_takeover_at is null),已转过的不重复转、不重复通知。
 */
@Component("merchantAftercareTimeoutTask")
public class MerchantAftercareTimeoutTask {

    private static final Logger log = LoggerFactory.getLogger(MerchantAftercareTimeoutTask.class);

    @Autowired
    private IMerchantAftercareService merchantAftercareService;

    public void run() {
        try {
            int escalated = merchantAftercareService.escalateStaleMerchantResponses();
            if (escalated > 0) {
                log.info("售后超时转平台扫描完成,本轮转出 {} 单", escalated);
            }
        } catch (Exception e) {
            // 扫描失败必须留痕:静默的定时任务 = 玩家永远等不到平台介入且零告警。
            log.error("售后超时转平台扫描执行失败", e);
        }
    }
}
