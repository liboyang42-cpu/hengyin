package com.chengyinhub.business.readcache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * T2 maps-backend (BE-18): a small read-through cache on Redis for hot, shared, non-personal reads.
 *
 * <ul>
 *   <li>Off switch: {@code chengyin.read-cache.enabled} (env {@code CHENGYIN_READ_CACHE_ENABLED}). Off = every read
 *       goes to MySQL exactly as before.</li>
 *   <li>Keys: {@code cy:rc:<namespace>:<version>:<key>}. A write to a source table moves the namespace version on
 *       after the transaction commits, so a reader never sees an entry older than the last committed write
 *       (an entry loaded during the write lands under the old version and is never read again).</li>
 *   <li>Every entry also has a short TTL, which bounds staleness for writes made outside the application
 *       (SQL scripts, another tool).</li>
 *   <li>Fail-open: any Redis or decoding error reads from MySQL and is counted, never surfaced to the player.</li>
 *   <li>Values are JSON written by a private mapper with annotations off, so the cached object is a field-for-field
 *       copy; the HTTP layer (media projection, date format) runs on it exactly as on a fresh MySQL row.</li>
 * </ul>
 */
public class ReadCache {
    private static final Logger log = LoggerFactory.getLogger(ReadCache.class);
    static final String PREFIX = "cy:rc:";

    /** Settings; TTL in seconds per namespace (missing namespace = 60 s). */
    public record Settings(boolean enabled, Map<String, Integer> ttlSeconds) {
        int ttl(String namespace) {
            Integer t = ttlSeconds == null ? null : ttlSeconds.get(namespace);
            return t == null || t <= 0 ? 60 : Math.min(t, 3600);
        }
    }

    private final ReadCacheStore store;
    private final Settings settings;
    private final ObjectMapper json = JsonMapper.builder()
            .disable(MapperFeature.USE_ANNOTATIONS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    public ReadCache(ReadCacheStore store, Settings settings) {
        this.store = store;
        this.settings = settings;
    }

    public boolean enabled() {
        return settings.enabled() && store != null;
    }

    public int ttl(String namespace) {
        return settings.ttl(namespace);
    }

    public ObjectMapper json() {
        return json;
    }

    public JavaType type(Class<?> raw) {
        return json.getTypeFactory().constructType(raw);
    }

    public JavaType listOf(Class<?> element) {
        return json.getTypeFactory().constructCollectionType(List.class, element);
    }

    /** Cached value of {@code key} in {@code namespace}, or {@code loader}'s value (then stored). */
    public <T> T read(String namespace, String key, JavaType type, Supplier<T> loader) {
        if (!enabled()) return loader.get();
        String entry;
        try {
            entry = entryKey(namespace, version(namespace), key);
            String cached = store.get(entry);
            if (cached != null) {
                T value = json.readValue(cached, type);
                hits.incrementAndGet();
                return value;
            }
        } catch (Exception e) {
            failed("read", namespace, e);
            return loader.get();
        }
        misses.incrementAndGet();
        T value = loader.get();
        put(entry, value, ttl(namespace), namespace);
        return value;
    }

    /** Current version of {@code namespace} ("0" before the first write). Throws when Redis is unreachable. */
    public String version(String namespace) {
        String v = store.get(PREFIX + "v:" + namespace);
        return v == null ? "0" : v;
    }

    public static String entryKey(String namespace, String version, String key) {
        return PREFIX + namespace + ":" + version + ":" + key;
    }

    /** Raw multi-read for callers that assemble one answer from several entries (map tiles). */
    List<String> getMany(List<String> keys) {
        return store.multiGet(keys);
    }

    void put(String entryKey, Object value, int ttlSeconds, String namespace) {
        try {
            store.set(entryKey, value instanceof String s ? s : json.writeValueAsString(value), ttlSeconds);
        } catch (Exception e) {
            failed("write", namespace, e);
        }
    }

    void putRaw(String entryKey, String value, int ttlSeconds, String namespace) {
        put(entryKey, value, ttlSeconds, namespace);
    }

    void hit(int n) { hits.addAndGet(n); }

    void miss(int n) { misses.addAndGet(n); }

    void failed(String what, String namespace, Exception e) {
        long n = errors.incrementAndGet();
        // One line per 100 failures: a Redis outage must not flood the log on every map read.
        if (n == 1 || n % 100 == 0) {
            log.warn("read cache {} failed for {} ({} failures so far), reading MySQL: {}", what, namespace, n, e.toString());
        }
    }

    /** Moves the namespace on now. Prefer {@link #invalidateAfterCommit(String)} from write paths. */
    public void invalidate(String namespace) {
        if (store == null) return;
        try {
            store.increment(PREFIX + "v:" + namespace);
            invalidations.incrementAndGet();
        } catch (Exception e) {
            failed("invalidate", namespace, e);
        }
    }

    /**
     * Moves the namespace on when the surrounding transaction commits (now when there is none). The version moves
     * even while caching is switched off, so turning it back on never serves an entry from before the switch.
     */
    public void invalidateAfterCommit(String namespace) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate(namespace);
            return;
        }
        // Look only at the current transaction's synchronizations: a REQUIRES_NEW inner transaction gets its own,
        // so its writes are invalidated when it commits, not when (or if) the outer one does.
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof PendingInvalidation p && p.owner == this) {
                p.namespaces.add(namespace);
                return;
            }
        }
        PendingInvalidation created = new PendingInvalidation(this);
        created.namespaces.add(namespace);
        TransactionSynchronizationManager.registerSynchronization(created);
    }

    private static final class PendingInvalidation implements TransactionSynchronization {
        private final ReadCache owner;
        private final Set<String> namespaces = new LinkedHashSet<>();

        PendingInvalidation(ReadCache owner) {
            this.owner = owner;
        }

        @Override
        public void afterCommit() {
            for (String ns : namespaces) owner.invalidate(ns);
        }
    }

    public long hits() { return hits.get(); }

    public long misses() { return misses.get(); }

    public long errors() { return errors.get(); }

    public long invalidations() { return invalidations.get(); }
}
