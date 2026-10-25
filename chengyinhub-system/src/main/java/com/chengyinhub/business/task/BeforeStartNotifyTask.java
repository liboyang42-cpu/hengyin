package com.chengyinhub.business.task;

import com.chengyinhub.business.domain.dto.BeforeStartNotifyResult;
import com.chengyinhub.business.service.ITeamGroupService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 出发前提醒定时任务(I2)。
 *
 * 扫 24h 内出发的经典定向(mode=1)票种,给已支付成员发出发前订阅提醒
 * (原票次 × 收件人持久身份；提交领取后 UNKNOWN 只能读取原结果，绝不由 cron 自动重发)。
 * 在 RuoYi「定时任务」注册:invokeTarget = beforeStartNotifyTask.run(),cron 建议每小时一次。
 *
 * ⚠️ 现状(2026-09-15):sys_job id=102 仍为【暂停】,启用迁移
 * chengyinhub-admin/sql/migration_sys_job_enable_paused_20260915.sql 已备好(用户裁决 102 要打开),
 * 落库后还需在后台对该 job 保存一次或受控重启,当前 JVM 的 Quartz 才会加载。
 * 此前更有第二重死锁——扫描条件曾要求 team_status=1(已成团),而成团引擎从未生效、全库
 * team_status 恒为 0 ⇒ 查询恒空。成团下线时已摘除该条件,本任务逻辑上已可用。
 *
 * 失败必须出声:每轮结束都留汇总 INFO(0 条也不例外);有失败打 ERROR 并推 alarmService,
 * 任务异常同样告警 —— 原实现只在送达数&gt;0 时留一行 info,全量被微信拒时日志空白、
 * sys_job_log 照样记成功,「date5 格式错误」这类全量失败因此零信号(审查D §8 P1-1)。
 */
@Component("beforeStartNotifyTask")
public class BeforeStartNotifyTask {

    private static final Logger log = LoggerFactory.getLogger(BeforeStartNotifyTask.class);

    @Autowired
    private ITeamGroupService teamGroupService;

    @Autowired
    private AlarmService alarmService;

    public void run() {
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return;
        try {
            BeforeStartNotifyResult r = teamGroupService.notifyBeforeStartBatch();
            String summary = "scanned=" + r.getScanned()
                    + " skipped=" + r.getSkipped()
                    + " delivered=" + r.getDelivered()
                    + " failed=" + r.getFailed();
            if (r.getFailed() > 0) {
                log.error("出发前提醒存在发送失败 {}", summary);
                alarmService.alarm("出发前提醒发送失败",
                        summary + ",查持久原意图及 [订阅消息] 日志；UNKNOWN 须查证，勿另号重发");
            } else {
                // 0 条也要留痕:没有这行时「没人需要发」和「整批静默失败」在日志上长得一样
                log.info("出发前提醒任务完成 {}", summary);
            }
        } catch (Exception e) {
            log.error("出发前提醒任务异常", e);
            alarmService.alarm("出发前提醒任务异常", e.getClass().getSimpleName());
        }
    }
}
