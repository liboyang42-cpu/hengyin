package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/** 完成漫游 24–72 小时后的订阅消息召回；开关默认关闭。 */
@Service
public class RoamRecallService {
    private static final Logger log = LoggerFactory.getLogger(RoamRecallService.class);
    private static final long HOUR_MS = 60L * 60L * 1000L;
    private static final int BATCH_SIZE = 50;

    @Value("${feature.roamRecall.enabled:false}")
    private boolean enabled;

    @Autowired private RoamMapper roamMapper;
    @Autowired private INotifyService notifyService;

    /**
     * @return 本轮被微信明确受理的消息数。
     *
     * <p>开关关闭 / 模板未配置时不扫描、不发送。这两条早返回原来静默返回 0,
     * 上层任务再记一行「扫描完成,受理 0 条」——看着像跑过、其实是空跑,
     * 依赖缺失(模板没配)最多只在 JVM 启动后警一次。故这里把「跳过原因 +
     * 未扫描未发送」显式落日志:sys_job_log 的 SUCCESS 不能替空跑背书。
     */
    public int runDue(Date now) {
        if (com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode()) return 0;
        if (!enabled) {
            log.info("漫游召回未开启(feature.roamRecall.enabled=false),本轮跳过:未扫描、未发送");
            return 0;
        }
        if (!notifyService.isRoamRecallReady()) {
            log.warn("漫游召回模板(wx.subscribe.tplRoamRecall)未配置,本轮跳过:未扫描、未发送;"
                    + "配齐模板与小程序授权前不得启用本任务");
            return 0;
        }
        Date clock = now == null ? new Date() : now;
        Date from = new Date(clock.getTime() - 72L * HOUR_MS);
        Date to = new Date(clock.getTime() - 24L * HOUR_MS);
        Date retryBefore = new Date(clock.getTime() - 6L * HOUR_MS);
        List<RoamSession> candidates = roamMapper.selectRecallCandidates(from, to, retryBefore, BATCH_SIZE);
        int sent = 0;
        if (candidates == null) return 0;
        for (RoamSession session : candidates) {
            if (session == null || session.getId() == null || session.getMemberId() == null) continue;
            if (roamMapper.claimRecallAttempt(session.getId(), clock) != 1) continue;
            boolean accepted = notifyService.notifyRoamRecall(session.getMemberId(),
                    session.getDistanceM() == null ? 0 : session.getDistanceM(),
                    session.getPoisFound() == null ? 0 : session.getPoisFound());
            if (accepted) {
                roamMapper.markRecallSent(session.getId(), clock);
                sent++;
            } else {
                log.warn("漫游召回未送达 sessionId={},将在窗口内按退避重试", session.getId());
            }
        }
        return sent;
    }
}
