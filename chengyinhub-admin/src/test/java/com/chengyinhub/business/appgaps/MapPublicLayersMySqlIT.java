package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.MapPublicLayerMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 公共图层 SQL 在 TEST 副本真表上的可见性过滤（审核/启用/上架/坐标/视窗）。 */
@EnabledIfEnvironmentVariable(named = "APPGAPS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MapPublicLayersMySqlIT {
    AppGapsMySql my; MapPublicLayers layers;
    static final String TAG = "APPGAPS_MAP_IT";

    @BeforeAll void setup() throws Exception {
        my = new AppGapsMySql();
        layers = new MapPublicLayers(my.mapper(MapPublicLayerMapper.class, "mapper/business/appgaps/MapPublicLayerMapper.xml"));
    }
    @BeforeEach @AfterAll void clean() {
        my.db.update("delete from mms_merchant where name like ?", TAG + "%");
        my.db.update("delete from cms_activity where name like ?", TAG + "%");
    }

    void store(String name, int status, int account, Double lat, Double lng) {
        my.db.update("insert into mms_merchant(name,status,account_status,business_status,del_flag,location_lat,location_lng,create_time,update_time) values(?,?,?,1,0,?,?,now(),now())",
                TAG + name, status, account, lat, lng);
    }
    void activity(String name, int status, int publish, String lat, String lng) {
        my.db.update("insert into cms_activity(name,status,publish_status,del_flag,latitude,longitude,start_date,end_date,create_time,update_time) values(?,?,?,0,?,?,now() - interval 1 hour,now() + interval 1 hour,now(),now())",
                TAG + name, status, publish, lat, lng);
    }

    @SuppressWarnings("unchecked")
    @Test void onlyPublicRowsInsideViewportAreReturned() {
        store("open", 1, 1, 31.2, 121.5); store("pending", 0, 1, 31.2, 121.5); store("disabled", 1, 2, 31.2, 121.5);
        store("outside", 1, 1, 30.0, 121.5); store("nocoord", 1, 1, null, null);
        activity("live", 1, 1, "31.21", "121.51"); activity("offline", 1, 0, "31.21", "121.51"); activity("badcoord", 1, 1, "abc", "121.5");
        Map<String, Object> body = MapPublicLayersTest.body(List.of("store", "activity"), 50);
        List<Map<String, Object>> pages = (List<Map<String, Object>>) layers.read(body, LocalDateTime.now()).get("layers");
        List<String> stores = ((List<Map<String, Object>>) pages.get(0).get("items")).stream().map(i -> (String) i.get("name")).filter(n -> n.startsWith(TAG)).toList();
        List<String> acts = ((List<Map<String, Object>>) pages.get(1).get("items")).stream().map(i -> (String) i.get("name")).filter(n -> n.startsWith(TAG)).toList();
        assertEquals(List.of(TAG + "open"), stores);
        assertEquals(List.of(TAG + "live"), acts);
        Map<String, Object> live = ((List<Map<String, Object>>) pages.get(1).get("items")).stream().filter(i -> (TAG + "live").equals(i.get("name"))).findFirst().orElseThrow();
        assertEquals("running", live.get("visualState"));
    }
}
