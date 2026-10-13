package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.ServiceStatusVO;
import com.chengyinhub.business.service.impl.ServiceStatusFeedServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ServiceStatusFeedServiceImplTest {
    @TempDir
    Path tempDir;

    @Test
    void missingMonitorSnapshotFailsClosedInsteadOfClaimingGreen() {
        ServiceStatusVO result = new ServiceStatusFeedServiceImpl(new ObjectMapper(),
                "/definitely/not/a/status/feed.json").read();

        assertEquals("UNKNOWN", result.getOverallStatus());
        assertEquals("EMBEDDED_FALLBACK", result.getSource());
        assertFalse(result.getComponents().isEmpty());
        assertEquals("UNKNOWN", result.getComponents().get(0).getStatus());
    }

    @Test
    void publisherWritesFreshAtomicHeartbeatThatReaderAccepts() {
        Path feed = tempDir.resolve("status.json");
        ServiceStatusFeedServiceImpl service = new ServiceStatusFeedServiceImpl(
                new ObjectMapper(), feed.toString(), 90L);

        service.publishCoreApiHeartbeat();
        ServiceStatusVO result = service.read();

        assertEquals("OPERATIONAL", result.getOverallStatus());
        assertEquals("LOCAL_STATIC_SNAPSHOT", result.getSource());
        assertEquals("CORE_API", result.getComponents().get(0).getCode());
        assertEquals("OPERATIONAL", result.getComponents().get(0).getStatus());
    }

    @Test
    void staleSnapshotFailsClosedEvenWhenItClaimsOperational() throws Exception {
        Path feed = tempDir.resolve("stale.json");
        ServiceStatusVO stale = new ServiceStatusVO();
        stale.setOverallStatus("OPERATIONAL");
        stale.setUpdatedAt(Instant.now().minusSeconds(300).toString());
        stale.setIncidents(Collections.emptyList());
        ServiceStatusVO.Component api = new ServiceStatusVO.Component();
        api.setCode("CORE_API");
        api.setName("城瘾核心服务");
        api.setStatus("OPERATIONAL");
        api.setUpdatedAt(stale.getUpdatedAt());
        stale.setComponents(Collections.singletonList(api));
        new ObjectMapper().writeValue(feed.toFile(), stale);

        ServiceStatusVO result = new ServiceStatusFeedServiceImpl(
                new ObjectMapper(), feed.toString(), 90L).read();

        assertEquals("UNKNOWN", result.getOverallStatus());
        assertEquals("EMBEDDED_FALLBACK", result.getSource());
    }
}
