package com.chengyinhub.framework.privacy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.chengyinhub.business.home.PrivateHomeLogScope;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Marker;

/** Replace private payload events, not logging levels or other endpoint observability. */
public final class PrivateHomeLogFilter extends TurboFilter {
    private static final String MAPPER = "com.chengyinhub.business.mapper.PrivateHomeMapper";
    private static final Pattern SLOW = Pattern.compile("^slow sql ([0-9]+) millis\\..*", Pattern.DOTALL);
    @Override public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                                        Object[] arguments, Throwable error) {
        if ((format == null && arguments == null && error == null) || !(PrivateHomeLogScope.active() || privateMapper(logger.getName()))) return FilterReply.NEUTRAL;
        if (!level.isGreaterOrEqual(logger.getEffectiveLevel())) return FilterReply.DENY;
        String event = "private-home event=redacted";
        if (logger.getName().equals("com.alibaba.druid.filter.stat.StatFilter")) {
            Matcher slow = SLOW.matcher(format == null ? "" : format);
            if (slow.matches()) event = "private-home event=slow-sql elapsedMs=" + slow.group(1);
        }
        if (error == null && arguments != null && arguments.length > 0
                && arguments[arguments.length - 1] instanceof Throwable)
            error = (Throwable) arguments[arguments.length - 1];
        if (error != null) event += " errorType=" + error.getClass().getName();
        // Bypass turbo filters for this already-safe replacement. Preserve source, severity and count;
        // do not copy arguments, throwable/cause/suppressed chains, caller data or request MDC.
        LoggingEvent replacement = new SafeEvent(logger, level, event);
        replacement.setMDCPropertyMap(Collections.emptyMap());
        logger.callAppenders(replacement);
        return FilterReply.DENY;
    }
    static void httpSummary(int status, long elapsed) {
        summary("private-home boundary=http status=" + status + " elapsedMs=" + elapsed);
    }
    static void serviceSummary(String operation, boolean completed, long elapsed) {
        // Only source-defined method names, including for nested calls under the HTTP scope.
        if (!operation.matches("getMyHome|setMyHome|deleteMyHome|verifyReturn|restore|cleanupMyExpiredReferences")) operation = "unknown";
        summary("private-home boundary=service operation=" + operation + " completed=" + completed + " elapsedMs=" + elapsed);
    }
    private static void summary(String safeMessage) {
        org.slf4j.Logger source = org.slf4j.LoggerFactory.getLogger(PrivateHomePrivacyBoundary.class);
        if (!(source instanceof Logger) || !source.isInfoEnabled()) return;
        Logger logger = (Logger) source;
        LoggingEvent event = new SafeEvent(logger, Level.INFO, safeMessage);
        event.setMDCPropertyMap(Collections.emptyMap());
        logger.callAppenders(event);
    }
    static boolean privateMapper(String name) { return name != null && (name.equals(MAPPER) || name.startsWith(MAPPER + ".")); }
    static LoggingEvent replacement(ch.qos.logback.classic.LoggerContext context, ch.qos.logback.classic.spi.ILoggingEvent source) {
        String text = "private-home event=redacted";
        if ("com.alibaba.druid.filter.stat.StatFilter".equals(source.getLoggerName()) && source.getMessage() != null) {
            Matcher slow = SLOW.matcher(source.getMessage());
            if (slow.matches()) text = "private-home event=slow-sql elapsedMs=" + slow.group(1);
        }
        if (source.getThrowableProxy() != null) text += " errorType=" + source.getThrowableProxy().getClassName();
        LoggingEvent event = new SafeEvent(context.getLogger(source.getLoggerName()), source.getLevel(), text);
        event.setTimeStamp(source.getTimeStamp());event.setThreadName(source.getThreadName());event.setMDCPropertyMap(Collections.emptyMap());
        return event;
    }
    static final class SafeEvent extends LoggingEvent {
        private SafeEvent(Logger logger, Level level, String message) {
            super(PrivateHomeLogFilter.class.getName(), logger, level, message, null, null);
        }
    }
}
