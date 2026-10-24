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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicRouteServiceImplTest {

    @Test
    void networkFailureIsExplicitlyUnavailable() {
        TencentMapClient mapClient = readyClient();
        when(mapClient.get(anyString()))
                .thenThrow(new HttpRequestException("HTTP request failed", -1, true));
        String result = service(mapClient).buildGeometryJson(
                Arrays.asList(node("30.1", "120.1"), node("30.2", "120.2")));

        assertNull(result);
    }

    @Test
    void oneFailedSegmentCannotPublishAPartiallySyntheticRoute() {
        TencentMapClient mapClient = readyClient();
        when(mapClient.get(anyString()))
                .thenReturn("{\"status\":0,\"result\":{\"routes\":[{\"polyline\":[30.1,120.1,100000,100000]}]}}")
                .thenThrow(new HttpRequestException("HTTP request failed", -1, true));

        String result = service(mapClient).buildGeometryJson(Arrays.asList(
                node("30.1", "120.1"), node("30.2", "120.2"), node("30.3", "120.3")));

        assertNull(result);
        verify(mapClient, times(2)).get(anyString());
    }

    @Test
    void degradedRouteIsNotCachedAndProviderRecoveryIsRetried() {
        TencentMapClient mapClient = readyClient();
        when(mapClient.get(anyString()))
                .thenThrow(new HttpRequestException("HTTP request failed", -1, true))
                .thenReturn("{\"status\":0,\"result\":{\"routes\":[{\"polyline\":[30.1,120.1,50000,50000,50000,50000]}]}}");
        TopicRouteServiceImpl service = service(mapClient);
        List<CmsTopicNode> nodes = Arrays.asList(
                node("30.1", "120.1"), node("30.2", "120.2"));

        String degraded = service.buildGeometryJson(nodes);
        String recovered = service.buildGeometryJson(nodes);
        assertNull(degraded);
        assertEquals(3, JSONArray.parseArray(recovered).size());
        assertNotEquals(degraded, recovered);
        assertEquals(recovered, service.buildGeometryJson(nodes));

        verify(mapClient, times(2)).get(anyString());
    }

    @Test
    void successfulRouteIsCachedButOversizedRouteIsStillReturnedWithoutRetention() {
        TencentMapClient client = readyClient();
        when(client.get(anyString())).thenReturn(successfulResponse());
        List<CmsTopicNode> nodes = Arrays.asList(node("30.1", "120.1"), node("30.2", "120.2"));
        TopicRouteServiceImpl cached = service(client);
        String expected = cached.buildGeometryJson(nodes);
        assertEquals(expected, cached.buildGeometryJson(nodes));
        verify(client, times(1)).get(anyString());

        TopicRouteServiceImpl small = configure(new TopicRouteServiceImpl(true, 1024, 300, 300), client);
        assertEquals(expected, small.buildGeometryJson(nodes));
        assertEquals(expected, small.buildGeometryJson(nodes));
        verify(client, times(3)).get(anyString());
    }

    @Test
    void disabledCacheStillUsesTheSameProviderClientOnEverySegment() {
        TencentMapClient client = readyClient();
        when(client.get(anyString())).thenReturn(successfulResponse());
        TopicRouteServiceImpl disabled = configure(new TopicRouteServiceImpl(false, 1024, 1024, 300), client);
        List<CmsTopicNode> nodes = Arrays.asList(
                node("30.1", "120.1"), node("30.2", "120.2"), node("30.3", "120.3"));
        String first = disabled.buildGeometryJson(nodes);
        assertEquals(first, disabled.buildGeometryJson(nodes));
        verify(client, times(4)).get(anyString());
    }

    @Test
    void failedStatusAndMalformedResponsesNeverPoisonCache() {
        TencentMapClient client = readyClient();
        when(client.get(anyString())).thenReturn("{\"status\":120}", "broken-json", successfulResponse());
        TopicRouteServiceImpl service = service(client);
        List<CmsTopicNode> nodes = Arrays.asList(node("30.1", "120.1"), node("30.2", "120.2"));
        assertNull(service.buildGeometryJson(nodes));
        assertNull(service.buildGeometryJson(nodes));
        String recovered = service.buildGeometryJson(nodes);
        assertEquals(3, JSONArray.parseArray(recovered).size());
        assertEquals(recovered, service.buildGeometryJson(nodes));
        verify(client, times(3)).get(anyString());
    }

    @Test
    void blockedProviderDoesNotHoldCacheLockOrBlockUnrelatedHits() throws Exception {
        TencentMapClient client = readyClient();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(client.get(anyString())).thenAnswer(invocation -> {
            if (invocation.<String>getArgument(0).contains("to=30.300000")) {
                providerEntered.countDown();
                assertTrue(releaseProvider.await(5, TimeUnit.SECONDS));
            }
            return successfulResponse();
        });
        TopicRouteServiceImpl service = service(client);
        List<CmsTopicNode> warm = Arrays.asList(node("30.1", "120.1"), node("30.2", "120.2"));
        String expected = service.buildGeometryJson(warm);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> slow = workers.submit(() -> service.buildGeometryJson(
                    Arrays.asList(node("30.1", "120.1"), node("30.3", "120.3"))));
            assertTrue(providerEntered.await(5, TimeUnit.SECONDS));
            assertEquals(expected, workers.submit(() -> service.buildGeometryJson(warm)).get(2, TimeUnit.SECONDS));
            releaseProvider.countDown();
            assertEquals(expected, slow.get(5, TimeUnit.SECONDS));
        } finally {
            releaseProvider.countDown();
            workers.shutdownNow();
        }
        verify(client, times(2)).get(anyString());
    }

    private String successfulResponse() {
        return "{\"status\":0,\"result\":{\"routes\":[{\"polyline\":[30.1,120.1,50000,50000,50000,50000]}]}}";
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
            assertNull(service(mapClient).buildGeometryJson(
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
        return configure(new TopicRouteServiceImpl(), mapClient);
    }

    private TopicRouteServiceImpl configure(TopicRouteServiceImpl service, TencentMapClient mapClient) {
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
