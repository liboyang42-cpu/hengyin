package com.chengyinhub.framework.privacy;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.home.PrivateHomeLogScope;
import com.chengyinhub.business.home.PrivateHomeService;
import com.chengyinhub.business.home.PrivateHomeContracts.*;
import com.chengyinhub.web.controller.api.ApiPrivateHomeController;
import java.math.BigDecimal;
import java.util.concurrent.*;
import javax.servlet.ServletException;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrivateHomeLoggingTest {
    LoggerContext context; Logger root; Level previous, previousBoundary, previousSpring; PrivateHomeLogFilter filter; ListAppender<ILoggingEvent> capture;
    PrivateHomeLogAppender producer;java.util.List<ch.qos.logback.core.Appender<ILoggingEvent>> previousAppenders;
    final String secret="30.123456";
    @BeforeEach void setup() {
        context=(LoggerContext)LoggerFactory.getILoggerFactory(); root=context.getLogger(Logger.ROOT_LOGGER_NAME);
        previous=root.getLevel(); root.setLevel(Level.TRACE); previousBoundary=context.getLogger(PrivateHomePrivacyBoundary.class).getLevel();context.getLogger(PrivateHomePrivacyBoundary.class).setLevel(Level.INFO); previousSpring=context.getLogger("org.springframework.web").getLevel();context.getLogger("org.springframework.web").setLevel(Level.TRACE); filter=new PrivateHomeLogFilter();filter.start();context.addTurboFilter(filter);
        capture=new ListAppender<>();capture.start();
        previousAppenders=new java.util.ArrayList<>();root.iteratorForAppenders().forEachRemaining(previousAppenders::add);
        for(ch.qos.logback.core.Appender<ILoggingEvent> appender:previousAppenders)root.detachAppender(appender);
        producer=new PrivateHomeLogAppender();producer.setContext(context);producer.setName("private-test-producer");producer.start();
        for(ch.qos.logback.core.Appender<ILoggingEvent> appender:previousAppenders)producer.addAppender(appender);
        producer.addAppender(capture);root.addAppender(producer);
    }
    @AfterEach void cleanup() {root.detachAppender(producer);for(ch.qos.logback.core.Appender<ILoggingEvent> appender:previousAppenders){producer.detachAppender(appender);root.addAppender(appender);}producer.stop();context.getTurboFilterList().remove(filter);root.setLevel(previous);context.getLogger(PrivateHomePrivacyBoundary.class).setLevel(previousBoundary);context.getLogger("org.springframework.web").setLevel(previousSpring);MDC.clear();}
    String output(){StringBuilder s=new StringBuilder();for(ILoggingEvent e:capture.list){s.append(e.getFormattedMessage()).append(e.getMDCPropertyMap());if(e.getThrowableProxy()!=null)s.append(e.getThrowableProxy().getMessage());}return s.toString();}
    @Test void allSecondaryLoggersRedactBodiesCausesParametersAndMdcWithinScope() {
        Logger sql=context.getLogger("com.chengyinhub.business.mapper.PrivateHomeMapper.writeHome");sql.setLevel(Level.TRACE);
        Logger secondary=context.getLogger("private.test.jdbc");secondary.setLevel(Level.TRACE);
        try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()) {
            MDC.put("location",secret);sql.debug("Parameters: {}",secret);
            secondary.error("body {}",secret,new IllegalArgumentException("location="+secret,new RuntimeException(secret)));
            secondary.trace("row={}",secret);
            secondary.error((String)null,new RuntimeException(secret));
        }
        assertEquals(4,capture.list.size());assertFalse(output().contains(secret));
        assertTrue(output().contains("IllegalArgumentException"));assertEquals(Level.ERROR,capture.list.get(1).getLevel());
        assertEquals("private.test.jdbc",capture.list.get(1).getLoggerName());
        MDC.clear();secondary.info("ordinary={}",secret);assertTrue(output().contains("ordinary="+secret));
        assertFalse(PrivateHomeLogScope.active());
    }
    @Test void mapperTraceIsRedactedEvenOutsideHttpAndSlowTimingSurvives() {
        Logger mapper=context.getLogger("com.chengyinhub.business.mapper.PrivateHomeMapper.findHome");mapper.setLevel(Level.TRACE);
        mapper.trace("Row: {}",secret);
        try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()) {
            context.getLogger("com.alibaba.druid.filter.stat.StatFilter").error("slow sql 1234 millis. select ? ["+secret+"]");
        }
        assertFalse(output().contains(secret));assertTrue(output().contains("elapsedMs=1234"));
    }
    @Test void privateHttpBindingAndOwnerResponseNeverAppearInLogs() throws Exception {
        ApiPrivateHomeController controller=new ApiPrivateHomeController();PrivateHomeService service=mock(PrivateHomeService.class);
        ReflectionTestUtils.setField(controller,"service",service);Home home=new Home();home.version=1L;home.status="ACTIVE";home.latitude=new BigDecimal(secret);home.longitude=new BigDecimal("120.123456");home.label="PRIVATE_LABEL";
        when(service.getMyHome()).thenReturn(new HomeView(home));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).addFilters(new PrivateHomePrivacyBoundary()).build();capture.list.clear();
        mvc.perform(get("/api/native/home")).andExpect(jsonPath("data.latitude").value(30.123456)).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(put("/api/native/home").contentType("application/json").content("{\"latitude\":\""+secret+"bad\"}"))
            .andExpect(jsonPath("msg").value("HOME_REQUEST_INVALID"));
        assertFalse(output().contains(secret));assertFalse(output().contains("PRIVATE_LABEL"));assertFalse(PrivateHomeLogScope.active());
        assertTrue(output().contains("boundary=http"));
    }
    @Test void escapingFailureIsSanitizedAndNextRequestIsUnaffected() throws Exception {
        PrivateHomePrivacyBoundary boundary=new PrivateHomePrivacyBoundary();MockHttpServletRequest request=new MockHttpServletRequest("PUT","/api/native/home");
        ServletException failure=assertThrows(ServletException.class,()->boundary.doFilter(request,new MockHttpServletResponse(),(a,b)->{throw new ServletException(secret,new RuntimeException(secret));}));
        assertEquals("PRIVATE_HOME_REQUEST_FAILED",failure.getMessage());assertNull(failure.getCause());assertFalse(output().contains(secret));assertFalse(PrivateHomeLogScope.active());
        boundary.doFilter(new MockHttpServletRequest("GET","/api/other"),new MockHttpServletResponse(),(a,b)->context.getLogger("private.test.other").info(secret));
        assertTrue(output().contains(secret));
    }
    @Test void scopeIsNestedAndThreadConfined() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try(PrivateHomeLogScope outer=PrivateHomeLogScope.open()) {
            try(PrivateHomeLogScope inner=PrivateHomeLogScope.open()){assertTrue(PrivateHomeLogScope.active());}
            assertTrue(PrivateHomeLogScope.active());assertFalse(pool.submit(PrivateHomeLogScope::active).get());
        } finally {pool.shutdownNow();}
        assertFalse(PrivateHomeLogScope.active());
    }
    @Test void aspectProtectsInternalServiceAndClearsOnFailure() {
        PrivateHomeService target=new PrivateHomeService(){@Override public HomeView getMyHome(){context.getLogger("private.test.provider").error(secret);throw new IllegalStateException(secret);}};
        AspectJProxyFactory factory=new AspectJProxyFactory(target);factory.setProxyTargetClass(true);factory.addAspect(new PrivateHomePrivacyBoundary());
        PrivateHomeService service=factory.getProxy();assertEquals("HOME_UNAVAILABLE",assertThrows(com.chengyinhub.common.exception.ServiceException.class,service::getMyHome).getMessage());
        assertFalse(output().contains(secret));assertTrue(output().contains("boundary=service"));assertFalse(PrivateHomeLogScope.active());
    }
    @Test void enabledApiGuardRejectsMissingRedactionAndUnsafeExtraStatFilter() {
        org.springframework.beans.factory.support.DefaultListableBeanFactory beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        PrivateHomePrivacyConfiguration config=new PrivateHomePrivacyConfiguration();
        org.springframework.beans.factory.SmartInitializingSingleton guard=config.privateHomePrivacyReadyGuard(beans.getBeanProvider(com.alibaba.druid.pool.DruidDataSource.class));
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
        context.getTurboFilterList().remove(filter);
        assertEquals("HOME_PRIVACY_LOGGING_UNAVAILABLE",assertThrows(IllegalStateException.class,guard::afterSingletonsInstantiated).getMessage());
        context.addTurboFilter(filter);
        com.alibaba.druid.pool.DruidDataSource ds=new PrivateHomeDruidDataSource();
        ds.setProxyFilters(java.util.Collections.singletonList(new com.alibaba.druid.filter.stat.StatFilter()));beans.registerSingleton("unsafeExtraSource",ds);
        assertEquals("HOME_PRIVACY_SQL_STATS_UNAVAILABLE",assertThrows(IllegalStateException.class,guard::afterSingletonsInstantiated).getMessage());
        ds.setProxyFilters(java.util.Collections.singletonList(new PrivateHomeStatFilter()));
        // Druid appends setProxyFilters entries, so explicitly verify an unsafe existing one still fails.
        assertThrows(IllegalStateException.class,guard::afterSingletonsInstantiated);
        ds.close();
    }
    @Test void safeSummariesSurviveNestedScopeWithoutRequestMdc() {
        MDC.put("private-field",secret);
        try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()) {
            PrivateHomeLogFilter.serviceSummary("getMyHome",true,12);
            PrivateHomeLogFilter.httpSummary(200,13);
        }
        assertEquals(2,capture.list.size());assertTrue(output().contains("operation=getMyHome completed=true elapsedMs=12"));
        assertTrue(output().contains("boundary=http status=200 elapsedMs=13"));assertFalse(output().contains(secret));
    }
    @Test void redactionDoesNotReenableDisabledLogLevels() {
        Logger logger=context.getLogger("private.test.disabled");Level previous=logger.getLevel();logger.setLevel(Level.OFF);
        try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()) {logger.error("secret {}",secret);assertFalse(logger.isDebugEnabled());}
        finally {logger.setLevel(previous);}
        assertEquals(0,capture.list.size());
    }
    @Test void nullMessageWithMdcIsOneSafeEventAndEnablementProbesEmitNothing() {
        Logger logger=context.getLogger("private.test.null");logger.setLevel(Level.INFO);MDC.put("point",secret);
        try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()) {
            assertTrue(logger.isInfoEnabled());assertFalse(logger.isDebugEnabled());assertEquals(0,capture.list.size());
            if(logger.isInfoEnabled())logger.info((String)null);
        }
        assertEquals(1,capture.list.size());assertEquals(Level.INFO,capture.list.get(0).getLevel());assertFalse(output().contains(secret));
        assertTrue(capture.list.get(0).getMDCPropertyMap().isEmpty());
    }
    @Test void nullMessageIsSanitizedBeforeAsyncQueue() {
        Logger logger=context.getLogger("private.test.null.async");logger.setLevel(Level.INFO);
        AsyncAppender async=new AsyncAppender();async.setContext(context);async.setName("null-async");
        ListAppender<ILoggingEvent> downstream=new ListAppender<>();downstream.start();async.addAppender(downstream);async.start();
        PrivateHomeLogAppender beforeQueue=new PrivateHomeLogAppender();beforeQueue.setContext(context);beforeQueue.setName("null-producer");beforeQueue.addAppender(async);beforeQueue.start();logger.addAppender(beforeQueue);
        try {
            try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()){MDC.put("point",secret);if(logger.isInfoEnabled())logger.info((String)null);}
            MDC.clear();beforeQueue.stop();assertFalse(async.isStarted());assertFalse(downstream.isStarted());assertDoesNotThrow(beforeQueue::stop);assertEquals(1,downstream.list.size());
            assertTrue(downstream.list.get(0).getMDCPropertyMap().isEmpty());assertEquals("private-home event=redacted",downstream.list.get(0).getFormattedMessage());
        } finally {logger.detachAppender(beforeQueue);beforeQueue.stop();async.stop();}
    }
    @Test void shippedLogbackConfigurationGuardsRootAndUserSinks() throws Exception {
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("home-logback-qa-");
        String xml=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/resources/logback.xml")),java.nio.charset.StandardCharsets.UTF_8)
            .replace("/home/ruoyi/logs",directory.toString()).replace(" - %msg%n", " - %msg %X%n");
        LoggerContext isolated=new LoggerContext();
        try {
            ch.qos.logback.classic.joran.JoranConfigurator config=new ch.qos.logback.classic.joran.JoranConfigurator();config.setContext(isolated);
            config.doConfigure(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertFalse(isolated.getStatusManager().getCopyOfStatusList().stream().anyMatch(status->status.getLevel()>=ch.qos.logback.core.status.Status.ERROR));
            java.util.List<ch.qos.logback.core.Appender<ILoggingEvent>> nested=new java.util.ArrayList<>();
            for(Logger logger:isolated.getLoggerList())logger.iteratorForAppenders().forEachRemaining(appender->{
                assertTrue(appender instanceof PrivateHomeLogAppender);((PrivateHomeLogAppender)appender).iteratorForAppenders().forEachRemaining(nested::add);
            });
            assertFalse(nested.isEmpty());assertTrue(nested.stream().allMatch(ch.qos.logback.core.Appender::isStarted));
            try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()) {
                MDC.put("point",secret);isolated.getLogger("sys-user").info((String)null);
                isolated.getLogger("private.test.config").error("point={}",secret,new IllegalArgumentException(secret));
            }
            MDC.clear();isolated.getLogger("ordinary.test.config").info("ORDINARY_CONTROL");
            isolated.stop();assertTrue(nested.stream().noneMatch(ch.qos.logback.core.Appender::isStarted));
            String info=new String(java.nio.file.Files.readAllBytes(directory.resolve("sys-info.log")),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(info.contains("ORDINARY_CONTROL"));assertTrue(info.contains("private-home event=redacted"));
            for(String name:java.util.Arrays.asList("sys-info.log","sys-error.log","sys-user.log")) {
                String output=new String(java.nio.file.Files.readAllBytes(directory.resolve(name)),java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(output.contains(secret),name);
            }
        } finally {isolated.stop();MDC.clear();}
    }
}
