package com.chengyinhub.quartz.util;

import java.util.Map;
import org.springframework.aop.support.AopUtils;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.exception.job.TaskException;
import com.chengyinhub.common.exception.job.TaskException.Code;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.spring.SpringUtils;
import com.chengyinhub.quartz.domain.SysJob;

/**
 * 定时任务工具类
 * 
 * @author ruoyi
 *
 */
public class ScheduleUtils
{
    private static final Map<String, String> EXISTING_BUSINESS_TASK_TARGETS = Map.ofEntries(
            Map.entry("auditBacklogMonitorTask.run()", "com.chengyinhub.business.task.AuditBacklogMonitorTask"),
            Map.entry("beforeStartNotifyTask.run()", "com.chengyinhub.business.task.BeforeStartNotifyTask"),
            Map.entry("clubEventOpsTask.run()", "com.chengyinhub.business.task.ClubEventOpsTask"),
            Map.entry("clubPostMediaReviewTask.run()", "com.chengyinhub.business.task.ClubPostMediaReviewTask"),
            Map.entry("clubSessionReconciliationTask.run()", "com.chengyinhub.business.task.ClubSessionReconciliationTask"),
            Map.entry("dataRetentionCleanupTask.run()", "com.chengyinhub.business.task.DataRetentionCleanupTask"),
            Map.entry("imNotificationDeliveryTask.deliverPending()", "com.chengyinhub.business.task.ImNotificationDeliveryTask"),
            Map.entry("committedOfficialNoticeTask.deliverPending()", "com.chengyinhub.business.task.CommittedOfficialNoticeTask"),
            Map.entry("merchantAftercareTimeoutTask.run()", "com.chengyinhub.business.task.MerchantAftercareTimeoutTask"),
            Map.entry("merchantCommerceReconcileTask.reconcile()", "com.chengyinhub.business.task.MerchantCommerceReconcileTask"),
            Map.entry("merchantCrmDeliveryRecoveryTask.run()", "com.chengyinhub.business.task.MerchantCrmDeliveryRecoveryTask"),
            Map.entry("orderCloseTask.closeExpired()", "com.chengyinhub.business.task.OrderCloseTask"),
            Map.entry("orderCloseTask.reconcileExpiredPaying()", "com.chengyinhub.business.task.OrderCloseTask"),
            Map.entry("piiBackfillTask.run()", "com.chengyinhub.business.task.PiiBackfillTask"),
            Map.entry("refundReconcileTask.reconcile()", "com.chengyinhub.business.task.RefundReconcileTask"),
            Map.entry("registrationAutoVerifyTask.run()", "com.chengyinhub.business.task.RegistrationAutoVerifyTask"),
            Map.entry("roamRewardDeliveryTask.run()", "com.chengyinhub.business.task.RoamRewardDeliveryTask"),
            Map.entry("roamPresenceRevokeAuditTask.run()", "com.chengyinhub.business.task.RoamPresenceRevokeAuditTask"),
            Map.entry("settlementReconciliationTask.run()", "com.chengyinhub.business.task.SettlementReconciliationTask"),
            Map.entry("storeVerifyGrowthTask.run()", "com.chengyinhub.business.task.StoreVerifyGrowthTask"),
            Map.entry("verificationQrcodeRepairTask.repairMissing()", "com.chengyinhub.business.task.VerificationQrcodeRepairTask"),
            Map.entry("withdrawReconciliationTask.run()", "com.chengyinhub.business.task.WithdrawReconciliationTask"),
            Map.entry("withdrawTransferQueryTask.run()", "com.chengyinhub.business.task.WithdrawTransferQueryTask"),
            Map.entry("wxTradeBillDownloadTask.download()", "com.chengyinhub.business.task.WxTradeBillDownloadTask"));
    private static final Logger log = LoggerFactory.getLogger(ScheduleUtils.class);

    /**
     * 得到quartz任务类
     *
     * @param sysJob 执行计划
     * @return 具体执行任务类
     */
    private static Class<? extends Job> getQuartzJobClass(SysJob sysJob)
    {
        boolean isConcurrent = "0".equals(sysJob.getConcurrent());
        return isConcurrent ? QuartzJobExecution.class : QuartzDisallowConcurrentExecution.class;
    }

    /**
     * 构建任务触发对象
     */
    public static TriggerKey getTriggerKey(Long jobId, String jobGroup)
    {
        return TriggerKey.triggerKey(ScheduleConstants.TASK_CLASS_NAME + jobId, jobGroup);
    }

