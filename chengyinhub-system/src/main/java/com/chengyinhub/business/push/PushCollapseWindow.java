package com.chengyinhub.business.push;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 突发合并：同一 (会员, thread) 窗口内（默认 30 秒）最多推一条。单机内存实现；
 * 多实例部署时各实例各算一次窗口，最坏多推几条，设备端还会按 apns-collapse-id 只留最新一条。
 */
public class PushCollapseWindow
{
    public static final long DEFAULT_WINDOW_MILLIS = 30_000L;
    private static final int SWEEP_THRESHOLD = 20_000;

    private final ConcurrentHashMap<String, Long> lastSent = new ConcurrentHashMap<>();
    private final long windowMillis;
    private final LongSupplier clock;

    public PushCollapseWindow()
    {
        this(DEFAULT_WINDOW_MILLIS, System::currentTimeMillis);
    }

    public PushCollapseWindow(long windowMillis, LongSupplier clock)
    {
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** true = 本条可推（并占住窗口）；false = 窗口内已推过。 */
    public boolean tryAcquire(long memberId, String threadId)
    {
        long now = clock.getAsLong();
        if (lastSent.size() > SWEEP_THRESHOLD) lastSent.values().removeIf(at -> now - at >= windowMillis);
        String key = memberId + "|" + threadId;
        boolean[] acquired = { false };
        lastSent.compute(key, (k, previous) ->
        {
            if (previous != null && now - previous < windowMillis) return previous;
            acquired[0] = true;
            return now;
        });
        return acquired[0];
    }
}
