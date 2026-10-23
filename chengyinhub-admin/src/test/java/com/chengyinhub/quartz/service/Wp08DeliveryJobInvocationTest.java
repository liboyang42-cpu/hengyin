package com.chengyinhub.quartz.service;

import com.chengyinhub.business.mapper.ImNotificationIntentMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.impl.RoamBadgeDeliveryService;
import com.chengyinhub.business.service.impl.RoamRewardDeliveryService;
import com.chengyinhub.business.task.ImNotificationDeliveryTask;
import com.chengyinhub.business.task.RoamRewardDeliveryTask;
import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.utils.spring.SpringUtils;
import com.chengyinhub.quartz.domain.SysJob;
import com.chengyinhub.quartz.domain.SysJobLog;
import com.chengyinhub.quartz.mapper.SysJobMapper;
import com.chengyinhub.quartz.service.impl.SysJobServiceImpl;
import com.chengyinhub.quartz.util.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.quartz.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real task/Quartz invocation/coordinator; JDBC and consumer boundaries mocked. Not a MySQL proof. */
class Wp08DeliveryJobInvocationTest {
    private static final String R7 = "imNotificationDeliveryTask.deliverPending()";
    private static final String R1 = "roamRewardDeliveryTask.run()";
    private final Map<Long,SysJob> rows = new LinkedHashMap<>();
    private Object originalFactory;
    private MySqlSchedulerCoordinator coordinator;
    private SchedulerJdbcFixture jdbc;
    private SysJobServiceImpl jobs;
    private SysJobMapper jobMapper;
    private Scheduler scheduler;
    private ImNotificationIntentMapper intents;
    private IImService messages;
    private RoamRewardDeliveryService rewards;
    private RoamBadgeDeliveryService badges;
    private ISysJobLogService logs;
    private long fire;

