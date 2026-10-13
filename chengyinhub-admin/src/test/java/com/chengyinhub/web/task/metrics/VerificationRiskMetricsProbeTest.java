package com.chengyinhub.web.task.metrics;

import com.chengyinhub.business.mapper.VerificationReviewMapper;
import com.chengyinhub.business.service.impl.AlarmService;
import org.junit.jupiter.api.Test;
import java.util.Date;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationRiskMetricsProbeTest {
    @Test
    void backlogOrOldSingleReviewAlertsWithoutAutomaticDisposition() {
        for (long[] values : new long[][]{{20,0}, {1,86400}, {19,86399}, {0,0}}) {
            VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);
            AlarmService alarms = mock(AlarmService.class);
            when(mapper.selectPendingQueueStats()).thenReturn(Map.of("pendingCount", values[0], "oldestAgeSeconds", values[1]));
            VerificationRiskMetricsProbe probe = new VerificationRiskMetricsProbe(mapper, alarms);
            assertEquals(2, probe.sample(new Date(), new Date(), new Date()).size());
            if (values[0] >= 20 || values[0] > 0 && values[1] >= 86400) {
                verify(alarms).alarmThrottled(eq("verification.risk.backlog"), eq("核销复核队列积压"),
                        eq("pending=" + values[0] + " oldestAgeSeconds=" + values[1]));
            } else verifyNoInteractions(alarms);
            verify(mapper).selectPendingQueueStats(); verifyNoMoreInteractions(mapper);
        }
    }

    @Test
    void missingMalformedOrNegativeStatsFailSamplingInsteadOfClaimingHealthy() {
        VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);
        VerificationRiskMetricsProbe probe = new VerificationRiskMetricsProbe(mapper, mock(AlarmService.class));
        for (Map<String, Object> stats : java.util.Arrays.<Map<String, Object>>asList(null,
                Map.of("pendingCount", "unknown", "oldestAgeSeconds", 0), Map.of("pendingCount", -1, "oldestAgeSeconds", 0))) {
            when(mapper.selectPendingQueueStats()).thenReturn(stats);
            assertThrows(IllegalStateException.class, () -> probe.sample(new Date(), new Date(), new Date()));
        }
    }
}
