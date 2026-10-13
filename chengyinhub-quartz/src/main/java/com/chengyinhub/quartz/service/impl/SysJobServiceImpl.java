package com.chengyinhub.quartz.service.impl;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.ArrayList;
import jakarta.annotation.PostConstruct;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.exception.job.TaskException;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.mapper.SysJobMapper;
import com.chengyinhub.quartz.service.ISysJobService;
import com.chengyinhub.quartz.util.CronUtils;
import com.chengyinhub.quartz.util.ScheduleUtils;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;
import com.chengyinhub.quartz.util.CanonicalJobTarget;
import com.chengyinhub.quartz.util.MySqlSessionLock;

/**
 * 定时任务调度信息 服务层
 * 
 * @author ruoyi
 */
@Service
public class SysJobServiceImpl implements ISysJobService
{
    @Value("${chengyin.jobs.enabled:true}")
    private boolean jobsEnabled = true;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private SysJobMapper jobMapper;

    @Autowired
    private MySqlSchedulerCoordinator coordinator;
    private String loadedDefinitions;

    /**
     * 项目启动时，初始化定时器 主要是防止手动修改数据库导致未同步到定时任务处理（注：不能手动修改数据库ID和任务组名，否则会导致脏数据）
     */
    @PostConstruct
    public void init() throws SchedulerException, TaskException
    {
        if (!jobsEnabled) return;
        List<SysJob> jobList = jobMapper.selectJobAll();
        ensureUniqueDefinitions(jobList, null);
        coordinator.registerQuartz(this::refreshPersistedSchedule, () -> {
            loadedDefinitions = null;
            try { scheduler.standby(); }
            catch (SchedulerException failure) { throw new IllegalStateException(failure); }
        });
    }

    private synchronized void refreshPersistedSchedule() {
        List<SysJob> jobs = new ArrayList<>(jobMapper.selectJobAll());
        ensureUniqueDefinitions(jobs, null);
        jobs.sort(Comparator.comparing(SysJob::getJobId));
        StringBuilder fingerprint = new StringBuilder();
        for (SysJob job : jobs) fingerprint.append(job.getJobId()).append('|').append(job.getJobGroup()).append('|')
                .append(CanonicalJobTarget.canonical(job.getInvokeTarget())).append('|').append(job.getCronExpression()).append('|')
                .append(job.getStatus()).append('|').append(job.getMisfirePolicy()).append('|').append(job.getConcurrent()).append('\n');
        if (fingerprint.toString().equals(loadedDefinitions)) return;
        try {
            scheduler.standby();
            scheduler.clear();
            for (SysJob job : jobs) ScheduleUtils.createScheduleJob(scheduler, job);
            scheduler.start();
            loadedDefinitions = fingerprint.toString();
        } catch (SchedulerException | TaskException failure) { throw new IllegalStateException(failure); }
    }

    private void ensureUniqueDefinitions(List<SysJob> jobs, SysJob replacement) {
        Map<String, Long> targets = new LinkedHashMap<>();
        for (SysJob stored : jobs) {
            if (replacement != null && stored.getJobId().equals(replacement.getJobId())) continue;
            assertUnique(targets, stored);
        }
        if (replacement != null) assertUnique(targets, replacement);
    }

    private void assertUnique(Map<String, Long> targets, SysJob job) {
        String canonical = CanonicalJobTarget.canonical(job.getInvokeTarget());
        if (coordinator.isBuiltin(canonical)) throw new ServiceException("任务与内置计划重复，jobId=" + job.getJobId());
        if (targets.containsKey(canonical)) throw new ServiceException("任务调用重复，jobIds=" + targets.get(canonical) + "," + job.getJobId());
        targets.put(canonical, job.getJobId());
    }

    /** A committed pause/delete/edit also fences already-queued old Quartz callbacks. Manual run remains explicit. */
    public boolean isCurrentInvocation(SysJob scheduled, boolean manual) {
        SysJob current = jobMapper.selectJobById(scheduled.getJobId());
        return current != null && (manual || ScheduleConstants.Status.NORMAL.getValue().equals(current.getStatus()))
                && CanonicalJobTarget.canonical(current.getInvokeTarget()).equals(CanonicalJobTarget.canonical(scheduled.getInvokeTarget()));
    }

