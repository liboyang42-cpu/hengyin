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
                    // 按任务限频：每分钟一跑的任务持续失败时，10 分钟内同一任务只尝试投递一次，
                    // 避免把告警通道刷成噪音；不同任务互不抑制。
                    try {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        alarmService.alarmThrottled(eventKey(context.getJobDetail().getKey()), "定时任务失败",
                                "job=" + context.getJobDetail().getKey()
                                + ", errorType=" + cause.getClass().getSimpleName());
                    } catch (RuntimeException alarmFailure) {
                        getLog().error("任务失败告警投递异常 job={} errorType={}", context.getJobDetail().getKey(),
                                alarmFailure.getClass().getSimpleName());
                    }
                }
            }
        };
    }

    /** AlarmService 的事件键只接受 [a-z0-9_.-]，任务键（含大写/中文分组）按此归一，超长截断。 */
    static String eventKey(org.quartz.JobKey key) {
        String raw = key == null ? "unknown" : (key.getGroup() + "." + key.getName());
        String normalized = raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        String event = "job.failure." + normalized;
        return event.length() > 80 ? event.substring(0, 80) : event;
    }
}
