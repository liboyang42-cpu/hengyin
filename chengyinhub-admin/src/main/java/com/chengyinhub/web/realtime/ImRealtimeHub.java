package com.chengyinhub.web.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * W5 C2：本节点的 SSE 订阅表（会话 id → 打开中的流）。
 *
 * <ul>
 *   <li>每条流 5 分钟超时，客户端到点重连（重连时重新过读权限闸）；每 25 秒发一行注释心跳，防代理断开空闲连接。</li>
 *   <li>上限：每人 {@code perMember} 条、全节点 {@code maxStreams} 条；超出返回“请用轮询”。</li>
 *   <li>心跳是本节点自己的连接保活（不是业务定时任务），所以不走调度主节点：每个节点只照看自己的连接。</li>
 * </ul>
 */
@Component
public class ImRealtimeHub
{
    private static final Logger log = LoggerFactory.getLogger(ImRealtimeHub.class);
    static final long STREAM_TIMEOUT_MS = 5 * 60 * 1000L;
    static final long HEARTBEAT_SECONDS = 25;

    record Subscriber(long memberId, long conversationId, SseEmitter emitter) { }

    private final Map<Long, Set<Subscriber>> byConversation = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> perMemberCount = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final int perMember;
    private final int maxStreams;
    private volatile ScheduledExecutorService heartbeat;

    public ImRealtimeHub(@Value("${chengyin.im.realtime.per-member:6}") int perMember,
            @Value("${chengyin.im.realtime.max-streams:5000}") int maxStreams)
    {
        this.perMember = perMember;
        this.maxStreams = maxStreams;
    }

    /** 返回 null 表示达到上限（调用方回“请用轮询”）。 */
    public SseEmitter open(long memberId, long conversationId)
    {
        AtomicInteger mine = perMemberCount.computeIfAbsent(memberId, k -> new AtomicInteger());
        if (mine.incrementAndGet() > perMember) { mine.decrementAndGet(); return null; }
        if (total.incrementAndGet() > maxStreams) { total.decrementAndGet(); mine.decrementAndGet(); return null; }
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Subscriber s = new Subscriber(memberId, conversationId, emitter);
        byConversation.computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet()).add(s);
        Runnable cleanup = () -> remove(s);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        ensureHeartbeat();
        return emitter;
    }

    /** 把一条 JSON 事件投给本节点订阅了该会话的流。 */
    public int deliverLocal(long conversationId, String type, String json)
    {
        Set<Subscriber> subs = byConversation.get(conversationId);
        if (subs == null || subs.isEmpty()) return 0;
        int sent = 0;
        for (Subscriber s : subs)
        {
            try
            {
                s.emitter().send(SseEmitter.event().name(type).data(json, MediaType.APPLICATION_JSON));
                sent++;
            }
            catch (IOException | IllegalStateException gone)
            {
                remove(s);
                try { s.emitter().complete(); } catch (RuntimeException ignored) { }
            }
        }
        return sent;
    }

    public int openStreams() { return total.get(); }

    public boolean hasSubscribers(long conversationId)
    {
        Set<Subscriber> subs = byConversation.get(conversationId);
        return subs != null && !subs.isEmpty();
    }

    void heartbeatOnce()
    {
        for (Set<Subscriber> subs : byConversation.values())
        {
            for (Subscriber s : subs)
            {
                try { s.emitter().send(SseEmitter.event().comment("hb")); }
                catch (IOException | IllegalStateException gone)
                {
                    remove(s);
                    try { s.emitter().complete(); } catch (RuntimeException ignored) { }
                }
            }
        }
    }

    private void remove(Subscriber s)
    {
        Set<Subscriber> subs = byConversation.get(s.conversationId());
        if (subs != null && subs.remove(s))
        {
            total.decrementAndGet();
            AtomicInteger mine = perMemberCount.get(s.memberId());
            if (mine != null && mine.decrementAndGet() <= 0) perMemberCount.remove(s.memberId(), mine);
            if (subs.isEmpty()) byConversation.remove(s.conversationId(), subs);
        }
    }

    private void ensureHeartbeat()
    {
        if (heartbeat != null) return;
        synchronized (this)
        {
            if (heartbeat != null) return;
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "im-realtime-heartbeat");
                t.setDaemon(true);
                return t;
            });
            ex.scheduleAtFixedRate(() -> {
                try { heartbeatOnce(); }
                catch (RuntimeException e) { log.warn("IM 实时心跳异常：{}", e.toString()); }
            }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
            heartbeat = ex;
        }
    }

    @PreDestroy
    void shutdown()
    {
        ScheduledExecutorService ex = heartbeat;
        if (ex != null) ex.shutdownNow();
        for (Set<Subscriber> subs : byConversation.values())
            for (Subscriber s : subs) try { s.emitter().complete(); } catch (RuntimeException ignored) { }
        byConversation.clear();
    }
}
