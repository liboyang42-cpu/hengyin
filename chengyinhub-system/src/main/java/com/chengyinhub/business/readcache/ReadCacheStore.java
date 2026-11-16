package com.chengyinhub.business.readcache;

import java.util.List;

/** The few key-value operations the read cache needs. Production: {@link RedisReadCacheStore}. */
public interface ReadCacheStore {
    String get(String key);

    /** Values in key order; a missing key gives {@code null} at its position. */
    List<String> multiGet(List<String> keys);

    void set(String key, String value, int ttlSeconds);

    /** Atomically adds one and returns the new value. */
    long increment(String key);
}
