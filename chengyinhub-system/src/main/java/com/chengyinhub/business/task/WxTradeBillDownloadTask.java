package com.chengyinhub.business.task;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.chengyinhub.business.service.IWxTradeBillService;

/** 昨日交易账单及三个独立资金账户的下载任务，任一待核都不记为完整成功。 */
@Component("wxTradeBillDownloadTask")
public class WxTradeBillDownloadTask {
    private static final Logger log = LoggerFactory.getLogger(WxTradeBillDownloadTask.class);
    @Autowired private IWxTradeBillService wxTradeBillService;
    @Autowired private com.chengyinhub.business.mapper.WxBillImportMapper importMapper;
    @Autowired private com.chengyinhub.common.config.WxPayConfig wxPayConfig;
    @Autowired private com.chengyinhub.business.service.impl.AlarmService alarmService;
    @Autowired private com.chengyinhub.business.service.IFourLedgerReconcileService reconcileService;

    public void download() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Date yesterday = Date.from(LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant());
        int pending = 0;
        for (String type : new String[] {"TRADE", "BASIC", "OPERATION", "FEES"}) {
            try {
                int rows = "TRADE".equals(type) ? wxTradeBillService.downloadAndIngestBill(yesterday)
                        : wxTradeBillService.downloadAndIngestFundFlowBill(yesterday, type);
                com.chengyinhub.business.domain.WxBillImport batch = importMapper.selectImport(
                        wxPayConfig.getMerchantId(), type, yesterday);
                if (batch == null || !"SUCCESS".equals(batch.getStatus()) || batch.getRowCount() == null
                        || batch.getRowCount() < 0 || batch.getSourceHash() == null || batch.getSourceHash().isBlank()) {
                    throw new IllegalStateException("账单完成标记缺失");
                }
                if ("TRADE".equals(type)) {
                    java.util.List<?> differences = reconcileService.reconcileWechatBillAgainstRegistrations(yesterday);
                    if (differences == null) throw new IllegalStateException("微信交易对账结果未确认");
                    if (!differences.isEmpty()) {
                        pending++;
                        try {
                            alarmService.alarmThrottled("payment.bill.trade.difference", "微信交易账单与报名订单存在差异",
                                    "differenceCount=" + differences.size() + " 只报告差异，未自动调账");
                        } catch (RuntimeException alarmFailure) {
                            log.error("微信交易账单差异告警未确认 failureType={}", alarmFailure.getClass().getSimpleName());
                        }
                    }
                }
                if (!"TRADE".equals(type) && "GAP".equals(batch.getBalanceStatus())) {
                    pending++;
                    try {
                        alarmService.alarmThrottled("payment.bill.balance." + type.toLowerCase(java.util.Locale.ROOT),
                                "微信资金账单余额待核", "account=" + type + " 文件内资金余额不连续，未自动调账");
                    } catch (RuntimeException alarmFailure) {
                        log.error("微信资金账单余额告警未确认 type={} failureType={}", type, alarmFailure.getClass().getSimpleName());
                    }
                }
                log.info("微信账单导入完成 type={} newRows={} balanceStatus={}", type, rows, batch.getBalanceStatus());
            } catch (IWxTradeBillService.BillUnavailableException notReady) {
                pending++;
                log.warn("微信账单待核 type={}：账单不存在或仍在生成，后续重试，不视为零交易", type);
            } catch (RuntimeException failure) {
                pending++;
                log.error("微信账单下载或导入失败 type={} failureType={}", type, failure.getClass().getSimpleName());
                try {
                    alarmService.alarmThrottled("payment.bill.download." + type.toLowerCase(java.util.Locale.ROOT),
                            "微信账单下载导入失败", "account=" + type + " failureType=" + failure.getClass().getSimpleName());
                } catch (RuntimeException alarmFailure) {
                    log.error("微信账单失败告警未确认 type={} failureType={}", type, alarmFailure.getClass().getSimpleName());
                }
            }
        }
        if (pending > 0) throw new IllegalStateException("微信账单仍有 " + pending + " 类待核；未自动调账");
    }
}
