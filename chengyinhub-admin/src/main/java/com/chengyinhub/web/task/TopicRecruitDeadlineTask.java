package com.chengyinhub.web.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.service.ICmsTopicService;

/**
 * 双死线② · 招募到期定时任务(报告 B1-7)。
 *
 * 在后台「定时任务」中配置 invokeTarget 为 topicRecruitDeadlineTask.run(),cron 建议 0 0/10 * * * ?(每10分钟),
 * 即可自动扫描招募中(lifecycle=1)且招募截止已过的主题:
 *   0 接受  → 回草稿(lifecycle=0),发布者可一键延期×2
 *   ≥1 接受 → 招募锁定,进入定价(lifecycle=2)
 */
@Component("topicRecruitDeadlineTask")
public class TopicRecruitDeadlineTask {
    private static final Logger logger = LoggerFactory.getLogger(TopicRecruitDeadlineTask.class);

    @Autowired
    private ICmsTopicService cmsTopicService;

    public void run() {
        try {
            int handled = cmsTopicService.processRecruitDeadlines();
            int lifecycleAdvanced = cmsTopicService.advanceSaleLifecycle();
            logger.info("双死线-招募到期扫描完成,招募处理 {}, 时间轴推进 {}", handled, lifecycleAdvanced);
        } catch (Exception e) {
            logger.error("双死线-招募到期扫描执行失败", e);
        }
    }
}
