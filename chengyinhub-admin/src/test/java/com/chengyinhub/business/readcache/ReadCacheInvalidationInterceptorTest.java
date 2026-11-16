package com.chengyinhub.business.readcache;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which writes invalidate which cache namespace (T2 maps-backend). */
class ReadCacheInvalidationInterceptorTest {

    @Test
    void targetTableDecidesTheNamespace() {
        assertEquals("poi", ReadCacheInvalidationInterceptor.namespaceOf("update roam_poi set status = 0 where id = ?"));
        assertEquals("poi", ReadCacheInvalidationInterceptor.namespaceOf("\n  INSERT INTO `roam_poi` (id) values (?)"));
        assertEquals("poi", ReadCacheInvalidationInterceptor.namespaceOf("insert ignore into roam_poi(id) values (?)"));
        assertEquals("poi", ReadCacheInvalidationInterceptor.namespaceOf("delete from cy.roam_poi where id = ?"));
        assertEquals("poi", ReadCacheInvalidationInterceptor.namespaceOf("UPDATE npc_profile SET del_flag = '2'"));
        assertEquals("features", ReadCacheInvalidationInterceptor.namespaceOf("update sys_config set config_value = ?"));
        assertEquals("banner", ReadCacheInvalidationInterceptor.namespaceOf("replace into sys_banner values (?)"));
        assertEquals("category", ReadCacheInvalidationInterceptor.namespaceOf("/* hint */ delete low_priority from sys_category where id = ?"));
        // Member and other tables never touch the shared cache.
        assertNull(ReadCacheInvalidationInterceptor.namespaceOf("insert into roam_poi_found (poi_id) select id from roam_poi"));
        assertNull(ReadCacheInvalidationInterceptor.namespaceOf("update roam_poi_reward_log set status = 1"));
        assertNull(ReadCacheInvalidationInterceptor.namespaceOf("insert into sys_config_log values (?)"));
        assertNull(ReadCacheInvalidationInterceptor.namespaceOf("select * from roam_poi"));
        assertNull(ReadCacheInvalidationInterceptor.namespaceOf(null));
    }

    /** Every write statement in the mappers that own the cached tables is recognised. */
    @Test
    void everyWriteInTheOwningMappersIsRecognised() throws Exception {
        Map<String, String> owners = Map.of(
                "mapper/system/SysBannerMapper.xml", "banner",
                "mapper/system/SysCategoryMapper.xml", "category",
                "mapper/system/SysConfigMapper.xml", "features",
                "mapper/business/NpcProfileMapper.xml", "poi");
        Pattern write = Pattern.compile("<(insert|update|delete)\\b[^>]*>([^<]*)", Pattern.CASE_INSENSITIVE);
        for (Map.Entry<String, String> owner : owners.entrySet()) {
            int seen = 0;
            for (Matcher m = write.matcher(read(owner.getKey())); m.find(); ) {
                assertEquals(owner.getValue(), ReadCacheInvalidationInterceptor.namespaceOf(m.group(2)),
                        owner.getKey() + ": " + m.group(2).trim());
                seen++;
            }
            assertTrue(seen >= 3, owner.getKey());
        }
        // RoamMapper and the seed import write roam_poi among other tables: each roam_poi write is recognised.
        Pattern poiWrite = Pattern.compile("<(?:insert|update|delete)\\b[^>]*>(\\s*(?:insert\\s+(?:ignore\\s+)?into|update|delete\\s+from)\\s+`?roam_poi`?[\\s(][^<]*)",
                Pattern.CASE_INSENSITIVE);
        for (String mapper : new String[]{"mapper/business/RoamMapper.xml", "mapper/business/SeedContentMapper.xml"}) {
            int seen = 0;
            for (Matcher m = poiWrite.matcher(read(mapper)); m.find(); ) {
                assertEquals("poi", ReadCacheInvalidationInterceptor.namespaceOf(m.group(1)), mapper + ": " + m.group(1).trim());
                seen++;
            }
            assertTrue(seen >= 1, mapper);
        }
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = ReadCacheInvalidationInterceptorTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
