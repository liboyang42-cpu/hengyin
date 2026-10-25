package com.chengyinhub.business.it;

import com.chengyinhub.quartz.config.LeaderJobsQuartzConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Direct SOURCE controls: real Boot/Quartz memory factory, no application/DB/provider boot.
 * Explicit scheduler.start here models only the asynchronous leader callback, not MySQL leadership.
 */
class RestoreHttpStartupContractTest {
    private JsonNode originalConfig;
    private RestoreHttpApplicationChild.StartupStage originalStage;
    private boolean originalAdmission;

    @BeforeEach void retainChildStatics() {
        originalConfig=RestoreHttpApplicationChild.config;
        originalStage=RestoreHttpApplicationChild.startupStage;
        originalAdmission=RestoreHttpApplicationChild.diagnosticAdmitted;
    }
    @AfterEach void restoreChildStatics() {
        RestoreHttpApplicationChild.config=originalConfig;
        RestoreHttpApplicationChild.startupStage=originalStage;
        RestoreHttpApplicationChild.diagnosticAdmitted=originalAdmission;
    }

    private AnnotationConfigApplicationContext quartz() {
        var context=new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(RestoreHttpApplicationChild.PROFILE);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-quartz-control",
                Map.of("spring.quartz.scheduler-name","restore-http-control-"+UUID.randomUUID(),
                        "spring.quartz.job-store-type","memory",
                        "spring.quartz.auto-startup","true",
                        "spring.quartz.wait-for-jobs-to-complete-on-shutdown","false",
                        "spring.quartz.properties.org.quartz.threadPool.threadCount","1")));
        context.register(QuartzAutoConfiguration.class,LeaderJobsQuartzConfig.class,
                RestoreHttpApplicationChild.QuartzShutdownConfiguration.class);
        try {context.refresh();return context;}
        catch (RuntimeException | Error failure) {context.close();throw failure;}
    }

    @Test void actualBootFactoryWaitsOnShutdownWithoutBypassingTheOriginalLeaderCustomizer() throws Exception {
        try(var context=quartz()) {
            Scheduler scheduler=context.getBean(Scheduler.class);
            var factories=context.getBeansOfType(SchedulerFactoryBean.class);
            assertEquals(1,factories.size());
            SchedulerFactoryBean factory=factories.values().iterator().next();
            assertSame(scheduler,factory.getScheduler());
            assertEquals(Boolean.TRUE,new DirectFieldAccessor(factory).getPropertyValue("waitForJobsToCompleteOnShutdown"));
            // The test customizer changes actual wait even when a property alone says false.
            assertFalse(context.getEnvironment().getProperty("spring.quartz.wait-for-jobs-to-complete-on-shutdown",Boolean.class,true));
            assertFalse(factory.isAutoStartup()); // Production leader customizer remains authoritative.
            assertFalse(scheduler.isStarted());
            RestoreHttpApplicationChild.assertQuartzFactory(context,scheduler);
        }
    }

    @Test void aDifferentRealSchedulerCannotStandInForTheSelectedFactory() throws Exception {
        try(var first=quartz();var second=quartz()) {
            Scheduler original=first.getBean(Scheduler.class),foreign=second.getBean(Scheduler.class);
            assertNotSame(original,foreign);
            var failure=assertThrows(RestoreHttpApplicationChild.StartupRejected.class,
                    () -> RestoreHttpApplicationChild.assertQuartzFactory(first,foreign));
            assertEquals(RestoreHttpApplicationChild.StartupReason.ASSERTION_REJECTED,failure.reason);
            assertTrue(failure.getMessage().contains("actual Quartz factory must wait"));
            assertFalse(original.isStarted());assertFalse(foreign.isStarted());
        }
    }

    @Test void realSchedulerCanBecomeStartedAfterTheInitialObservationWithoutForcedAutostart() throws Exception {
        try(var context=quartz();var worker=Executors.newSingleThreadExecutor()) {
            Scheduler actual=context.getBean(Scheduler.class);
            CountDownLatch firstObservation=new CountDownLatch(1),leaderCallback=new CountDownLatch(1);
            AtomicInteger reads=new AtomicInteger();
            Scheduler observed=(Scheduler)Proxy.newProxyInstance(Scheduler.class.getClassLoader(),
                    new Class<?>[]{Scheduler.class},(proxy,method,args) -> {
                        if(method.getName().equals("isStarted") && reads.getAndIncrement()==0) {
                            firstObservation.countDown();
                            assertTrue(leaderCallback.await(2,TimeUnit.SECONDS));
                            return false;
                        }
                        try {return method.invoke(actual,args);}
                        catch(InvocationTargetException failure) {throw failure.getCause();}
                    });
            var waiting=worker.submit(() -> {
                RestoreHttpApplicationChild.awaitScheduler(observed,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
                return null;
            });
            try {
                assertTrue(firstObservation.await(2,TimeUnit.SECONDS));
                assertFalse(actual.isStarted());
                actual.start(); // Controlled stand-in for the existing leader refresh callback only.
                leaderCallback.countDown();
                waiting.get(2,TimeUnit.SECONDS);
                assertTrue(actual.isStarted());assertTrue(reads.get()>=2);
            } finally {
                leaderCallback.countDown();waiting.cancel(true);
            }
        }
    }

    @Test void leadershipTimeoutPreservesTypedFailureAndNeverStartsTheScheduler() throws Exception {
        try(var context=quartz()) {
            Scheduler actual=context.getBean(Scheduler.class);
            var failure=assertThrows(RestoreHttpApplicationChild.StartupRejected.class,
                    () -> RestoreHttpApplicationChild.awaitScheduler(actual,System.nanoTime()-1));
            assertEquals(RestoreHttpApplicationChild.StartupReason.SCHEDULER_LEADERSHIP_TIMEOUT,failure.reason);
            assertFalse(actual.isStarted());
        }
    }

    @Test void startupProjectionContainsOnlyFixedEnumsAndBoundIdentitiesNeverMessagesOrCauses() {
        RestoreHttpApplicationChild.config=RestoreHttpApplicationChild.JSON.createObjectNode()
                .put("sourceSha","a".repeat(40)).put("jarSha256","b".repeat(64))
                .put("classManifestSha256","c".repeat(64)).put("scope","d".repeat(32))
                .put("schema","cy_e2e_auth_"+"d".repeat(32)).put("restoreMode","invalid")
                .put("tokenSecret","private-stack-canary").put("jdbcUrl","private-SQL-canary");
        RestoreHttpApplicationChild.startupStage=RestoreHttpApplicationChild.StartupStage.QUARTZ_FACTORY;
        var failure=new RestoreHttpApplicationChild.StartupRejected("private-provider-message",
                RestoreHttpApplicationChild.StartupReason.ASSERTION_REJECTED);
        failure.initCause(new IllegalStateException("private-cause-canary"));
        JsonNode document=RestoreHttpApplicationChild.startupDocument("FAILED",failure);
        Set<String> fields=new HashSet<>();document.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("sourceSha","jarSha256","classManifestSha256","scope","schema","restoreMode",
                "bootId","javaPid","stage","status","reasonCode","failureType"),fields);
        assertEquals("QUARTZ_FACTORY",document.path("stage").asText());
        assertEquals("ASSERTION_REJECTED",document.path("reasonCode").asText());
        assertEquals("ILLEGAL_STATE",document.path("failureType").asText());
        assertFalse(document.toString().contains("private"));
        assertEquals("UNCLASSIFIED_FAILURE",RestoreHttpApplicationChild.startupDocument("FAILED",
                new AssertionError("private-unknown-canary")).path("reasonCode").asText());
        assertEquals("OTHER",RestoreHttpApplicationChild.startupDocument("FAILED",
                new AssertionError("private-unknown-canary")).path("failureType").asText());
    }
}
