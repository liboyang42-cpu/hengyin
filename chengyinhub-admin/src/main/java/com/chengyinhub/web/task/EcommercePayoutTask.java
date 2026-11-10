package com.chengyinhub.web.task;

import com.chengyinhub.business.payout.EcommerceProfitShareService;
import com.chengyinhub.business.payout.EcommerceSubMerchantService;
import com.chengyinhub.business.payout.PayoutSwitches;
import com.chengyinhub.business.service.impl.AlarmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * l-payout2 · 电商收付通定时任务（迁移 migration_20261110_ecommerce_payout_wiring.sql 以暂停状态注册）。
 * <ul>
 *   <li>{@code ecommercePayoutTask.shareAndRetry()}：补齐已结算主题的分账计划 → 提交到期计划（与 T+14 同一时点）
 *       → 查询处理中的分账 → 查询处理中的分账回退。</li>
 *   <li>{@code ecommercePayoutTask.syncOnboarding()}：刷新审核中 / 待签约 / 待验证的二级商户进件状态。</li>
 * </ul>
 * 开关关闭时两者都直接返回，不读不写、不调微信。失败先告警再抛出，让 Quartz 记 FAIL。
 */
@Component("ecommercePayoutTask")
public class EcommercePayoutTask {
    private static final Logger logger = LoggerFactory.getLogger(EcommercePayoutTask.class);

    @Autowired private PayoutSwitches switches;
    @Autowired private EcommerceProfitShareService profitShareService;
    @Autowired private EcommerceSubMerchantService subMerchantService;
    @Autowired private AlarmService alarmService;

    public void shareAndRetry() {
        if (!switches.profitSharingEnabled()) return;
        try {
            int planned = profitShareService.planMissing(50);
            int submitted = profitShareService.submitPlanned(50);
            int synced = profitShareService.syncProcessing(100);
            int returns = profitShareService.syncReturns(100);
            logger.info("电商收付通分账：补计划 {}，提交 {}，查分账 {}，查回退 {}", planned, submitted, synced, returns);
        } catch (RuntimeException e) {
            alarm("电商收付通分账任务失败", e);
            throw e;
        }
    }

    public void syncOnboarding() {
        if (!switches.ecommerceEnabled()) return;
        try {
            logger.info("电商收付通进件状态同步：{} 条", subMerchantService.refreshInProgress(100));
        } catch (RuntimeException e) {
            alarm("电商收付通进件同步失败", e);
            throw e;
        }
    }

    private void alarm(String title, RuntimeException e) {
        try {
            alarmService.alarm(title, "ecommercePayoutTask 异常: " + e.getClass().getSimpleName());
        } catch (Exception ignore) {
        }
    }
}