    /**
     * 构建任务键对象
     */
    public static JobKey getJobKey(Long jobId, String jobGroup)
    {
        return JobKey.jobKey(ScheduleConstants.TASK_CLASS_NAME + jobId, jobGroup);
    }

    /**
     * 创建定时任务
     */
    public static void createScheduleJob(Scheduler scheduler, SysJob job) throws SchedulerException, TaskException
    {
        ensureCronHasFutureExecution(job);
        Class<? extends Job> jobClass = getQuartzJobClass(job);
        // 构建job信息
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        JobDetail jobDetail = JobBuilder.newJob(jobClass).withIdentity(getJobKey(jobId, jobGroup)).build();

        // 表达式调度构建器
        CronScheduleBuilder cronScheduleBuilder = CronScheduleBuilder.cronSchedule(job.getCronExpression());
        cronScheduleBuilder = handleCronScheduleMisfirePolicy(job, cronScheduleBuilder);

        // 按新的cronExpression表达式构建一个新的trigger
        CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity(getTriggerKey(jobId, jobGroup))
                .withSchedule(cronScheduleBuilder).build();

        // 放入参数，运行时的方法可以获取
        jobDetail.getJobDataMap().put(ScheduleConstants.TASK_PROPERTIES, job);

        // 判断是否存在
        if (scheduler.checkExists(getJobKey(jobId, jobGroup)))
        {
            // 防止创建时存在数据问题 先移除，然后在执行创建操作
            scheduler.deleteJob(getJobKey(jobId, jobGroup));
        }

        scheduler.scheduleJob(jobDetail, trigger);

        // 暂停任务
        if (job.getStatus().equals(ScheduleConstants.Status.PAUSE.getValue()))
        {
            scheduler.pauseJob(ScheduleUtils.getJobKey(jobId, jobGroup));
        }
    }

    /**
     * 拒绝语法合法但已不可能再次触发的 cron，避免数据库状态与调度器静默分叉。
     */
    public static void ensureCronHasFutureExecution(SysJob job) throws TaskException
    {
        if (CronUtils.getNextExecution(job.getCronExpression()) == null)
        {
            String message = "Cron expression has no future fire time: jobId=" + job.getJobId()
                    + ", jobGroup=" + job.getJobGroup() + ", cron=" + job.getCronExpression();
            log.error(message);
            throw new TaskException(message, Code.CONFIG_ERROR);
        }
    }

    /**
     * 设置定时任务策略
     */
    public static CronScheduleBuilder handleCronScheduleMisfirePolicy(SysJob job, CronScheduleBuilder cb)
            throws TaskException
    {
        switch (job.getMisfirePolicy())
        {
            case ScheduleConstants.MISFIRE_DEFAULT:
                return cb;
            case ScheduleConstants.MISFIRE_IGNORE_MISFIRES:
                return cb.withMisfireHandlingInstructionIgnoreMisfires();
            case ScheduleConstants.MISFIRE_FIRE_AND_PROCEED:
                return cb.withMisfireHandlingInstructionFireAndProceed();
            case ScheduleConstants.MISFIRE_DO_NOTHING:
                return cb.withMisfireHandlingInstructionDoNothing();
            default:
                throw new TaskException("The task misfire policy '" + job.getMisfirePolicy()
                        + "' cannot be used in cron schedule tasks", Code.CONFIG_ERROR);
        }
    }

    /**
     * 检查包名是否为白名单配置
     * 
     * @param invokeTarget 目标字符串
     * @return 结果
     */
    public static boolean whiteList(String invokeTarget)
    {
        String expectedBusinessClass = invokeTarget == null ? null
                : EXISTING_BUSINESS_TASK_TARGETS.get(invokeTarget.trim());
        if (expectedBusinessClass != null)
        {
            Object bean = SpringUtils.getBean(JobInvokeUtil.getBeanName(invokeTarget.trim()));
            return expectedBusinessClass.equals(AopUtils.getTargetClass(bean).getName());
        }
        String packageName = StringUtils.substringBefore(invokeTarget, "(");
        int count = StringUtils.countMatches(packageName, ".");
        if (count > 1)
        {
            return StringUtils.startsWithAny(invokeTarget, Constants.JOB_WHITELIST_STR);
        }
        Object obj = SpringUtils.getBean(StringUtils.split(invokeTarget, ".")[0]);
        String beanPackageName = obj.getClass().getPackage().getName();
        return StringUtils.startsWithAny(beanPackageName, Constants.JOB_WHITELIST_STR)
                && !StringUtils.startsWithAny(beanPackageName, Constants.JOB_ERROR_STR);
    }
}
