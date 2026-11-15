package com.chengyinhub.web.realtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.IImRealtimePublisher;

/**
 * W5 C2：实时提示总线（两节点安全）。
 *
 * <p>业务事务提交后把 {@code {"c":会话,"t":种类,"d":数据}} 发布到 Redis 频道 {@value #TOPIC}；每个节点在本机第一次有人
 * 打开实时流时才开始订阅（没人看就不连 Redis 订阅），收到后投给本机订阅者。发布方若自己还没在订阅（或 Redis 不可用），
 * 就同时直接投本机，保证单节点、无 Redis 时也能用。发布失败只记一次 WARN，不影响写入。
 *
 * <p>开关：{@code chengyin.im.realtime.redis-fanout}（默认 true）。关掉后只投本机，适合单节点。
 */
@Component
public class ImRealtimeBus implements IImRealtimePublisher, MessageListener
{
    private static final Logger log = LoggerFactory.getLogger(ImRealtimeBus.class);
    static final String TOPIC = "cy:im:realtime:v1";

    private final ImRealtimeHub hub;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectProvider<RedisConnectionFactory> connections;
    private final boolean redisFanout;
    private volatile boolean listening;
    private volatile boolean warned;
    private RedisMessageListenerContainer container;

    public ImRealtimeBus(ImRealtimeHub hub, ObjectProvider<StringRedisTemplate> redis,
            ObjectProvider<RedisConnectionFactory> connections,
            @Value("${chengyin.im.realtime.redis-fanout:true}") boolean redisFanout)
    {
        this.hub = hub;
        this.redis = redis;
        this.connections = connections;
        this.redisFanout = redisFanout;
    }

    @Override
    public void publish(long conversationId, String type, Map<String, Object> data)
    {
        if (conversationId <= 0 || type == null) return;
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("c", conversationId);
        envelope.put("t", type);
        envelope.put("d", data == null ? Map.of() : data);
        String payload = JSON.toJSONString(envelope);
        Runnable send = () -> dispatch(conversationId, type, payload);
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit() { send.run(); }
            });
        }
        else send.run();
    }

    void dispatch(long conversationId, String type, String payload)
    {
        boolean published = false;
        if (redisFanout)
        {
            StringRedisTemplate template = redis.getIfAvailable();
            if (template != null)
            {
                try { template.convertAndSend(TOPIC, payload); published = true; }
                catch (RuntimeException e) { warnOnce("发布", e); }
            }
        }
        // 自己在订阅时，Redis 会把消息回投给本机；否则直接投本机。
        if (!published || !listening) hub.deliverLocal(conversationId, type, eventJson(conversationId, type, payload));
    }

    /** 本机有人打开实时流时调用：开始订阅 Redis（只做一次，失败则退回只投本机）。 */
    public void ensureListening()
    {
        if (listening || !redisFanout) return;
        synchronized (this)
        {
            if (listening) return;
            RedisConnectionFactory factory = connections.getIfAvailable();
            if (factory == null) return;
            try
            {
                RedisMessageListenerContainer c = new RedisMessageListenerContainer();
                c.setConnectionFactory(factory);
                c.addMessageListener(this, new ChannelTopic(TOPIC));
                c.afterPropertiesSet();
                c.start();
                container = c;
                listening = true;
            }
            catch (RuntimeException e) { warnOnce("订阅", e); }
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern)
    {
        try
        {
            String payload = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            JSONObject o = JSON.parseObject(payload);
            long conv = o.getLongValue("c");
            String type = o.getString("t");
            if (conv <= 0 || type == null) return;
            hub.deliverLocal(conv, type, eventJson(conv, type, payload));
        }
        catch (RuntimeException e) { warnOnce("接收", e); }
    }

    /** 给客户端的事件体：{"type":..,"conversation_id":"..",...data}。 */
    static String eventJson(long conversationId, String type, String envelope)
    {
        JSONObject o = JSON.parseObject(envelope);
        JSONObject out = new JSONObject();
        out.put("type", type);
        out.put("conversation_id", String.valueOf(conversationId));
        JSONObject d = o.getJSONObject("d");
        if (d != null) out.putAll(d);
        return out.toJSONString();
    }

    public boolean listening() { return listening; }

    private void warnOnce(String what, RuntimeException e)
    {
        if (warned) return;
        warned = true;
        log.warn("IM 实时提示 Redis {}失败，退回只投本节点：{}", what, e.toString());
    }

    @jakarta.annotation.PreDestroy
    void stop()
    {
        RedisMessageListenerContainer c = container;
        if (c != null)
        {
            try { c.stop(); c.destroy(); } catch (Exception ignored) { }
        }
    }
}
