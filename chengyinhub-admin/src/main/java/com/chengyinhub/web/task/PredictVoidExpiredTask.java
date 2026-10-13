package com.chengyinhub.web.task;

import com.chengyinhub.business.service.IPlayPredictService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 竞猜轮次超时兜底:商家过了截止仍不给答案的,把那一轮作废。
 *
 * <p>作废**不发奖** —— 奖是商家配的券,平台不能替商家发。玩家的核心收益
 * (节点通关、探索值)在下注那一刻就已经拿到,竞猜的奖是额外的。
 *
 * <p>不做这个任务的后果:玩家押完就永远悬着,商家侧待办无限堆积,
 * 而且没有任何信号 —— 正是那种「不炸所以发现不了」的漏法。
 */
@Component("predictVoidExpiredTask")
public class PredictVoidExpiredTask {

    private static final Logger log = LoggerFactory.getLogger(PredictVoidExpiredTask.class);

    /** 过了截止 48 小时还没结算就作废。给商家留出一个完整工作日 */
    private static final int GRACE_HOURS = IPlayPredictService.ANSWER_GRACE_HOURS;

    @Autowired
    private IPlayPredictService playPredictService;

    public void run() {
        try {
            int voided = playPredictService.voidExpiredRounds(GRACE_HOURS);
            if (voided > 0) {
                log.warn("竞猜超时作废 {} 轮:这些商家超过 {} 小时没给答案", voided, GRACE_HOURS);
            }
        } catch (Exception e) {
            // 整批失败不该让调度器把任务标记成永久失败,下一轮还要再试
            log.error("竞猜超时作废任务失败", e);
        }
    }
}