    @BeforeEach void setup() throws Exception {
        originalFactory = ReflectionTestUtils.getField(SpringUtils.class,"beanFactory");
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        intents = mock(ImNotificationIntentMapper.class); messages = mock(IImService.class);
        ImNotificationDeliveryTask im = new ImNotificationDeliveryTask();
        ReflectionTestUtils.setField(im,"mapper",intents); ReflectionTestUtils.setField(im,"imService",messages);
        rewards = mock(RoamRewardDeliveryService.class); badges = mock(RoamBadgeDeliveryService.class);
        factory.registerSingleton("imNotificationDeliveryTask",im);
        factory.registerSingleton("roamRewardDeliveryTask",new RoamRewardDeliveryTask(rewards,badges));
        factory.registerAlias("imNotificationDeliveryTask","imDeliveryAlias");
        jdbc = new SchedulerJdbcFixture();
        coordinator = new MySqlSchedulerCoordinator(jdbc.source,mock(ScheduledExecutorService.class));
        ReflectionTestUtils.setField(coordinator,"running",true);
        factory.registerSingleton("coordinator",coordinator);
        jobMapper = mock(SysJobMapper.class); scheduler = mock(Scheduler.class); jobs = new SysJobServiceImpl();
        ReflectionTestUtils.setField(jobs,"jobMapper",jobMapper); ReflectionTestUtils.setField(jobs,"scheduler",scheduler);
        ReflectionTestUtils.setField(jobs,"coordinator",coordinator); factory.registerSingleton("jobs",jobs);
        logs = mock(ISysJobLogService.class); factory.registerSingleton("logs",logs);
        ReflectionTestUtils.setField(SpringUtils.class,"beanFactory",factory);
        rows.put(1L,job(1,R7)); rows.put(2L,job(2,R1));
        when(jobMapper.selectJobAll()).thenAnswer(call -> new ArrayList<>(rows.values()));
        when(jobMapper.selectJobById(anyLong())).thenAnswer(call -> rows.get(call.<Long>getArgument(0)));
        jobs.init(); coordinator.maintainLeadership();
        assertTrue(coordinator.isLeader()); ReflectionTestUtils.setField(coordinator,"activatedAt",0L);
        assertTrue(ScheduleUtils.whiteList(R7)); assertTrue(ScheduleUtils.whiteList(R1));
    }
    @AfterEach void restore() {
        if (coordinator != null) coordinator.stop();
        ReflectionTestUtils.setField(SpringUtils.class,"beanFactory",originalFactory);
    }
    @Test void seededPausesAreScheduledPausedAndQueuedCallbacksCannotConsume() throws Exception {
        verify(scheduler).pauseJob(ScheduleUtils.getJobKey(1L,"DEFAULT"));
        verify(scheduler).pauseJob(ScheduleUtils.getJobKey(2L,"DEFAULT"));
        callback(1); callback(2);
        verifyNoInteractions(intents,messages,rewards,badges,logs);
        verify(jobMapper,never()).updateJob(any());
    }
    @Test void explicitlyActiveRowsReachActualTasksAndOriginalIntentIdsOnly() throws Exception {
        rows.values().forEach(row -> row.setStatus("0"));
        when(intents.maxPendingId()).thenReturn(8L);
        when(intents.selectPendingPage(0L,8L)).thenReturn(List.of(3L,8L));
        callback(1); callback(2);
        verify(messages).deliverSystemNotification(3L); verify(messages).deliverSystemNotification(8L);
        verifyNoMoreInteractions(messages); verify(rewards).reconcile(); verify(badges).reconcile();
        verify(logs,times(2)).addJobLog(any());
        verify(jobMapper,never()).updateJob(any()); // fixture-only status change, no automatic resume.
    }
    @Test void taskLockPreventsBothRealTasksFromStartingDuringPriorWork() throws Exception {
        rows.values().forEach(row -> row.setStatus("0"));
        for (long id : List.of(1L,2L)) {
            String canonical = CanonicalJobTarget.canonical(rows.get(id).getInvokeTarget());
            try (MySqlSessionLock.Lease previous = MySqlSessionLock.acquire(jdbc.source,"task",canonical,0)) {
                assertNotNull(previous); callback(id);
            }
        }
        verifyNoInteractions(intents,messages,rewards,badges,logs);
    }
    @Test void committedPauseAfterQueueingIsRecheckedBeforeConsumerEntry() throws Exception {
        rows.get(1L).setStatus("0"); JobExecutionContext queued = context(1);
        rows.get(1L).setStatus("1"); new QuartzDisallowConcurrentExecution().execute(queued);
        verifyNoInteractions(intents,messages,logs);
    }
    @Test void sameFireAndStoppedGenerationCannotConsumeAgain() throws Exception {
        rows.get(2L).setStatus("0"); JobExecutionContext queued = context(2);
        new QuartzDisallowConcurrentExecution().execute(queued); new QuartzDisallowConcurrentExecution().execute(queued);
        coordinator.stop(); callback(2);
        verify(rewards,times(1)).reconcile(); verify(badges,times(1)).reconcile(); verify(logs,times(1)).addJobLog(any());
    }
    @Test void rewardFailureStillRunsBadgeRecoveryAndStaysFailedInQuartz() {
        rows.get(2L).setStatus("0"); doThrow(new IllegalStateException("reward-unavailable")).when(rewards).reconcile();
        assertThrows(JobExecutionException.class,() -> callback(2)); verify(badges).reconcile();
        ArgumentCaptor<SysJobLog> log = ArgumentCaptor.forClass(SysJobLog.class); verify(logs).addJobLog(log.capture());
        assertEquals("1",log.getValue().getStatus());
    }
    @Test void admittedTargetDoesNotRelaxCanonicalAliasDuplicateProtection() {
        SysJob duplicate = job(3,"imDeliveryAlias.deliverPending()"); rows.put(3L,duplicate);
        assertThrows(com.chengyinhub.common.exception.ServiceException.class,jobs::init);
        verify(jobMapper,never()).insertJob(any()); verify(jobMapper,never()).updateJob(any());
    }
    private void callback(long id) throws JobExecutionException { new QuartzDisallowConcurrentExecution().execute(context(id)); }
    private JobExecutionContext context(long id) {
        SysJob current = rows.get(id), scheduled = job(id,current.getInvokeTarget()); scheduled.setStatus(current.getStatus());
        JobDataMap data = new JobDataMap(); data.put(ScheduleConstants.TASK_PROPERTIES,scheduled);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(data); when(context.getScheduledFireTime()).thenReturn(new Date(++fire)); return context;
    }
    private SysJob job(long id,String target) {
        SysJob job = new SysJob(); job.setJobId(id); job.setJobName("WP08 isolated test"); job.setJobGroup("DEFAULT");
        job.setInvokeTarget(target); job.setCronExpression(target.equals(R7)?"0 0/1 * * * ?":"0 0/10 * * * ?");
        job.setMisfirePolicy("3"); job.setConcurrent("1"); job.setStatus("1"); return job;
    }
}
