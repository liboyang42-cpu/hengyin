package com.chengyinhub.web.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.externalgame.ExternalGameReviewService;
import com.chengyinhub.business.externalgame.ExternalGameReviewTimeoutJob;
import com.chengyinhub.common.core.scheduling.SchedulingLeadership;
import com.chengyinhub.quartz.config.QuartzClusterConfig;
import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;

/** R9 BE-26: a second node must not double-run background work. */
class TwoNodeSchedulingTest
{
    @Test
    void scheduledMethodRunsOnlyOnTheLeader()
    {
        ExternalGameReviewService reviews = mock(ExternalGameReviewService.class);
        ExternalGameReviewTimeoutJob job = new ExternalGameReviewTimeoutJob(reviews);

        ReflectionTestUtils.setField(job, "schedulingLeadership", (SchedulingLeadership) () -> false);
        job.sweep();
        verify(reviews, never()).sweepTimeouts();

        ReflectionTestUtils.setField(job, "schedulingLeadership", (SchedulingLeadership) () -> true);
        job.sweep();
        verify(reviews, times(1)).sweepTimeouts();

        ReflectionTestUtils.setField(job, "schedulingLeadership", null); // no coordinator: old single-node behaviour
        job.sweep();
        verify(reviews, times(2)).sweepTimeouts();
    }

    @Test
    void theQuartzLeaderIsTheSchedulingLeadership()
    {
        assertTrue(SchedulingLeadership.class.isAssignableFrom(MySqlSchedulerCoordinator.class));
    }

    /** Ratchet: every Spring @Scheduled method in production code checks leadership before doing work. */
    @Test
    void everyScheduledMethodIsLeaderGated() throws Exception
    {
        Path root = Paths.get("").toAbsolutePath().getParent();
        List<String> ungated = new ArrayList<>();
        for (String module : new String[] {"chengyinhub-admin", "chengyinhub-framework", "chengyinhub-system", "chengyinhub-quartz"})
        {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(main)) continue;
            try (Stream<Path> files = Files.walk(main))
            {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator)
                {
                    String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                    int at = 0;
                    while ((at = src.indexOf("\n    @Scheduled(", at)) >= 0)
                    {
                        int body = src.indexOf('{', src.indexOf(')', src.indexOf("public", at)));
                        int end = Math.min(src.length(), body + 400);
                        if (!src.substring(body, end).contains("SchedulingLeadership.allows(")) ungated.add(f.getFileName().toString());
                        at = body;
                    }
                }
            }
        }
        assertTrue(ungated.isEmpty(), "@Scheduled without a leader check: " + ungated);
    }

    @Test
    void quartzClusterModeIsOffByDefaultAndUsesTheRuoyiClusterValues()
    {
        ConditionalOnProperty on = QuartzClusterConfig.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(on);
        assertEquals("chengyin.quartz.cluster.enabled", on.name()[0]);
        assertEquals("true", on.havingValue());
        assertTrue(!on.matchIfMissing(), "must stay off unless explicitly enabled");

        Properties p = QuartzClusterConfig.clusterProperties();
        assertEquals("true", p.getProperty("org.quartz.jobStore.isClustered"));
        assertEquals("AUTO", p.getProperty("org.quartz.scheduler.instanceId"));
        assertEquals("QRTZ_", p.getProperty("org.quartz.jobStore.tablePrefix"));
        assertEquals("org.springframework.scheduling.quartz.LocalDataSourceJobStore", p.getProperty("org.quartz.jobStore.class"));
    }
}
