package com.chengyinhub.framework;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceAutoConfigure;
import com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceWrapper;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.common.enums.DataSourceType;
import com.chengyinhub.common.utils.spring.SpringUtils;
import com.chengyinhub.framework.config.DruidConfig;
import com.chengyinhub.framework.config.properties.DruidProperties;
import com.chengyinhub.framework.datasource.DynamicDataSource;
import com.chengyinhub.framework.datasource.DynamicDataSourceContextHolder;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class DruidWallConfigTest {
    @Test
    void actualYamlEnablesWallAndRejectsMultipleStatements() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-druid.yml"));
        Properties properties = yaml.getObject();
        assertNotNull(properties);
        assertEquals("true", properties.getProperty("spring.datasource.druid.filter.wall.enabled"));
        Map<String, Object> values = new LinkedHashMap<>();
        properties.forEach((key, value) -> values.put(String.valueOf(key), value));
        WallConfig config = new Binder(new MapConfigurationPropertySource(values))
                .bind("spring.datasource.druid.filter.wall.config", Bindable.of(WallConfig.class)).get();
        assertFalse(config.isMultiStatementAllow());
        MySqlWallProvider provider = new MySqlWallProvider(config);
        assertTrue(provider.check("select id from ums_member where id=1").getViolations().isEmpty());
        assertTrue(provider.check("update ums_member set nickname='A' where id=1").getViolations().isEmpty());
        assertFalse(provider.check("select id from ums_member; delete from ums_member where id=1")
                .getViolations().isEmpty());
    }

    @Test
    void realMasterAndSlaveUseConfiguredWallAndDynamicRouting() {
        withRealDruidContext(context -> {
            DruidDataSource master = context.getBean("masterDataSource", DruidDataSource.class);
            DruidDataSource slave = context.getBean("slaveDataSource", DruidDataSource.class);
            assertInstanceOf(DruidDataSourceWrapper.class, master);
            assertInstanceOf(DruidDataSourceWrapper.class, slave);
            assertTrue(master.isInited());
            assertTrue(slave.isInited());
            WallConfig configured = context.getBean(WallConfig.class);
            assertFalse(configured.isMultiStatementAllow());
            assertSame(configured, actualWall(master).getConfig());
            assertSame(configured, actualWall(slave).getConfig());
            assertSame(actualWall(master), actualWall(slave));

            DynamicDataSource router = context.getBean("dynamicDataSource", DynamicDataSource.class);
            assertSame(router, context.getBean(javax.sql.DataSource.class));
            assertSame(master, ReflectionTestUtils.invokeMethod(router, "determineTargetDataSource"));
            DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.MASTER.name());
            assertSame(master, ReflectionTestUtils.invokeMethod(router, "determineTargetDataSource"));
            DynamicDataSourceContextHolder.setDataSourceType(DataSourceType.SLAVE.name());
            assertSame(slave, ReflectionTestUtils.invokeMethod(router, "determineTargetDataSource"));
            DynamicDataSourceContextHolder.clearDataSourceType();
            assertSame(master, ReflectionTestUtils.invokeMethod(router, "determineTargetDataSource"));
        });
    }

    @Test
    void actualMapperBoundSqlKeepsSingleStatementTerminatorsAndLiteralsWhileRejectingStackedSql()
            throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("UmsMemberBalanceDetail", UmsMemberBalanceDetail.class);
        configuration.getTypeAliasRegistry().registerAlias("CommunityPost", CommunityPost.class);
        for (String resource : List.of("mapper/business/UmsMemberBalanceDetailMapper.xml",
                "mapper/business/CommunityPostMapper.xml")) {
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        String balance = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper.statisticsChangeBalance")
                .getBoundSql(Map.of("memberId", 11L, "year", 2026)).getSql();
        assertTrue(balance.trim().endsWith(";"), "exercise the actual mapper's trailing terminator");
        Map<String, String> references = new LinkedHashMap<>();
        for (String type : List.of("ACTIVITY", "TOPIC", "ROUTE", "CLUB", "POI", "UNKNOWN")) {
            references.put(type, configuration.getMappedStatement(
                    "com.chengyinhub.business.mapper.CommunityPostMapper.selectReferenceSnapshot")
                    .getBoundSql(Map.of("referenceType", type, "referenceId", 31L, "memberId", 11L)).getSql());
        }
        assertTrue(references.get("ACTIVITY").contains("';'"), "exercise the actual quoted separator");
        String foreach = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.CommunityPostMapper.countActiveMemberEnforcement")
                .getBoundSql(Map.of("memberId", 11L, "now", new java.util.Date(0),
                        "types", List.of("POSTING", "COMMENTING"))).getSql();
        assertTrue(foreach.replaceAll("\\s+", " ").matches("(?s).*\\(\\s*\\?\\s*,\\s*\\?\\s*\\).*"));
        withRealDruidContext(context -> {
            MySqlWallProvider provider = assertInstanceOf(MySqlWallProvider.class,
                    actualWall(context.getBean("masterDataSource", DruidDataSource.class)).getProvider());
            assertTrue(provider.check(balance).getViolations().isEmpty(), "single trailing terminator remains valid");
            references.forEach((type, sql) -> assertTrue(provider.check(sql).getViolations().isEmpty(), type));
            assertTrue(provider.check(foreach).getViolations().isEmpty(), "comma foreach remains a single statement");
            assertFalse(provider.check(balance + " delete from ums_member where id=11").getViolations().isEmpty());
        });
    }

    private static WallFilter actualWall(DruidDataSource source) {
        List<WallFilter> filters = source.getProxyFilters().stream()
                .filter(WallFilter.class::isInstance).map(WallFilter.class::cast).toList();
        assertEquals(1, filters.size(), "actual initialized data source must have exactly one wall filter");
        return filters.get(0);
    }

    private static void withRealDruidContext(Consumer<AssertableApplicationContext> assertions) {
        Object previousFactory = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        Object previousContext = ReflectionTestUtils.getField(SpringUtils.class, "applicationContext");
        NoConnectDriver.attempts.set(0);
        try {
            new ApplicationContextRunner()
                    .withInitializer(context -> {
                        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
                        yaml.setResources(new ClassPathResource("application-druid.yml"));
                        Properties properties = yaml.getObject();
                        assertNotNull(properties);
                        context.getEnvironment().getPropertySources().addLast(
                                new PropertiesPropertySource("shipped-druid", properties));
                    })
                    .withConfiguration(AutoConfigurations.of(DruidDataSourceAutoConfigure.class))
                    .withUserConfiguration(DruidConfig.class, DruidProperties.class, SpringUtils.class)
                    .withPropertyValues(
                            "spring.datasource.driver-class-name=" + NoConnectDriver.class.getName(),
                            "spring.datasource.druid.master.driver-class-name=" + NoConnectDriver.class.getName(),
                            "spring.datasource.druid.slave.driver-class-name=" + NoConnectDriver.class.getName(),
                            "spring.datasource.druid.master.url=jdbc:mysql://127.0.0.1:1/wall_master_fixture",
                            "spring.datasource.druid.master.username=wall_fixture",
                            "spring.datasource.druid.master.password=fixture-only",
                            "spring.datasource.druid.slave.enabled=true",
                            "spring.datasource.druid.slave.url=jdbc:mysql://127.0.0.1:1/wall_slave_fixture",
                            "spring.datasource.druid.slave.username=wall_fixture",
                            "spring.datasource.druid.slave.password=fixture-only",
                            "spring.datasource.druid.initialSize=0",
                            "spring.datasource.druid.minIdle=0",
                            "spring.datasource.druid.keep-alive=false",
                            "spring.datasource.druid.async-init=false",
                            "spring.datasource.druid.statViewServlet.enabled=false",
                            "spring.datasource.druid.statViewServlet.login-password=fixture-only",
                            "spring.datasource.druid.webStatFilter.enabled=false")
                    .run(context -> {
                        assertNull(context.getStartupFailure(), "actual Boot4 Druid context must start");
                        assertions.accept(context);
                    });
            // The production wrapper really initializes; a zero-sized pool must not open JDBC connections.
            assertEquals(0, NoConnectDriver.attempts.get(), "no JDBC connection during startup, checks or shutdown");
        } finally {
            DynamicDataSourceContextHolder.clearDataSourceType();
            ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", previousFactory);
            ReflectionTestUtils.setField(SpringUtils.class, "applicationContext", previousContext);
        }
    }

    public static final class NoConnectDriver implements Driver {
        private static final AtomicInteger attempts = new AtomicInteger();

        @Override public Connection connect(String url, Properties properties) throws SQLException {
            attempts.incrementAndGet();
            throw new SQLException("Druid wall fixture forbids JDBC connections");
        }
        @Override public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:mysql:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) {
            return new DriverPropertyInfo[0];
        }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getLogger(NoConnectDriver.class.getName()); }
    }
}
