package com.chengyinhub.business.it;

import com.alibaba.druid.pool.DruidDataSource;
import com.chengyinhub.framework.config.properties.DruidProperties;
import java.util.Map;
import java.util.List;
import java.nio.file.Path;
import com.chengyinhub.quartz.util.CrossDomainSchedulerStage;
import com.chengyinhub.quartz.util.SchedulerFenceProcessProbe;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Property, real datasource and child command assembly only; no web, SQL, Redis or provider proof. */
class CombinedContextConfigurationTest {
    @Test
    void boundedPoolOverridesBothIncompatibleInheritedFloorsWithoutChangingOtherOwners() {
        try (var application = inheritedDruidContext()) {
            var environment = application.getEnvironment();
            assertEquals(5, environment.getProperty("spring.datasource.druid.initialSize", Integer.class));
            assertEquals(10, environment.getProperty("spring.datasource.druid.minIdle", Integer.class));
            CrossDomainCombinedLoadMySqlIT.Initializer.configureBoundedPool(application);
            assertEquals(0, environment.getProperty("spring.datasource.druid.initialSize", Integer.class));
            assertEquals(0, environment.getProperty("spring.datasource.druid.minIdle", Integer.class));
            assertEquals(3, environment.getProperty("spring.datasource.druid.maxActive", Integer.class));
            assertEquals(5000, environment.getProperty("spring.datasource.druid.maxWait", Integer.class));
            assertEquals(5000, environment.getProperty("spring.datasource.druid.socketTimeout", Integer.class));
            assertEquals("original-owned-jdbc", environment.getProperty("spring.datasource.druid.master.url"));
            assertEquals("original-actor-secret", environment.getProperty("token.secret"));
            assertEquals("true", environment.getProperty("original.fixture.guard"));
        }
    }

    @Test
    void originalDruidPropertiesAssemblesAValidUninitializedThreeConnectionPool() {
        try (var application = inheritedDruidContext(); var pool = new DruidDataSource()) {
            CrossDomainCombinedLoadMySqlIT.Initializer.configureBoundedPool(application);
            var environment = application.getEnvironment();
            var properties = new DruidProperties();
            for (String field : new String[]{"initialSize", "minIdle", "maxActive", "maxWait", "socketTimeout"})
                ReflectionTestUtils.setField(properties, field,
                        environment.getRequiredProperty("spring.datasource.druid." + field, Integer.class));
            ReflectionTestUtils.setField(properties, "connectTimeout", 30000);
            ReflectionTestUtils.setField(properties, "timeBetweenEvictionRunsMillis", 60000);
            ReflectionTestUtils.setField(properties, "minEvictableIdleTimeMillis", 300000);
            ReflectionTestUtils.setField(properties, "maxEvictableIdleTimeMillis", 900000);
            ReflectionTestUtils.setField(properties, "validationQuery", "SELECT 1 FROM DUAL");
            ReflectionTestUtils.setField(properties, "testWhileIdle", true);
            ReflectionTestUtils.setField(properties, "testOnBorrow", false);
            ReflectionTestUtils.setField(properties, "testOnReturn", false);
            assertSame(pool, properties.dataSource(pool));
            assertEquals(0, pool.getInitialSize()); assertEquals(0, pool.getMinIdle());
            assertEquals(3, pool.getMaxActive()); assertEquals(5000, pool.getMaxWait());
            assertEquals(5000, pool.getSocketTimeout());
            assertTrue(pool.getInitialSize() <= pool.getMaxActive());
            assertTrue(pool.getMinIdle() <= pool.getMaxActive());
            assertFalse(pool.isInited(), "Assembly control must not open a database connection");
            assertEquals(0, pool.getActiveCount()); assertEquals(0, pool.getPoolingCount());
        }
    }

    @Test
    void schedulerChildHeapIsFixedOnlyForOriginalCombinedScope() {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String main=SchedulerFenceProcessProbe.class.getName();
        assertEquals(List.of(java,"-Xmx64m","-cp",cp,main),
                CrossDomainSchedulerStage.childArguments("CrossDomainCombinedLoadMySqlIT"));
        for(String scope:new String[]{null,"","CrossDomainFaultIsolationMySqlIT",
                " CrossDomainCombinedLoadMySqlIT","CrossDomainCombinedLoadMySqlIT -Xmx1024m",
                "crossdomaincombinedloadmysqlit"})
            assertEquals(List.of(java,"-cp",cp,main),CrossDomainSchedulerStage.childArguments(scope));
    }

    private static GenericApplicationContext inheritedDruidContext() {
        var application = new GenericApplicationContext();
        application.getEnvironment().getPropertySources().addFirst(new MapPropertySource("original-druid", Map.of(
                "spring.datasource.druid.initialSize", "5", "spring.datasource.druid.minIdle", "10",
                "spring.datasource.druid.maxActive", "20", "spring.datasource.druid.maxWait", "5000",
                "spring.datasource.druid.socketTimeout", "60000", "spring.datasource.druid.master.url", "original-owned-jdbc",
                "token.secret", "original-actor-secret", "original.fixture.guard", "true")));
        return application;
    }
}
