package com.chengyinhub.framework.privacy;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.home.PrivateHomeLogScope;
import com.chengyinhub.business.home.PrivateHomeService;
import com.chengyinhub.business.home.PrivateHomeContracts.HomeView;
import java.util.concurrent.*;
import javax.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivateHomeIndependentReviewTest extends PrivateHomeLoggingTest {
    @Test void escapingErrorCannotLeakAfterHttpScopeCloses() {
        PrivateHomePrivacyBoundary boundary=new PrivateHomePrivacyBoundary();
        Throwable failure=assertThrows(Throwable.class,()->boundary.doFilter(new MockHttpServletRequest("GET","/api/native/home"),new MockHttpServletResponse(),(a,b)->{throw new AssertionError(secret);}));
        assertFalse(PrivateHomeLogScope.active());
        assertFalse(failure.toString().contains(secret), "Escaping throwable carries raw request data");
    }
    @Test void escapingErrorCannotLeakAfterServiceScopeCloses() {
        PrivateHomeService target=new PrivateHomeService(){@Override public HomeView getMyHome(){throw new AssertionError(secret);}};
        AspectJProxyFactory factory=new AspectJProxyFactory(target);factory.setProxyTargetClass(true);factory.addAspect(new PrivateHomePrivacyBoundary());
        PrivateHomeService service=factory.getProxy();
        Throwable failure=assertThrows(Throwable.class,service::getMyHome);
        assertFalse(PrivateHomeLogScope.active());
        assertFalse(failure.toString().contains(secret), "Escaping throwable carries raw service data");
    }
    @Test void thresholdDoesNotEnableDebugAndNextWorkerTaskIsClean() throws Exception {
        Logger logger=context.getLogger("private.review.threshold"); logger.setLevel(Level.WARN);
        int count=capture.list.size();
        try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()) {logger.debug(secret);logger.info(secret);logger.warn(secret);}
        assertEquals(count+1,capture.list.size());assertEquals(Level.WARN,capture.list.get(count).getLevel());
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            worker.submit(()->{try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()){logger.error(secret);throw new IllegalStateException();}});
            assertFalse(worker.submit(PrivateHomeLogScope::active).get(5,TimeUnit.SECONDS));
        } finally {worker.shutdownNow();}
        assertFalse(output().contains(secret));
    }
    @Test void asyncAppenderSeesOnlySanitizedEventsAfterScopeHasClosed() throws Exception {
        Logger logger=context.getLogger("private.review.async");logger.setLevel(Level.TRACE);
        AsyncAppender async=new AsyncAppender();async.setContext(context);async.setName("review-private-async");
        ListAppender<ILoggingEvent> target=new ListAppender<>();target.start();async.addAppender(target);async.start();logger.addAppender(async);
        try {
            try(PrivateHomeLogScope ignored=PrivateHomeLogScope.open()){org.slf4j.MDC.put("point",secret);logger.error(secret,new RuntimeException(secret));}
            org.slf4j.MDC.clear();async.stop();assertEquals(1,target.list.size());
            ILoggingEvent event=target.list.get(0);assertFalse(event.getFormattedMessage().contains(secret));assertNull(event.getThrowableProxy());assertTrue(event.getMDCPropertyMap().isEmpty());
        } finally {logger.detachAppender(async);async.stop();}
    }
}