    private void afterCommitSchedule(SchedulerChange change) {
        if (!jobsEnabled || !coordinator.isLeader()) return;
        Runnable apply = () -> {
            if (!coordinator.isLeader()) return;
            try { change.run(); }
            catch (SchedulerException | TaskException failure) { throw new IllegalStateException("调度配置已保存，调度同步失败", failure); }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { apply.run(); }
            });
        } else apply.run();
    }

    @FunctionalInterface private interface SchedulerChange { void run() throws SchedulerException, TaskException; }

    /**
     * 获取quartz调度器的计划任务列表
     * 
     * @param job 调度信息
     * @return
     */
    @Override
    public List<SysJob> selectJobList(SysJob job)
    {
        return jobMapper.selectJobList(job);
    }

    /**
     * 通过调度任务ID查询调度信息
     * 
     * @param jobId 调度任务ID
     * @return 调度任务对象信息
     */
    @Override
    public SysJob selectJobById(Long jobId)
    {
        return jobMapper.selectJobById(jobId);
    }

    /**
     * 暂停任务
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int pauseJob(SysJob job) throws SchedulerException
    {
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        job.setStatus(ScheduleConstants.Status.PAUSE.getValue());
        int rows = jobMapper.updateJob(job);
        if (rows > 0)
        {
            afterCommitSchedule(() -> scheduler.pauseJob(ScheduleUtils.getJobKey(jobId, jobGroup)));
        }
        return rows;
    }

    /**
     * 恢复任务
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int resumeJob(SysJob job) throws SchedulerException, TaskException
    {
        ScheduleUtils.ensureCronHasFutureExecution(job);
        try (MySqlSessionLock.Lease definitions = coordinator.definitionLock()) {
            ensureUniqueDefinitions(jobMapper.selectJobAllForUpdate(), job);
            Long jobId = job.getJobId();
            String jobGroup = job.getJobGroup();
            job.setStatus(ScheduleConstants.Status.NORMAL.getValue());
            int rows = jobMapper.updateJob(job);
            if (rows > 0 && jobsEnabled && coordinator.isLeader())
            {
                afterCommitSchedule(() -> {
                    JobKey jobKey = ScheduleUtils.getJobKey(jobId, jobGroup);
                    if (scheduler.checkExists(jobKey)) scheduler.resumeJob(jobKey);
                    else ScheduleUtils.createScheduleJob(scheduler, job);
                });
            }
            return rows;
        }
    }

    /**
     * 删除任务后，所对应的trigger也将被删除
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteJob(SysJob job) throws SchedulerException
    {
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        int rows = jobMapper.deleteJobById(jobId);
        if (rows > 0)
        {
            afterCommitSchedule(() -> scheduler.deleteJob(ScheduleUtils.getJobKey(jobId, jobGroup)));
        }
        return rows;
    }

    /**
     * 批量删除调度信息
     * 
     * @param jobIds 需要删除的任务ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteJobByIds(Long[] jobIds) throws SchedulerException
    {
        for (Long jobId : jobIds)
        {
            SysJob job = jobMapper.selectJobById(jobId);
            deleteJob(job);
        }
    }

    /**
     * 任务调度状态修改
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeStatus(SysJob job) throws SchedulerException, TaskException
    {
        int rows = 0;
        String status = job.getStatus();
        if (ScheduleConstants.Status.NORMAL.getValue().equals(status))
        {
            rows = resumeJob(job);
        }
        else if (ScheduleConstants.Status.PAUSE.getValue().equals(status))
        {
            rows = pauseJob(job);
        }
        return rows;
    }

    /**
     * 立即运行任务
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean run(SysJob job) throws SchedulerException
    {
        if (!jobsEnabled) return false;
        try (MySqlSessionLock.Lease definitions = coordinator.definitionLock()) {
            ensureUniqueDefinitions(jobMapper.selectJobAllForUpdate(), null);
            if (!coordinator.isLeader()) throw new ServiceException("当前实例不是调度主节点，请稍后重试");
            boolean result = false;
            Long jobId = job.getJobId();
            String jobGroup = job.getJobGroup();
            SysJob properties = selectJobById(job.getJobId());
            // 参数
            JobDataMap dataMap = new JobDataMap();
            dataMap.put(ScheduleConstants.TASK_PROPERTIES, properties);
            dataMap.put("chengyin.manualJobInvocation", true);
            JobKey jobKey = ScheduleUtils.getJobKey(jobId, jobGroup);
            if (scheduler.checkExists(jobKey))
            {
                result = true;
                afterCommitSchedule(() -> scheduler.triggerJob(jobKey, dataMap));
            }
            return result;
        }
    }

    /**
     * 新增任务
     * 
     * @param job 调度信息 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertJob(SysJob job) throws SchedulerException, TaskException
    {
        job.setStatus(ScheduleConstants.Status.PAUSE.getValue());
        ScheduleUtils.ensureCronHasFutureExecution(job);
        try (MySqlSessionLock.Lease definitions = coordinator.definitionLock()) {
            ensureUniqueDefinitions(jobMapper.selectJobAllForUpdate(), job);
            int rows = jobMapper.insertJob(job);
            if (rows > 0 && jobsEnabled && coordinator.isLeader())
            {
                afterCommitSchedule(() -> ScheduleUtils.createScheduleJob(scheduler, job));
            }
            return rows;
        }
    }

    /**
     * 更新任务的时间表达式
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateJob(SysJob job) throws SchedulerException, TaskException
    {
        ScheduleUtils.ensureCronHasFutureExecution(job);
        try (MySqlSessionLock.Lease definitions = coordinator.definitionLock()) {
            ensureUniqueDefinitions(jobMapper.selectJobAllForUpdate(), job);
            SysJob properties = selectJobById(job.getJobId());
            int rows = jobMapper.updateJob(job);
            if (rows > 0 && jobsEnabled && coordinator.isLeader())
            {
                afterCommitSchedule(() -> updateSchedulerJob(job, properties.getJobGroup()));
            }
            return rows;
        }
    }

    /**
     * 更新任务
     * 
     * @param job 任务对象
     * @param jobGroup 任务组名
     */
    public void updateSchedulerJob(SysJob job, String jobGroup) throws SchedulerException, TaskException
    {
        ScheduleUtils.ensureCronHasFutureExecution(job);
        Long jobId = job.getJobId();
        // 判断是否存在
        JobKey jobKey = ScheduleUtils.getJobKey(jobId, jobGroup);
        if (scheduler.checkExists(jobKey))
        {
            // 防止创建时存在数据问题 先移除，然后在执行创建操作
            scheduler.deleteJob(jobKey);
        }
        ScheduleUtils.createScheduleJob(scheduler, job);
    }

    /**
     * 校验cron表达式是否有效
     * 
     * @param cronExpression 表达式
     * @return 结果
     */
    @Override
    public boolean checkCronExpressionIsValid(String cronExpression)
    {
        return CronUtils.isValid(cronExpression);
    }
}
