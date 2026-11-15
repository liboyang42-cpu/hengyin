package com.chengyinhub.business.readcache;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Plain string keys and values on the application's Redis (no Java type information is stored). */
public class RedisReadCacheStore implements ReadCacheStore {
    private final StringRedisTemplate redis;

    public RedisReadCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public List<String> multiGet(List<String> keys) {
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) {
            values = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) values.add(null);
        }
        return values;
    }

    @Override
    public void set(String key, String value, int ttlSeconds) {
        redis.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public long increment(String key) {
        Long value = redis.opsForValue().increment(key);
        return value == null ? 0L : value;
    }
}
