package com.chengyinhub.web.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.IImRealtimePublisher;

class ImRealtimeHubTest
{
    @Test
    void streamsAreCappedPerMemberAndOverall()
    {
        ImRealtimeHub hub = new ImRealtimeHub(2, 3);
        try
        {
            assertNotNull(hub.open(1, 9));
            assertNotNull(hub.open(1, 9));
            assertNull(hub.open(1, 10), "per-member cap");
            assertNotNull(hub.open(2, 9));
            assertNull(hub.open(3, 9), "node cap");
            assertEquals(3, hub.openStreams());
            assertTrue(hub.hasSubscribers(9));
            assertFalse(hub.hasSubscribers(10));
        }
        finally { hub.shutdown(); }
    }

    @Test
    void deliversOnlyToTheConversationsSubscribers()
    {
        ImRealtimeHub hub = new ImRealtimeHub(6, 100);
        try
        {
            hub.open(1, 9);
            hub.open(2, 9);
            hub.open(3, 10);
            assertEquals(2, hub.deliverLocal(9, "message.created", "{}"));
            assertEquals(1, hub.deliverLocal(10, "typing", "{}"));
            assertEquals(0, hub.deliverLocal(11, "typing", "{}"));
        }
        finally { hub.shutdown(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value)
    {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @Test
    void withoutRedisTheBusDeliversLocally()
    {
        ImRealtimeHub hub = mock(ImRealtimeHub.class);
        ImRealtimeBus bus = new ImRealtimeBus(hub, provider(null), provider(null), true);
        bus.publish(9, IImRealtimePublisher.MESSAGE_CREATED, Map.of("message_id", "70"));
        verify(hub).deliverLocal(eq(9L), eq("message.created"), anyString());
    }

    @Test
    void withRedisAndAListenerTheBusPublishesOnceAndDeliversFromTheSubscription()
    {
        ImRealtimeHub hub = mock(ImRealtimeHub.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ImRealtimeBus bus = new ImRealtimeBus(hub, provider(redis), provider((RedisConnectionFactory) null), true);
        // 本机未在订阅：发布到 Redis，同时直投本机（本机可能还没有订阅者）。
        bus.publish(9, IImRealtimePublisher.TYPING, Map.of("member_id", "3"));
        verify(redis).convertAndSend(eq(ImRealtimeBus.TOPIC), anyString());
        verify(hub).deliverLocal(eq(9L), eq("typing"), anyString());
    }

    @Test
    void redisFailureFallsBackToLocalDelivery()
    {
        ImRealtimeHub hub = mock(ImRealtimeHub.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("down")).when(redis).convertAndSend(anyString(), anyString());
        ImRealtimeBus bus = new ImRealtimeBus(hub, provider(redis), provider((RedisConnectionFactory) null), true);
        bus.publish(9, IImRealtimePublisher.MESSAGE_UPDATED, Map.of("message_id", "70"));
        verify(hub).deliverLocal(eq(9L), eq("message.updated"), anyString());
    }

    @Test
    void subscriptionMessagesReachTheLocalHubAsClientEvents()
    {
        ImRealtimeHub hub = mock(ImRealtimeHub.class);
        ImRealtimeBus bus = new ImRealtimeBus(hub, provider(null), provider(null), true);
        String envelope = "{\"c\":9,\"t\":\"reaction.updated\",\"d\":{\"message_id\":\"70\"}}";
        bus.onMessage(new DefaultMessage(ImRealtimeBus.TOPIC.getBytes(), envelope.getBytes()), null);
        String expected = ImRealtimeBus.eventJson(9, "reaction.updated", envelope);
        verify(hub).deliverLocal(9L, "reaction.updated", expected);
        JSONObject event = JSON.parseObject(expected);
        assertEquals("reaction.updated", event.getString("type"));
        assertEquals("9", event.getString("conversation_id"));
        assertEquals("70", event.getString("message_id"));
        bus.onMessage(new DefaultMessage(ImRealtimeBus.TOPIC.getBytes(), "garbage".getBytes()), null);
        verify(hub, never()).deliverLocal(eq(0L), anyString(), anyString());
    }
}
