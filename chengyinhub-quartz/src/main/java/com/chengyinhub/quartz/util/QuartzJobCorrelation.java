package com.chengyinhub.quartz.util;

import java.util.HashMap;
import java.util.Map;
import com.chengyinhub.common.utils.LogCorrelation;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Diagnostic scope only; the coordinator remains the sole owner of scheduling/leases/epochs. */
public final class QuartzJobCorrelation {
    public static final String RUN_ATTRIBUTE = "chengyin.log.jobRunId";
    private static final Logger log = LoggerFactory.getLogger(QuartzJobCorrelation.class);

    private QuartzJobCorrelation() { }

    /** The caller must close in finally/try-with-resources, including coordinator skip and failure. */
    public static LogCorrelation.Scope open(JobExecutionContext context) {
        String runId = LogCorrelation.newId();
        context.put(RUN_ATTRIBUTE, runId); // This firing only, never persisted JobDataMap/lease identity.
        Map<String, String> ids = new HashMap<>();
        ids.put(LogCorrelation.REQUEST_ID, runId);
        ids.put(LogCorrelation.JOB_RUN_ID, runId);
        LogCorrelation.Scope scope = LogCorrelation.open(ids);
        try {
            // An invocation may still be skipped by the original coordinator. Never claim work ran.
            log.info("event=quartz.invocation.begin jobRunId={}", runId);
            return scope;
        } catch (RuntimeException | Error failure) {
            scope.close();
            throw failure;
        }
    }

    public static String runId(JobExecutionContext context) {
        Object value = context.get(RUN_ATTRIBUTE);
        return LogCorrelation.safe(LogCorrelation.JOB_RUN_ID, value instanceof String ? (String) value : null);
    }
}
