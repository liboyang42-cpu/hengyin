package com.chengyinhub.business.task;

import java.util.Calendar;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.chengyinhub.business.service.IWxTradeBillService;

/**
 * 微信交易账单 T+1 下载任务骨架(P1-6 N块)。
 *
 * <p>★复用 O 块(资金 Quartz)已验证的基础设施范式(参照 {@link RefundReconcileTask}/
 * {@link SettlementReconciliationTask}:{@code @Component} bean + 无参 public 方法,
 * 由 RuoYi「定时任务管理」(sys_job)配 {@code invokeTarget}),但本轮<b>刻意不写注册 seed SQL</b>
 * ——{@link #download()} 方法体当前只是 TODO 骨架(见下方注释),真调用会直接抛
 * {@link UnsupportedOperationException}。若现在就注册进 sys_job,定时任务会按 cron 反复触发、
 * 反复抛异常、反复写 error 日志,除了噪音没有任何价值,且容易被误当成"账单下载已经在跑"——
 * 那是比"任务未注册"更隐蔽的坑(表面看起来在工作,实际上从未真正下载过一天账单)。
 *
 * <p>P1-7 真正实现 {@link IWxTradeBillService#downloadAndIngestBill} 后,再补一条
 * {@code migration_wx_trade_bill_download_job_*.sql} 注册 sys_job,cron 建议每天 10:30(
 * 微信账单 T+1 早10点后可下载,留半小时余量避免"资源尚未生成")。
 */
@Component("wxTradeBillDownloadTask")
public class WxTradeBillDownloadTask {

    private static final Logger log = LoggerFactory.getLogger(WxTradeBillDownloadTask.class);

    @Autowired
    private IWxTradeBillService wxTradeBillService;

    /**
     * 下载昨天(T日)的微信交易账单。
     *
     * <p>TODO(P1-7):
     * <ol>
     *   <li>调用 {@link IWxTradeBillService#downloadAndIngestBill} 下载 T=昨天 的账单;</li>
     *   <li>若返回"资源尚未生成"(账单未到10点生成窗口),记录待重试,不当异常告警——这是正常态;</li>
     *   <li>下载成功后可选择性触发一次 N 块的对账 Service(手动或另开一个 cron),
     *       本任务本身只负责"把微信官方数据搬到本地",不做对账判断;</li>
     *   <li>失败(网络/签名/解析异常)按 O 块规范接 {@code AlarmService},不能只落 log.error
     *       ——账单没下载成功等于当天四账对账里的"微信交易"这一账缺失,且现状零告警。</li>
     * </ol>
     */
    public void download() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -1);
        Date yesterday = cal.getTime();
        log.warn("微信交易账单T+1下载任务被调用,但本轮(P1-6)只交付骨架,未实现真实下载逻辑。"
                + "billDate={}", yesterday);
        // TODO(P1-7): 取消下一行注释,接入真实下载逻辑后删除本方法体里的 throw。
        // wxTradeBillService.downloadAndIngestBill(yesterday);
        throw new UnsupportedOperationException(
                "微信交易账单T+1下载未实现(P1-7范围)。本任务未注册进 sys_job,不应被生产调度到。");
    }
}
