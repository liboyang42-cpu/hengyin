package com.chengyinhub.business.readcache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test stand-in for Redis: same contract as {@link RedisReadCacheStore}; TTL recorded, not enforced. */
public class InMemoryReadCacheStore implements ReadCacheStore {
    public final Map<String, String> values = new ConcurrentHashMap<>();
    final Map<String, Integer> ttl = new ConcurrentHashMap<>();
    public final AtomicInteger calls = new AtomicInteger();
    public volatile boolean down;

    private void touch() {
        calls.incrementAndGet();
        if (down) throw new IllegalStateException("redis down (test)");
    }

    @Override
    public String get(String key) {
        touch();
        return values.get(key);
    }

    @Override
    public List<String> multiGet(List<String> keys) {
        touch();
        List<String> out = new ArrayList<>();
        for (String k : keys) out.add(values.get(k));
        return out;
    }

    @Override
    public void set(String key, String value, int ttlSeconds) {
        touch();
        values.put(key, value);
        ttl.put(key, ttlSeconds);
    }

    @Override
    public long increment(String key) {
        touch();
        long next = Long.parseLong(values.getOrDefault(key, "0")) + 1;
        values.put(key, Long.toString(next));
        return next;
    }

    public long entries(String namespace) {
        return values.keySet().stream().filter(k -> k.startsWith(ReadCache.PREFIX + namespace + ":")).count();
    }
}
