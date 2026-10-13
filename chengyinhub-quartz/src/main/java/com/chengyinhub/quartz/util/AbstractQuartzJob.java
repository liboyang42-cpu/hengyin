package com.chengyinhub.quartz.util;

import java.util.Date;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.utils.ExceptionUtil;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.bean.BeanUtils;
import com.chengyinhub.common.utils.spring.SpringUtils;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.domain.SysJobLog;
import com.chengyinhub.quartz.service.ISysJobLogService;
import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;
import com.chengyinhub.quartz.service.impl.SysJobServiceImpl;

/**
 * 抽象quartz调用
 *
 * @author ruoyi
 */
public abstract class AbstractQuartzJob implements Job
{
    private static final Logger log = LoggerFactory.getLogger(AbstractQuartzJob.class);

    /**
     * 线程本地变量
     */
    private static ThreadLocal<Date> threadLocal = new ThreadLocal<>();

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException
    {
        SysJob sysJob = new SysJob();
        Exception executionFailure = null;
        java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();
        try
        {
            BeanUtils.copyBeanProp(sysJob, context.getMergedJobDataMap().get(ScheduleConstants.TASK_PROPERTIES));
            boolean executed = SpringUtils.getBean(MySqlSchedulerCoordinator.class).execute(
                    sysJob.getInvokeTarget(), context.getScheduledFireTime(), () -> {
                        if (!SpringUtils.getBean(SysJobServiceImpl.class).isCurrentInvocation(sysJob,
                                Boolean.TRUE.equals(context.getMergedJobDataMap().get("chengyin.manualJobInvocation")))) return;
                        invoked.set(true);
                        before(context, sysJob);
                        try { doExecute(context, sysJob); }
                        catch (Exception original) { failure.set(original); }
                    });
            if (!executed || !invoked.get()) return;
            executionFailure = failure.get();
        }
        catch (Exception e)
        {
            log.error("任务执行异常  - ：", e);
            executionFailure = failure.get();
            if (executionFailure == null) executionFailure = e;
            else executionFailure.addSuppressed(e);
        }

        try
        {
            after(context, sysJob, executionFailure);
        }
        catch (Exception logFailure)
        {
            log.error("任务日志写入异常 - jobId:{}", sysJob.getJobId(), logFailure);
            if (executionFailure == null)
            {
                executionFailure = logFailure;
            }
            else
            {
                executionFailure.addSuppressed(logFailure);
            }
        }

        if (executionFailure != null)
        {
            throw new JobExecutionException(executionFailure);
        }
    }

    /**
     * 执行前
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     */
    protected void before(JobExecutionContext context, SysJob sysJob)
    {
        threadLocal.set(new Date());
    }

    /**
     * 执行后
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     */
    protected void after(JobExecutionContext context, SysJob sysJob, Exception e)
    {
        Date startTime = threadLocal.get();
        threadLocal.remove();
        if (startTime == null)
        {
            startTime = new Date();
        }

        final SysJobLog sysJobLog = new SysJobLog();
        sysJobLog.setJobName(sysJob.getJobName());
        sysJobLog.setJobGroup(sysJob.getJobGroup());
        sysJobLog.setInvokeTarget(sysJob.getInvokeTarget());
        sysJobLog.setStartTime(startTime);
        sysJobLog.setStopTime(new Date());
        long runMs = sysJobLog.getStopTime().getTime() - sysJobLog.getStartTime().getTime();
        sysJobLog.setJobMessage(sysJobLog.getJobName() + " 总共耗时：" + runMs + "毫秒");
        if (e != null)
        {
            sysJobLog.setStatus(Constants.FAIL);
            String errorMsg = StringUtils.substring(ExceptionUtil.getExceptionMessage(e), 0, 2000);
            sysJobLog.setExceptionInfo(errorMsg);
        }
        else
        {
            sysJobLog.setStatus(Constants.SUCCESS);
        }

        // 写入数据库当中
        SpringUtils.getBean(ISysJobLogService.class).addJobLog(sysJobLog);
    }

    /**
     * 执行方法，由子类重载
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     * @throws Exception 执行过程中的异常
     */
    protected abstract void doExecute(JobExecutionContext context, SysJob sysJob) throws Exception;
}
