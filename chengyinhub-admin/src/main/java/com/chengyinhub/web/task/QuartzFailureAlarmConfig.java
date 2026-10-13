package com.chengyinhub.web.task;

import com.chengyinhub.business.service.impl.AlarmService;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.listeners.JobListenerSupport;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzFailureAlarmConfig {

    @Bean
    public SchedulerFactoryBeanCustomizer quartzFailureAlarmCustomizer(AlarmService alarmService) {
        return factory -> factory.setGlobalJobListeners(failureListener(alarmService));
    }

    static JobListenerSupport failureListener(AlarmService alarmService) {
        return new JobListenerSupport() {
            @Override
            public String getName() {
                return "chengyinTaskFailureAlarm";
            }

            @Override
            public void jobWasExecuted(JobExecutionContext context, JobExecutionException failure) {
                if (failure != null) {
                    // 任务参数和异常消息可能包含个人数据，仅投递任务标识和异常类型。
                    try {
                        alarmService.alarm("定时任务失败", "job=" + context.getJobDetail().getKey()
                                + ", errorType=" + failure.getClass().getSimpleName());
                    } catch (RuntimeException alarmFailure) {
                        getLog().error("任务失败告警投递异常 job={} errorType={}", context.getJobDetail().getKey(),
                                alarmFailure.getClass().getSimpleName());
                    }
                }
            }
        };
    }
}
