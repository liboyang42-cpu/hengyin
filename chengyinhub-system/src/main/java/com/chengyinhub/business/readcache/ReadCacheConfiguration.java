package com.chengyinhub.business.readcache;

import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

/** Wires the read cache (T2 maps-backend). Settings live under {@code chengyin.read-cache.*} in application.yml. */
@Configuration
public class ReadCacheConfiguration {

    @Bean
    public ReadCache readCache(ObjectProvider<StringRedisTemplate> redis,
                               @Value("${chengyin.read-cache.enabled:true}") boolean enabled,
                               @Value("${chengyin.read-cache.ttl.poi-seconds:60}") int poiTtl,
                               @Value("${chengyin.read-cache.ttl.features-seconds:30}") int featuresTtl,
                               @Value("${chengyin.read-cache.ttl.banner-seconds:300}") int bannerTtl,
                               @Value("${chengyin.read-cache.ttl.category-seconds:300}") int categoryTtl) {
        StringRedisTemplate template = redis.getIfAvailable();
        return new ReadCache(template == null ? null : new RedisReadCacheStore(template),
                new ReadCache.Settings(enabled, Map.of(
                        ReadCacheNamespace.POI, poiTtl,
                        ReadCacheNamespace.FEATURES, featuresTtl,
                        ReadCacheNamespace.BANNER, bannerTtl,
                        ReadCacheNamespace.CATEGORY, categoryTtl)));
    }

    /** Tile cache for the unfiltered /api/roam/pois read. */
    @Bean
    public MapPoiTileCache mapPoiTileCache(com.chengyinhub.business.mapper.RoamMapper roamMapper, ReadCache readCache,
                                           @Value("${chengyin.read-cache.poi.max-tiles:64}") int maxTiles,
                                           @Value("${chengyin.read-cache.poi.tile-cap:3000}") int tileCap) {
        return new MapPoiTileCache(roamMapper, readCache, maxTiles, tileCap);
    }

    /** Adds the invalidation hook to every MyBatis session factory once all singletons exist (as PageHelper does). */
    @Bean
    public SmartInitializingSingleton readCacheInvalidationRegistrar(ObjectProvider<SqlSessionFactory> factories,
                                                                     ObjectProvider<ReadCache> cache) {
        return () -> {
            ReadCache readCache = cache.getIfAvailable();
            if (readCache == null) return;
            factories.orderedStream().forEach(factory -> {
                for (Interceptor existing : factory.getConfiguration().getInterceptors()) {
                    if (existing instanceof ReadCacheInvalidationInterceptor) return;
                }
                factory.getConfiguration().addInterceptor(new ReadCacheInvalidationInterceptor(readCache));
            });
        };
    }
}
