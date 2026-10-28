package com.chengyinhub.quartz.util;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.springframework.aop.framework.ProxyFactory;

import com.chengyinhub.common.utils.spring.SpringUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.chengyinhub.common.constant.ScheduleConstants;
import com.chengyinhub.common.exception.job.TaskException;
import com.chengyinhub.quartz.domain.SysJob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleUtilsTest
{
    private final Logger scheduleLogger = (Logger) LoggerFactory.getLogger(ScheduleUtils.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

    @AfterEach
    void tearDown()
    {
        scheduleLogger.detachAppender(logAppender);
    }

    @Test
    void cronWithoutFutureFireTimeThrowsBeforeSchedulerMutation() throws Exception
    {
        logAppender.start();
        scheduleLogger.addAppender(logAppender);
        Scheduler scheduler = mock(Scheduler.class);
        SysJob job = job("0 0 0 1 1 ? 2000", ScheduleConstants.Status.NORMAL.getValue());

        TaskException error = assertThrows(TaskException.class, () -> ScheduleUtils.createScheduleJob(scheduler, job));

        assertEquals(TaskException.Code.CONFIG_ERROR, error.getCode());
        List<ILoggingEvent> errors = logAppender.list;
        assertTrue(errors.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                && event.getFormattedMessage().contains("jobId=99")
                && event.getFormattedMessage().contains("0 0 0 1 1 ? 2000")));
        verify(scheduler, never()).checkExists(any(JobKey.class));
        verify(scheduler, never()).deleteJob(any(JobKey.class));
        verify(scheduler, never()).scheduleJob(any(), any());
    }

    @Test
    void validCronIsScheduledAndPausedWhenRequested() throws Exception
    {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);
        SysJob job = job("0/5 * * * * ?", ScheduleConstants.Status.PAUSE.getValue());

        ScheduleUtils.createScheduleJob(scheduler, job);

        verify(scheduler).scheduleJob(any(), any());
        verify(scheduler).pauseJob(ScheduleUtils.getJobKey(job.getJobId(), job.getJobGroup()));
    }

    @ParameterizedTest
    @MethodSource("businessTargets")
    void exactExistingBusinessTargetsAllowOnlyTheirActualType(String target, String className) throws Exception
    {
        Object bean = mock(Class.forName(className));
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class))
        {
            spring.when(() -> SpringUtils.getBean(JobInvokeUtil.getBeanName(target))).thenReturn(bean);
            assertTrue(ScheduleUtils.whiteList(target));
            assertTrue(ScheduleUtils.whiteList("  " + target + "  "));
            verifyNoInteractions(bean);
        }
    }

    @ParameterizedTest
    @MethodSource("businessTargets")
    void exactNamesCannotGrantUnrelatedBeanTypes(String target, String className)
    {
        Object unrelated = new Object();
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class))
        {
            spring.when(() -> SpringUtils.getBean(JobInvokeUtil.getBeanName(target))).thenReturn(unrelated);
            assertFalse(ScheduleUtils.whiteList(target));
        }
    }

    @ParameterizedTest
    @MethodSource("businessTargets")
    void otherBusinessMethodsArgumentsAliasesAndClassFormsStayDenied(String target, String className) throws Exception
    {
        Object bean = mock(Class.forName(className));
        String receiver = JobInvokeUtil.getBeanName(target);
        String method = target.substring(target.indexOf('.') + 1);
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class))
        {
            spring.when(() -> SpringUtils.getBean(receiver)).thenReturn(bean);
            spring.when(() -> SpringUtils.getBean("unlistedBusinessAlias")).thenReturn(bean);
            assertFalse(ScheduleUtils.whiteList(className + "." + method));
            assertFalse(ScheduleUtils.whiteList(target.replace("()", "(1)")));
            assertFalse(ScheduleUtils.whiteList(receiver + ".getClass()"));
            assertFalse(ScheduleUtils.whiteList(receiver + ".unlistedMethod()"));
            assertFalse(ScheduleUtils.whiteList("unlistedBusinessAlias." + method));
            verifyNoInteractions(bean);
        }
    }

    @Test
    void springCglibProxyKeepsTheExactTargetClassCheck()
    {
        Object bean = mock(com.chengyinhub.business.task.DataRetentionCleanupTask.class);
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        Object proxy = factory.getProxy();
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class))
        {
            spring.when(() -> SpringUtils.getBean("dataRetentionCleanupTask")).thenReturn(proxy);
            assertTrue(ScheduleUtils.whiteList("dataRetentionCleanupTask.run()"));
        }
        verifyNoInteractions(bean);
    }

    @Test
    void legacyQuartzAndWebTargetsKeepTheirExistingPolicy()
    {
        Object quartzBean = mock(com.chengyinhub.quartz.task.RyTask.class);
        Object webBean = mock(com.chengyinhub.web.task.MetricsProbeTask.class);
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class))
        {
            spring.when(() -> SpringUtils.getBean("ryTask")).thenReturn(quartzBean);
            spring.when(() -> SpringUtils.getBean("metricsProbeTask")).thenReturn(webBean);
            assertTrue(ScheduleUtils.whiteList("ryTask.ryNoParams()"));
            assertTrue(ScheduleUtils.whiteList("metricsProbeTask.run()"));
            assertTrue(ScheduleUtils.whiteList("com.chengyinhub.quartz.task.RyTask.ryNoParams()"));
            assertTrue(ScheduleUtils.whiteList("com.chengyinhub.web.task.MetricsProbeTask.run()"));
            assertFalse(ScheduleUtils.whiteList("java.net.URL.openConnection()"));
            assertFalse(ScheduleUtils.whiteList("javax.naming.InitialContext.lookup('x')"));
            verifyNoInteractions(quartzBean, webBean);
        }
    }

    static Stream<Arguments> businessTargets()
    {
        return Stream.of(
                Arguments.of("auditBacklogMonitorTask.run()", "com.chengyinhub.business.task.AuditBacklogMonitorTask"),
                Arguments.of("beforeStartNotifyTask.run()", "com.chengyinhub.business.task.BeforeStartNotifyTask"),
                Arguments.of("clubEventOpsTask.run()", "com.chengyinhub.business.task.ClubEventOpsTask"),
                Arguments.of("clubPostMediaReviewTask.run()", "com.chengyinhub.business.task.ClubPostMediaReviewTask"),
                Arguments.of("clubSessionReconciliationTask.run()", "com.chengyinhub.business.task.ClubSessionReconciliationTask"),
                Arguments.of("dataRetentionCleanupTask.run()", "com.chengyinhub.business.task.DataRetentionCleanupTask"),
                Arguments.of("imNotificationDeliveryTask.deliverPending()", "com.chengyinhub.business.task.ImNotificationDeliveryTask"),
                Arguments.of("committedOfficialNoticeTask.deliverPending()", "com.chengyinhub.business.task.CommittedOfficialNoticeTask"),
                Arguments.of("merchantAftercareTimeoutTask.run()", "com.chengyinhub.business.task.MerchantAftercareTimeoutTask"),
                Arguments.of("merchantCommerceReconcileTask.reconcile()", "com.chengyinhub.business.task.MerchantCommerceReconcileTask"),
                Arguments.of("merchantCrmDeliveryRecoveryTask.run()", "com.chengyinhub.business.task.MerchantCrmDeliveryRecoveryTask"),
                Arguments.of("orderCloseTask.closeExpired()", "com.chengyinhub.business.task.OrderCloseTask"),
                Arguments.of("orderCloseTask.reconcileExpiredPaying()", "com.chengyinhub.business.task.OrderCloseTask"),
                Arguments.of("piiBackfillTask.run()", "com.chengyinhub.business.task.PiiBackfillTask"),
                Arguments.of("refundReconcileTask.reconcile()", "com.chengyinhub.business.task.RefundReconcileTask"),
                Arguments.of("registrationAutoVerifyTask.run()", "com.chengyinhub.business.task.RegistrationAutoVerifyTask"),
                Arguments.of("roamRewardDeliveryTask.run()", "com.chengyinhub.business.task.RoamRewardDeliveryTask"),
                Arguments.of("roamPresenceRevokeAuditTask.run()", "com.chengyinhub.business.task.RoamPresenceRevokeAuditTask"),
                Arguments.of("settlementReconciliationTask.run()", "com.chengyinhub.business.task.SettlementReconciliationTask"),
                Arguments.of("storeVerifyGrowthTask.run()", "com.chengyinhub.business.task.StoreVerifyGrowthTask"),
                Arguments.of("verificationQrcodeRepairTask.repairMissing()", "com.chengyinhub.business.task.VerificationQrcodeRepairTask"),
                Arguments.of("withdrawReconciliationTask.run()", "com.chengyinhub.business.task.WithdrawReconciliationTask"),
                Arguments.of("withdrawTransferQueryTask.run()", "com.chengyinhub.business.task.WithdrawTransferQueryTask"),
                Arguments.of("wxTradeBillDownloadTask.download()", "com.chengyinhub.business.task.WxTradeBillDownloadTask"));
    }

    static SysJob job(String cronExpression, String status)
    {
        SysJob job = new SysJob();
        job.setJobId(99L);
        job.setJobName("audit-job");
        job.setJobGroup("DEFAULT");
        job.setInvokeTarget("auditTask.run");
        job.setCronExpression(cronExpression);
        job.setMisfirePolicy(ScheduleConstants.MISFIRE_DEFAULT);
        job.setConcurrent("1");
        job.setStatus(status);
        return job;
    }
}
