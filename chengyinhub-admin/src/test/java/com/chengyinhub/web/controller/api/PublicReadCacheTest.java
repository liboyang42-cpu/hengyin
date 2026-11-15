package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.readcache.InMemoryReadCacheStore;
import com.chengyinhub.business.readcache.ReadCache;
import com.chengyinhub.business.readcache.ReadCacheNamespace;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.system.domain.SysBanner;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysBannerService;
import com.chengyinhub.system.service.ISysCategoryService;
import com.chengyinhub.system.service.ISysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T2 maps-backend (BE-18): the cached public reads answer byte for byte like the uncached ones, re-read after a write
 * to their source table, keep per-viewer keys per viewer, and stop caching when switched off.
 */
class PublicReadCacheTest {
    /** Same settings as the HTTP converter (ApplicationConfig: Asia/Shanghai; Boot: ISO dates, not timestamps). */
    private final ObjectMapper http = new Jackson2ObjectMapperBuilder()
            .timeZone(TimeZone.getTimeZone("Asia/Shanghai"))
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final ObjectMapper plain = new ObjectMapper();
    private InMemoryReadCacheStore store;
    private ReadCache cache;

    @BeforeEach
    void setUp() {
        store = new InMemoryReadCacheStore();
        cache = new ReadCache(store, new ReadCache.Settings(true, Map.of()));
    }

    private void same(AjaxResult expected, AjaxResult actual) throws Exception {
        assertEquals(http.writeValueAsString(expected), http.writeValueAsString(actual));
        assertEquals(plain.writeValueAsString(expected), plain.writeValueAsString(actual));
    }

    // ---------- /api/config/features ----------

    private ApiConfigController features(ISysConfigService config, CommunityPostFeatureGate gate, ReadCache readCache) {
        NpcFeatureFlags flags = new NpcFeatureFlags();
        ReflectionTestUtils.setField(flags, "configService", config);
        ApiConfigController c = new ApiConfigController();
        ReflectionTestUtils.setField(c, "configService", config);
        ReflectionTestUtils.setField(c, "npcFeatureFlags", flags);
        ReflectionTestUtils.setField(c, "communityPostFeatureGate", gate);
        SpeechToTextService stt = mock(SpeechToTextService.class);
        when(stt.available()).thenReturn(true);
        ReflectionTestUtils.setField(c, "speechToTextService", stt);
        ReflectionTestUtils.setField(c, "readCache", readCache);
        return c;
    }

    @Test
    void featuresCachedAnswerIsIdenticalAndKeepsViewerKeysLive() throws Exception {
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey(anyString())).thenReturn("");
        when(config.selectConfigByKey("feature.flag.finishNearbyRoute")).thenReturn("true");
        when(config.selectConfigByKey("feature.flag.shopNpcChat")).thenReturn("1");
        when(config.selectConfigByKey("aigc.algorithm.filing-no")).thenReturn(" 浙网信算备-1 ");
        when(config.selectConfigByKey("app.support.contact")).thenReturn("help@example.invalid");
        CommunityPostFeatureGate gate = mock(CommunityPostFeatureGate.class);
        when(gate.canRead(anyLong())).thenReturn(true);
        when(gate.canWrite(anyLong())).thenReturn(false);

        AjaxResult uncached = features(config, gate, null).features();
        ApiConfigController cached = features(config, gate, cache);
        AjaxResult miss = cached.features();
        AjaxResult hit = cached.features();
        same(uncached, miss);
        same(uncached, hit);
        assertEquals(1, store.entries(ReadCacheNamespace.FEATURES));
        verify(config, times(2)).selectConfigByKey("feature.flag.finishNearbyRoute"); // uncached + the miss; the hit reads nothing
        assertTrue(cache.hits() >= 1);

        // Per-viewer keys are never served from the cache.
        when(gate.canWrite(anyLong())).thenReturn(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) cached.features().get(AjaxResult.DATA_TAG);
        assertEquals(true, data.get("communityPostWrite"));
        String json = String.join("\n", store.values.values());
        assertTrue(!json.contains("communityPost"), "viewer keys must not be stored");

