package com.chengyinhub.framework.privacy;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import com.chengyinhub.business.home.PrivateHomeLogScope;
import java.util.Iterator;

/** Producer-side boundary, before any asynchronous sink; isEnabled probes are not events. */
public final class PrivateHomeLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
        implements AppenderAttachable<ILoggingEvent> {
    private final AppenderAttachableImpl<ILoggingEvent> sinks = new AppenderAttachableImpl<>();
    @Override protected void append(ILoggingEvent event) {
        if (!(event instanceof PrivateHomeLogFilter.SafeEvent)
                && (PrivateHomeLogScope.active() || PrivateHomeLogFilter.privateMapper(event.getLoggerName())))
            event = PrivateHomeLogFilter.replacement((LoggerContext) getContext(), event);
        sinks.appendLoopOnAppenders(event);
    }
    @Override public void stop() {
        if (!isStarted()) return;
        super.stop();
        // Logback owns the producer, so it must also drain/close its delegated async/file sinks.
        sinks.detachAndStopAllAppenders();
    }
    @Override public void addAppender(Appender<ILoggingEvent> appender) { sinks.addAppender(appender); }
    @Override public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() { return sinks.iteratorForAppenders(); }
    @Override public Appender<ILoggingEvent> getAppender(String name) { return sinks.getAppender(name); }
    @Override public boolean isAttached(Appender<ILoggingEvent> appender) { return sinks.isAttached(appender); }
    @Override public void detachAndStopAllAppenders() { sinks.detachAndStopAllAppenders(); }
    @Override public boolean detachAppender(Appender<ILoggingEvent> appender) { return sinks.detachAppender(appender); }
    @Override public boolean detachAppender(String name) { return sinks.detachAppender(name); }
}
