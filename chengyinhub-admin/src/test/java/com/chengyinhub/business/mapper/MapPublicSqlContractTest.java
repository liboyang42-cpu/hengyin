package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.map.MapPublicLimits;
import com.chengyinhub.business.service.impl.ApiMapServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPublicSqlContractTest {

    @Test
    void roamPredicateMatchesPlayerQueryAndDoesNotAbsorbMerchantGates() throws Exception {
        String roam = read("chengyinhub-system/src/main/resources/mapper/business/RoamMapper.xml");
        String map = read("chengyinhub-system/src/main/resources/mapper/business/MapPublicMapper.xml");
        String approved = between(roam, "<sql id=\"cityNodeContentApproved\">", "</sql>");
        String select = between(map, "<select id=\"selectRoamPois\"", "</select>");
        assertTrue(compact(select).contains(compact(approved)));
        assertTrue(select.contains("p.status = 1"));
        assertTrue(select.contains("f.member_id = #{viewerId}"));
        assertTrue(select.contains("r.status = 0 and r.coupon_id &gt; 0"));
        assertTrue(select.contains("cast(p.visibility_version as char) as visibilityVersion"));
        assertTrue(select.contains("order by p.id asc"));
        assertFalse(select.toLowerCase(Locale.ROOT).contains("ifnull"));
        String where = select.substring(select.indexOf("where p.status"));
        assertFalse(where.contains("mms_merchant"));
        assertFalse(where.contains("account_status"));
        assertFalse(where.contains("del_flag"));
        assertFalse(select.contains("selectPoisByIds"));
        assertTrue(map.contains("visibility-watermark: " + MapPublicLimits.WATERMARK));
    }

    @Test
    void storeAndActivityReusePlayerPublicGatesAndStayReadOnly() throws Exception {
        String map = read("chengyinhub-system/src/main/resources/mapper/business/MapPublicMapper.xml");
        String store = between(map, "<select id=\"selectStores\"", "</select>");
        assertTrue(store.contains("status = 1 and account_status = 1 and del_flag = 0"));
        assertFalse(store.contains("phone"));
        assertFalse(store.contains("wechat"));
        assertFalse(store.contains("business_license"));
        assertFalse(store.contains("business_status ="));
        assertTrue(store.contains("date_format(update_time, '%Y-%m-%dT%H:%i:%s') as visibilityVersion"));
        assertTrue(store.contains("location_verified as locationVerified"));
        assertFalse(store.contains("location_verified ="));

        String activity = between(map, "<select id=\"selectActivities\"", "</select>");
        assertTrue(activity.contains("status = 1 and publish_status = 1 and del_flag = 0 and cancel_time is null"));
        assertTrue(activity.contains("live_parent.status = 1 and live_parent.user_status = 1"));
        assertTrue(activity.contains("and (club_id is null or club_id = 0)"));
        assertTrue(activity.contains("end_date &gt; now()"));
        assertTrue(activity.contains("date_format(update_time, '%Y-%m-%dT%H:%i:%s') as visibilityVersion"));
        assertTrue(activity.contains("start_date as startDate"));
        assertFalse(activity.contains("IFNULL"));
        assertFalse(activity.contains("correct_answer"));

        String lower = map.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("<insert"));
        assertFalse(lower.contains("<update"));
        assertFalse(lower.contains("<delete"));
        assertFalse(map.contains("sideQuest"));
        assertFalse(map.contains("visibilityVersion = 1"));
        String candidate = read("docs/w13/map-public-visibility-version.sql");
        assertTrue(candidate.contains("CANDIDATE ONLY"));
        assertTrue(candidate.contains("visibility_version"));
        assertFalse(candidate.contains("DEFAULT 1"));
        assertFalse(candidate.contains("DEFAULT '1'"));
        for (Method method : MapPublicMapper.class.getMethods()) {
            if (method.getDeclaringClass() == MapPublicMapper.class) {
                assertTrue(method.getName().startsWith("select"));
            }
        }
    }

    @Test
    void legacyNearbyContractIsUnchanged() throws Exception {
        Method nearby = ApiMapMapper.class.getMethod("selectNearbyNodes", double.class, double.class,
                double.class, int.class, Integer.class, Integer.class);
        assertEquals(java.util.List.class, nearby.getReturnType());
        Method service = ApiMapServiceImpl.class.getMethod("nearbyNodes", double.class, double.class,
                double.class, int.class, Integer.class, Integer.class);
        assertEquals(java.util.List.class, service.getReturnType());
        Method roam = RoamMapper.class.getMethod("selectPoisNear", double.class, double.class,
                double.class, double.class, Long.class);
        assertEquals(java.util.List.class, roam.getReturnType());

        String xml = read("chengyinhub-system/src/main/resources/mapper/business/ApiMapMapper.xml");
        String select = between(xml, "<select id=\"selectNearbyNodes\"", "</select>");
        assertTrue(select.contains("del_flag = '0'"));
        assertTrue(select.contains("<if test=\"status != null\"> and status = #{status}</if>"));
        assertTrue(select.contains("<if test=\"auditStatus != null\"> and audit_status = #{auditStatus}</if>"));
        String filter = select.substring(select.indexOf("from cms_registration_merchant"));
        assertFalse(filter.contains("status = 1"));
        assertFalse(filter.contains("audit_status = 1"));
        String impl = read("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/ApiMapServiceImpl.java");
        assertTrue(impl.contains("selectNearbyNodes(lng, lat, radius, limit, status, auditStatus)"));
        String security = read("chengyinhub-framework/src/main/java/com/chengyinhub/framework/config/SecurityConfig.java");
        assertFalse(security.contains("public-layers"));
        assertTrue(Arrays.stream(security.split("\n")).anyMatch(line -> line.contains(".anyRequest().authenticated()")));
    }

    private static String read(String relative) throws Exception {
        Path current = Path.of(System.getProperty("user.dir"));
        Path root = Files.exists(current.resolve("chengyinhub-system")) ? current : current.getParent();
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from);
        assertTrue(from >= 0 && to > from, start);
        return text.substring(from + start.length(), to);
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
