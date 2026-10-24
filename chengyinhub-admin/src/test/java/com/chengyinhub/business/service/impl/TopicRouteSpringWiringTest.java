package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSONArray;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.TencentMapClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/** 实际 Spring 构造器/字段装配；provider 受控，不启动 Boot、数据库或真实地图请求。 */
class TopicRouteSpringWiringTest
{
    @Test
    void defaultPropertiesWireProductionServiceAndCacheSuccessfulGeometry()
    {
        try (AnnotationConfigApplicationContext context = context(Map.of()))
        {
            context.refresh();
            TopicRouteServiceImpl service = context.getBean(TopicRouteServiceImpl.class);
            ControlledMapClient provider = context.getBean(ControlledMapClient.class);
            assertEquals(TopicRouteServiceImpl.class, service.getClass());

            String first = service.buildGeometryJson(nodes());
            assertProviderGeometry(first);
            assertEquals(first, service.buildGeometryJson(nodes()));
            assertEquals(1, provider.endpoints.size());
            assertTrue(provider.endpoints.get(0).contains("/walking/?from=30.100000,120.100000&to=30.200000,120.200000"));
        }
    }

    @Test
    void enabledFalsePropertyBypassesRetention()
    {
        assertNotRetained(Map.of("tencent.map.route-cache.enabled", "false"), "/walking/");
    }

    @Test
    void smallTotalBudgetPropertyIsAppliedBySpringConstructorInjection()
    {
        // key + 三点几何 + 元数据的计费超过 300；若误用无参构造器会命中第二次请求。
        assertNotRetained(Map.of("tencent.map.route-cache.max-bytes", "300"), "/walking/");
    }

    @Test
    void smallEntryBudgetAndModePropertiesAreBothApplied()
    {
        assertNotRetained(Map.of("tencent.map.route-cache.max-entry-bytes", "300",
                "tencent.map.mode", "bicycling"), "/bicycling/");
    }

    @Test
    void negativeTotalBudgetFailsContextRefresh()
    {
        assertInvalidConfiguration("tencent.map.route-cache.max-bytes", "-1");
    }

    @Test
    void nonPositiveEntryBudgetFailsContextRefresh()
    {
        assertInvalidConfiguration("tencent.map.route-cache.max-entry-bytes", "0");
    }

    @Test
    void nonPositiveTtlFailsContextRefresh()
    {
        assertInvalidConfiguration("tencent.map.route-cache.ttl-seconds", "0");
    }

    @Test
    void ttlOverflowFailsContextRefresh()
    {
        assertInvalidConfiguration("tencent.map.route-cache.ttl-seconds", Long.toString(Long.MAX_VALUE));
    }

    @Test
    void configuredBudgetsCannotExceedTheProductionCeilings()
    {
        assertInvalidConfiguration("tencent.map.route-cache.max-bytes", Long.toString(8388608L + 1));
        assertInvalidConfiguration("tencent.map.route-cache.max-entry-bytes", Long.toString(262144L + 1));
        assertInvalidConfiguration("tencent.map.route-cache.ttl-seconds", "301");
    }

    private void assertNotRetained(Map<String, String> properties, String expectedMode)
    {
        try (AnnotationConfigApplicationContext context = context(properties))
        {
            context.refresh();
            TopicRouteServiceImpl service = context.getBean(TopicRouteServiceImpl.class);
            ControlledMapClient provider = context.getBean(ControlledMapClient.class);
            String first = service.buildGeometryJson(nodes());
            assertProviderGeometry(first);
            assertEquals(first, service.buildGeometryJson(nodes()));
            assertEquals(2, provider.endpoints.size());
            assertTrue(provider.endpoints.stream().allMatch(endpoint -> endpoint.contains(expectedMode)));
        }
    }

    private void assertInvalidConfiguration(String property, String value)
    {
        try (AnnotationConfigApplicationContext context = context(Map.of(property, value)))
        {
            BeanCreationException error = assertThrows(BeanCreationException.class, context::refresh);
            assertInstanceOf(IllegalArgumentException.class, error.getMostSpecificCause());
            assertFalse(context.isActive());
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, String> properties)
    {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // 缺省用例只依赖生产 @Value 的默认值，隔离宿主机器的同名环境/系统属性。
        context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("route-wiring-test", new HashMap<>(properties)));
        context.register(PropertySourcesPlaceholderConfigurer.class, TopicRouteServiceImpl.class, ControlledMapClient.class);
        return context;
    }

    private List<CmsTopicNode> nodes()
    {
        CmsTopicNode from = new CmsTopicNode();
        from.setLatitude("30.1"); from.setLongitude("120.1");
        CmsTopicNode to = new CmsTopicNode();
        to.setLatitude("30.2"); to.setLongitude("120.2");
        return List.of(from, to);
    }

    private void assertProviderGeometry(String json)
    {
        JSONArray geometry = JSONArray.parseArray(json);
        assertEquals(3, geometry.size(), "不得将两点直线降级当成 provider 成功");
        assertEquals(30.1D, geometry.getJSONObject(0).getDoubleValue("latitude"), 1e-7);
        assertEquals(30.2D, geometry.getJSONObject(2).getDoubleValue("latitude"), 1e-7);
    }

    /** 作为真实 Spring bean 的受控 provider，所有 GET 都在内存返回，不调用父类网络出口。 */
    static class ControlledMapClient extends TencentMapClient
    {
        private final List<String> endpoints = new ArrayList<>();

        @Override
        public boolean isReady()
        {
            return true;
        }

        @Override
        public void warnIfNotReady()
        {
            // 测试 provider 不需要真实 key；不执行生产 provider 的配置告警。
        }

        @Override
        public String get(String endpoint)
        {
            endpoints.add(endpoint);
            return "{\"status\":0,\"result\":{\"routes\":[{\"polyline\":[30.1,120.1,50000,50000,50000,50000]}]}}";
        }
    }
}
