package com.chengyinhub.web.task;

import com.chengyinhub.business.service.RoamRecallService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

/** 完成漫游 24–72 小时召回；未配模板或开关关闭时安全空跑。 */
@Component("roamRecallTask")
public class RoamRecallTask {
    private static final Logger log = LoggerFactory.getLogger(RoamRecallTask.class);

    @Autowired private RoamRecallService roamRecallService;

    public void run() {
        int sent = roamRecallService.runDue(new Date());
        log.info("漫游召回任务结束,微信受理 {} 条(跳过/扫描详情见上述日志)", sent);
    }
}
