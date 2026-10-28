package com.chengyinhub.web.task;

import com.chengyinhub.business.service.impl.AlarmService;
import com.chengyinhub.common.utils.LogCorrelation;
import com.chengyinhub.quartz.util.QuartzJobCorrelation;
import java.util.Map;
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
                    String runId = QuartzJobCorrelation.runId(context);
                    try (LogCorrelation.Scope ignored = LogCorrelation.open("-".equals(runId) ? Map.of()
                            : Map.of(LogCorrelation.REQUEST_ID, runId, LogCorrelation.JOB_RUN_ID, runId))) {
                        Throwable cause = rootCause(failure);
                        String correlation = "-".equals(runId) ? "" : ", jobRunId=" + runId;
                        alarmService.alarmThrottled(eventKey(context.getJobDetail().getKey()), "定时任务失败",
                                "job=" + safeJobKey(context.getJobDetail().getKey())
                                + ", errorType=" + cause.getClass().getSimpleName() + correlation);
                    } catch (RuntimeException alarmFailure) {
                        getLog().error("任务失败告警投递异常 event={} jobRunId={} errorType={}",
                                eventKey(context.getJobDetail().getKey()), runId, alarmFailure.getClass().getSimpleName());
                    }
                }
            }
        };
    }

    static String safeJobKey(org.quartz.JobKey key) {
        String value = key == null ? null : key.toString();
        return value != null && value.matches("[A-Za-z0-9_.-]{1,128}") ? value : "UNAVAILABLE";
    }

    /**
     * 任务经 JobInvokeUtil 反射调用,失败会被包成 JobExecutionException → InvocationTargetException → 真实异常;
     * 只取 getCause() 永远得到 InvocationTargetException。逐层解包到最深一层(带环保护)。
     */
    static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            Throwable next = current instanceof java.lang.reflect.InvocationTargetException
                    ? ((java.lang.reflect.InvocationTargetException) current).getTargetException() : current.getCause();
            if (next == null || next == current) break;
            current = next;
        }
        return current == null ? failure : current;
    }

    /** AlarmService 的事件键只接受 [a-z0-9_.-]，任务键（含大写/中文分组）按此归一，超长截断。 */
    static String eventKey(org.quartz.JobKey key) {
        String raw = key == null ? "unknown" : (key.getGroup() + "." + key.getName());
        String normalized = raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        String event = "job.failure." + normalized;
        return event.length() > 80 ? event.substring(0, 80) : event;
    }
}
