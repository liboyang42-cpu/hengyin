package com.chengyinhub.business.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONArray;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.TencentMapClient;
import com.chengyinhub.common.utils.http.HttpRequestException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicRouteServiceImplTest {

    @Test
    void networkFailureFallsBackToThatSegmentDirectly() {
        TencentMapClient mapClient = readyClient();
        when(mapClient.get(anyString()))
                .thenThrow(new HttpRequestException("HTTP request failed", -1, true));
        String result = service(mapClient).buildGeometryJson(
                Arrays.asList(node("30.1", "120.1"), node("30.2", "120.2")));

        JSONArray line = JSONArray.parseArray(result);
        assertEquals(2, line.size());
    }

    @Test
    void oneFailedSegmentDoesNotDiscardSuccessfulSegments() {
        TencentMapClient mapClient = readyClient();
        when(mapClient.get(anyString()))
                .thenReturn("{\"status\":0,\"result\":{\"routes\":[{\"polyline\":[30.1,120.1,100000,100000]}]}}")
                .thenThrow(new HttpRequestException("HTTP request failed", -1, true));

        String result = service(mapClient).buildGeometryJson(Arrays.asList(
                node("30.1", "120.1"), node("30.2", "120.2"), node("30.3", "120.3")));

        JSONArray line = JSONArray.parseArray(result);
        assertEquals(3, line.size());
        assertEquals("30.3", line.getJSONObject(2).getBigDecimal("latitude").stripTrailingZeros().toPlainString());
    }

    @Test
    void degradedRouteIsNotCachedAndProviderRecoveryIsRetried() {
        TencentMapClient mapClient = readyClient();
        when(mapClient.get(anyString()))
                .thenThrow(new HttpRequestException("HTTP request failed", -1, true))
                .thenReturn("{\"status\":0,\"result\":{\"routes\":[{\"polyline\":[30.1,120.1,100000,100000]}]}}");
        TopicRouteServiceImpl service = service(mapClient);
        List<CmsTopicNode> nodes = Arrays.asList(
                node("30.1", "120.1"), node("30.2", "120.2"));

        assertNotNull(service.buildGeometryJson(nodes));
        assertNotNull(service.buildGeometryJson(nodes));

        verify(mapClient, times(2)).get(anyString());
    }

    @Test
    void providerFailureLogNeverContainsResponseSecrets() {
        String phone = "13912345678";
        String token = "route-token-secret";
        String upstreamBody = "private route body";
        String upstreamUrl = "https://provider.invalid/private-path";
        TencentMapClient mapClient = readyClient();
        when(mapClient.get(anyString())).thenReturn("{\"status\":120,\"message\":\""
                + phone + " " + token + " " + upstreamBody + " " + upstreamUrl + "\"}");
        Logger logger = (Logger) LoggerFactory.getLogger(TopicRouteServiceImpl.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertNotNull(service(mapClient).buildGeometryJson(
                    Arrays.asList(node("30.1", "120.1"), node("30.2", "120.2"))));

            String joined = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(joined.contains("status=120"));
            assertFalse(joined.contains(phone));
            assertFalse(joined.contains(token));
            assertFalse(joined.contains(upstreamBody));
            assertFalse(joined.contains(upstreamUrl));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private TencentMapClient readyClient() {
        TencentMapClient mapClient = mock(TencentMapClient.class);
        when(mapClient.isReady()).thenReturn(true);
        return mapClient;
    }

    private TopicRouteServiceImpl service(TencentMapClient mapClient) {
        TopicRouteServiceImpl service = new TopicRouteServiceImpl();
        ReflectionTestUtils.setField(service, "tencentMapClient", mapClient);
        ReflectionTestUtils.setField(service, "mode", "walking");
        return service;
    }

    private CmsTopicNode node(String latitude, String longitude) {
        CmsTopicNode node = new CmsTopicNode();
        node.setLatitude(latitude);
        node.setLongitude(longitude);
        return node;
    }
}