        // A sys_config write (any mapper) moves the namespace on; the next answer reads the new value.
        when(config.selectConfigByKey("feature.flag.finishNearbyRoute")).thenReturn("false");
        cache.invalidate(ReadCacheNamespace.FEATURES);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) cached.features().get(AjaxResult.DATA_TAG);
        assertEquals(false, after.get("finishNearbyRoute"));
        same(features(config, gate, null).features(), cached.features());
    }

    // ---------- /api/common/banner ----------

    private static SysBanner banner(int id, int showType) {
        SysBanner b = new SysBanner();
        b.setId(id);
        b.setShowType(showType);
        b.setLinkType(id % 2);
        b.setPicUrl("/images/banner" + id + ".jpg");
        b.setContents("内容 " + id);
        b.setSortId(id);
        b.setStatus(1);
        b.setDataId("topic:" + id);
        b.setCreateBy("ops");
        b.setCreateTime(new Date(1_763_000_000_123L + id));
        b.setUpdateTime(new Date(1_763_100_000_000L));
        b.setRemark("备注");
        return b;
    }

    @Test
    void bannerCachedAnswerIsIdenticalPerFilterAndRereadsAfterWrite() throws Exception {
        ISysBannerService service = mock(ISysBannerService.class);
        List<SysBanner> rows = new ArrayList<>(List.of(banner(1, 1), banner(2, 1)));
        when(service.selectSysBannerList(any())).thenAnswer(inv -> {
            SysBanner f = inv.getArgument(0);
            List<SysBanner> out = new ArrayList<>();
            for (SysBanner r : rows) {
                if (r.getShowType().equals(f.getShowType()) && (f.getLinkType() == null || f.getLinkType().equals(r.getLinkType()))) {
                    SysBanner copy = banner(r.getId(), r.getShowType());
                    copy.setContents(r.getContents());
                    out.add(copy);
                }
            }
            return out;
        });
        ApiCommonController uncached = new ApiCommonController();
        ReflectionTestUtils.setField(uncached, "sysBannerService", service);
        ApiCommonController cached = new ApiCommonController();
        ReflectionTestUtils.setField(cached, "sysBannerService", service);
        ReflectionTestUtils.setField(cached, "readCache", cache);

        for (String[] args : new String[][]{{"1", null}, {"1", "1"}, {null, null}, {"2", null}}) {
            Integer link = args[1] == null ? null : Integer.valueOf(args[1]);
            AjaxResult expected = uncached.getBanner(args[0], link);
            same(expected, cached.getBanner(args[0], link));
            same(expected, cached.getBanner(args[0], link));
        }
        assertEquals(3, store.entries(ReadCacheNamespace.BANNER), "showType 1 (twice: link filter or not) and 2; null = 1");

        rows.get(0).setContents("新内容");
        cache.invalidate(ReadCacheNamespace.BANNER);
        same(uncached.getBanner("1", null), cached.getBanner("1", null));
        @SuppressWarnings("unchecked")
        List<SysBanner> list = (List<SysBanner>) cached.getBanner("1", null).get(AjaxResult.DATA_TAG);
        assertEquals("新内容", list.get(0).getContents());
    }

    // ---------- /api/category/list ----------

    @Test
    void categoryCachedAnswerIsIdenticalAndKeywordSearchesAreNotCached() throws Exception {
        ISysCategoryService service = mock(ISysCategoryService.class);
        when(service.selectSysCategoryList(any())).thenAnswer(inv -> {
            SysCategory f = inv.getArgument(0);
            SysCategory c = new SysCategory();
            c.setId(10L + (f.getType() == null ? 0 : f.getType()) + f.getParentId());
            c.setType(f.getType());
            c.setParentId(f.getParentId());
            c.setCategoryName("分类" + f.getType() + "/" + f.getCategoryName());
            c.setIcon("/images/c.png");
            c.setStatus(1);
            c.setDelFlag(0);
            c.setSortId(3L);
            c.setCreateTime(new Date(1_763_000_000_000L));
            c.setRemark("内部");
            return new ArrayList<>(List.of(c));
        });
        ApiCategoryController uncached = new ApiCategoryController();
        ReflectionTestUtils.setField(uncached, "sysCategoryService", service);
        ApiCategoryController cached = new ApiCategoryController();
        ReflectionTestUtils.setField(cached, "sysCategoryService", service);
        ReflectionTestUtils.setField(cached, "readCache", cache);

        for (String[] a : new String[][]{{"1", "0"}, {"MERCHANT", null}, {" merchant ", "0"}, {null, "0"}, {"2", "5"}, {"x", "0"}}) {
            AjaxResult expected = uncached.categoryList(a[0], a[1], null);
            same(expected, cached.categoryList(a[0], a[1], null));
            same(expected, cached.categoryList(a[0], a[1], ""));
        }
        long entries = store.entries(ReadCacheNamespace.CATEGORY);
        same(uncached.categoryList("1", "0", "展"), cached.categoryList("1", "0", "展"));
        assertEquals(entries, store.entries(ReadCacheNamespace.CATEGORY), "keyword searches read MySQL only");
        String before = String.join("", store.values.values());
        cache.invalidate(ReadCacheNamespace.CATEGORY);
        cached.categoryList("1", "0", null);
        assertNotEquals(before, String.join("", store.values.values()));
    }

    @Test
    void switchedOffNeverTouchesRedis() throws Exception {
        ReadCache off = new ReadCache(store, new ReadCache.Settings(false, Map.of()));
        ISysCategoryService service = mock(ISysCategoryService.class);
        when(service.selectSysCategoryList(any())).thenReturn(new ArrayList<>());
        ApiCategoryController c = new ApiCategoryController();
        ReflectionTestUtils.setField(c, "sysCategoryService", service);
        ReflectionTestUtils.setField(c, "readCache", off);
        c.categoryList("1", "0", null);
        c.categoryList("1", "0", null);
        verify(service, times(2)).selectSysCategoryList(any());
        assertEquals(0, store.calls.get());
    }
}
