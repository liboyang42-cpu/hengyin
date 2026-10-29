package com.chengyinhub.business.service.impl;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** The existing map-result byte/LRU/monotonic-TTL cache, shared by geometry and city responses.
 * Accounting is key/value UTF-16 upper-bound bytes plus 256 bytes per item, not measured heap.
 * No service, worker, authorization or runtime callback; only complete successful values are supplied.
 */
final class MapResponseCache
{
    static final long MAX_CACHE_BYTES = 8L * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 256L * 1024;
    static final long MAX_TTL_SECONDS = 300;
    private static final long ENTRY_ALLOWANCE_BYTES = 256;

    private final long maxBytes;
    private final long maxEntryBytes;
    private final long maxEntries;
    private final long ttlNanos;
    private final LongSupplier ticker;
    private final LinkedHashMap<String, CacheEntry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long retainedBytes;

    MapResponseCache(long maxBytes, long maxEntryBytes, long ttlNanos, LongSupplier ticker)
    {
        if (maxBytes < 0 || maxBytes > MAX_CACHE_BYTES || maxEntryBytes <= 0 || maxEntryBytes > MAX_ENTRY_BYTES
                || ttlNanos <= 0 || ttlNanos > TimeUnit.SECONDS.toNanos(MAX_TTL_SECONDS))
        {
            throw new IllegalArgumentException("Invalid route cache limits");
        }
        this.maxBytes = maxBytes;
        this.maxEntryBytes = Math.min(maxEntryBytes, maxBytes);
        this.maxEntries = Math.min(Integer.MAX_VALUE, maxBytes / ENTRY_ALLOWANCE_BYTES);
        this.ttlNanos = ttlNanos;
        this.ticker = ticker;
    }

    synchronized String get(String key)
    {
        CacheEntry entry = entries.get(key);
        if (entry == null)
        {
            return null;
        }
        // 单调时钟的差值同时支持 nanoTime 的有符号回绕。
        if (ticker.getAsLong() - entry.writtenAt >= ttlNanos)
        {
            entries.remove(key);
            retainedBytes -= entry.weight;
            return null;
        }
        return entry.json;
    }

    synchronized void put(String key, String json)
    {
        long weight = weight(key, json);
        if (weight > maxEntryBytes)
        {
            // 超大结果照常返回给当前请求，但不留存，也不淘汰已有正常项。
            return;
        }
        CacheEntry old = entries.remove(key);
        if (old != null)
        {
            retainedBytes -= old.weight;
        }
        Iterator<Map.Entry<String, CacheEntry>> oldest = entries.entrySet().iterator();
        while (entries.size() >= maxEntries || retainedBytes > maxBytes - weight)
        {
            CacheEntry evicted = oldest.next().getValue();
            retainedBytes -= evicted.weight;
            oldest.remove();
        }
        entries.put(key, new CacheEntry(json, weight, ticker.getAsLong()));
        retainedBytes += weight;
    }

    boolean accepts(String key, String json)
    {
        return weight(key, json) <= maxEntryBytes;
    }

    private static long weight(String key, String json)
    {
        return ENTRY_ALLOWANCE_BYTES + 2L * ((long) key.length() + json.length());
    }

    private static final class CacheEntry
    {
        private final String json;
        private final long weight;
        private final long writtenAt;

        private CacheEntry(String json, long weight, long writtenAt)
        {
            this.json = json;
            this.weight = weight;
            this.writtenAt = writtenAt;
        }
    }
}
