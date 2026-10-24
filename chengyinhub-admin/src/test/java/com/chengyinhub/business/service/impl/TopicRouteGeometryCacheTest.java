package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class TopicRouteGeometryCacheTest
{
    @Test
    void byteBudgetEvictsLeastRecentlyUsedAndCountsKeysToo()
    {
        // 每项 256 + 2*(1+1) = 260 bytes；总预算只容两项。
        MapResponseCache cache = cache(520, 520, new AtomicLong());
        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals("1", cache.get("a"));
        cache.put("c", "3");
        assertNull(cache.get("b"));
        assertEquals("1", cache.get("a"));
        assertEquals("3", cache.get("c"));
        assertBounded(cache, 520);
        assertEquals(520L, ReflectionTestUtils.getField(cache, "retainedBytes"));
    }

    @Test
    void expiryUsesMonotonicWriteTimeAndHitsDoNotExtendTtl()
    {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 50);
        MapResponseCache cache = cache(1024, 1024, now);
        cache.put("a", "route");
        now.addAndGet(99);
        assertEquals("route", cache.get("a"));
        now.incrementAndGet();
        assertNull(cache.get("a"));
        assertEquals(0L, ReflectionTestUtils.getField(cache, "retainedBytes"));
        cache.put("a", "recovered");
        assertEquals("recovered", cache.get("a"));
    }

    @Test
    void oversizedKeyOrGeometryDoesNotEvictUsefulEntries()
    {
        MapResponseCache cache = cache(1024, 300, new AtomicLong());
        cache.put("a", "ok");
        cache.put("b", "x".repeat(23)); // 256+2*(1+23)=304 > 300
        cache.put("k".repeat(23), "x");
        cache.put("a", "x".repeat(23));
        assertEquals("ok", cache.get("a"));
        assertNull(cache.get("b"));
        assertNull(cache.get("k".repeat(23)));
        assertEquals(262L, ReflectionTestUtils.getField(cache, "retainedBytes"));
    }

    @Test
    void replacementReleasesPreviousWeightAndCanUseExactEntryLimit()
    {
        MapResponseCache cache = cache(600, 300, new AtomicLong());
        cache.put("a", "x".repeat(21)); // exactly 300
        assertEquals("x".repeat(21), cache.get("a"));
        cache.put("a", "1");
        cache.put("b", "2");
        assertEquals("1", cache.get("a"));
        assertEquals("2", cache.get("b"));
        assertEquals(520L, ReflectionTestUtils.getField(cache, "retainedBytes"));
    }

    @Test
    void concurrentHitsAndEvictionsNeverExceedBudgetOrMixValues() throws Exception
    {
        MapResponseCache cache = cache(4096, 1024, new AtomicLong());
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++)
            {
                final int index = worker;
                tasks.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int i = 0; i < 200; i++)
                    {
                        String key = "route-" + ((i + index) % 32);
                        cache.put(key, "geometry:" + key);
                        String found = cache.get(key);
                        assertTrue(found == null || found.equals("geometry:" + key));
                        assertBounded(cache, 4096);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        finally
        {
            start.countDown();
            workers.shutdownNow();
        }
        assertBounded(cache, 4096);
    }

    @Test
    void invalidLimitsAreRejectedAndZeroBudgetDisablesRetention()
    {
        MapResponseCache disabled = cache(0, 300, new AtomicLong());
        disabled.put("a", "route");
        assertNull(disabled.get("a"));
        assertBounded(disabled, 0);
        assertThrows(IllegalArgumentException.class, () -> cache(-1, 300, new AtomicLong()));
        assertThrows(IllegalArgumentException.class, () -> cache(1024, 0, new AtomicLong()));
        assertThrows(IllegalArgumentException.class,
                () -> new MapResponseCache(1024, 300, 0, () -> 0L));
    }

    @Test
    void totalBudgetAlsoLimitsSingleEntryEvenWhenEntrySettingIsLarger()
    {
        MapResponseCache cache = cache(300, 4096, new AtomicLong());
        cache.put("a", "x".repeat(23));
        assertNull(cache.get("a"));
        cache.put("a", "ok");
        assertEquals("ok", cache.get("a"));
        assertBounded(cache, 300);
        assertThrows(IllegalArgumentException.class,
                () -> new TopicRouteServiceImpl(true, 1024, 300, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> new TopicRouteServiceImpl(true, -1, 300, 300));
    }

    private MapResponseCache cache(long total, long entry, AtomicLong now)
    {
        return new MapResponseCache(total, entry, 100, now::get);
    }

    private void assertBounded(MapResponseCache cache, long budget)
    {
        synchronized (cache)
        {
            long bytes = (Long) ReflectionTestUtils.getField(cache, "retainedBytes");
            Map<?, ?> entries = (Map<?, ?>) ReflectionTestUtils.getField(cache, "entries");
            assertNotNull(entries);
            assertTrue(bytes >= 0 && bytes <= budget, "retainedBytes=" + bytes);
            assertTrue(entries.size() <= budget / 256, "entry count exceeds derived cap");
            long actual = 0;
            for (Map.Entry<?, ?> entry : entries.entrySet())
            {
                String json = (String) ReflectionTestUtils.getField(entry.getValue(), "json");
                actual += 256L + 2L * (((String) entry.getKey()).length() + json.length());
            }
            assertEquals(actual, bytes, "accounting must match retained keys and values");
        }
    }
}
