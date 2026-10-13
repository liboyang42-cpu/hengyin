package com.chengyinhub.business.service;

import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscoveryFilterContractTest {
    @Test
    void activityAndTopicExposeSeparateFrozenSchemasWithServerTotal() {
        Map<String, Object> activity = DiscoveryFilterContract.schema("activity");
        Map<String, Object> topic = DiscoveryFilterContract.schema("topic");

        assertEquals("data.total", activity.get("totalField"), "分页总数必须由服务端响应提供");
        assertEquals("ACTIVITY", activity.get("resource"), "活动筛选不得套用主题 schema");
        assertEquals("TOPIC", topic.get("resource"), "主题筛选不得套用活动 schema");
        @SuppressWarnings("unchecked")
        Map<String, Object> activityFields = (Map<String, Object>) activity.get("fields");
        assertNotNull(activityFields.get("city_keyword"), "城市筛选必须进入冻结 schema");
        assertNotNull(activityFields.get("distance_meters"), "距离口径必须显式冻结，不能藏在 SQL 常量里");
    }

    @Test
    void unknownEnumReturnsSupportedValuesAndNearestRequiresCoordinates() {
        ServiceException invalid = assertThrows(ServiceException.class,
                () -> DiscoveryFilterContract.schedule("PAST"));
        ServiceException noLocation = assertThrows(ServiceException.class,
                () -> DiscoveryFilterContract.activitySort("NEAREST", null, null));

        assertEquals(Integer.valueOf(400), invalid.getCode(), "失效枚举必须是可辨别的参数错误");
        assertEquals("schedule 枚举无效，支持值: [ONGOING, UPCOMING]", invalid.getMessage());
        assertEquals("sort=NEAREST 必须同时提供 longitude、latitude；无定位权限请降级 LATEST",
                noLocation.getMessage(), "定位权限拒绝时必须给出明确降级口径");
    }

    @Test
    void invalidCategoryAndRadiusReturnDistinguishableParameterErrors() {
        ServiceException category = assertThrows(ServiceException.class,
                () -> DiscoveryFilterContract.categoryId("not-a-number"));
        ServiceException radius = assertThrows(ServiceException.class,
                () -> DiscoveryFilterContract.distanceMeters("5000", "121.4", "31.2"));

        assertEquals(Integer.valueOf(400), category.getCode(), "非法分类不能静默变成空列表");
        assertEquals("category_id 必须为正整数", category.getMessage(), "分类错误必须指认具体字段");
        assertEquals(Integer.valueOf(400), radius.getCode(), "未冻结的距离不能进入查询");
        assertEquals("distance_meters 枚举无效，支持值: [100000]", radius.getMessage(),
                "距离错误必须返回冻结值");
    }

    @Test
    void invalidCoordinatesAreRejectedBeforeTheyReachSpatialSql() {
        ServiceException invalid = assertThrows(ServiceException.class,
                () -> DiscoveryFilterContract.activitySort("NEAREST", "not-a-number", "91"));

        assertEquals(Integer.valueOf(400), invalid.getCode(), "非法坐标不能变成数据库异常");
        assertEquals("longitude/latitude 必须为合法经纬度", invalid.getMessage(),
                "坐标错误必须在 query contract 层指认");
    }
}
