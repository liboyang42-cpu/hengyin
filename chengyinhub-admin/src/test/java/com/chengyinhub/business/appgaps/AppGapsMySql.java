package com.chengyinhub.business.appgaps;

import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

/**
 * App 缺口真库 IT 共用连接：只连 VPS TEST 库的副本（APPGAPS_MYSQL_DATABASE，默认 cy_gaps_b，
 * 由 cy_e2e_beint 复制），绝不连共享库或生产库。迁移按台账文件原样执行两遍，验证幂等。
 */
public final class AppGapsMySql {
    public final DriverManagerDataSource source;
    public final JdbcTemplate db;
    public final DataSourceTransactionManager tx;
    public final String database;

    public AppGapsMySql(String... migrations) throws Exception {
        String host = env("CHENGYIN_TEST_MYSQL_HOST", "127.0.0.1"), port = env("CHENGYIN_TEST_MYSQL_PORT", "3330");
        database = env("APPGAPS_MYSQL_DATABASE", "cy_gaps_b");
        if (!database.matches("cy_gaps_b[a-z0-9_]*")) throw new IllegalStateException("只允许 TEST 副本库 cy_gaps_b*");
        source = new DriverManagerDataSource("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowMultiQueries=false",
                env("CHENGYIN_TEST_MYSQL_USER", "root"), env("CHENGYIN_TEST_MYSQL_PASSWORD", ""));
        db = new JdbcTemplate(source);
        tx = new DataSourceTransactionManager(source);
        try (Connection c = source.getConnection()) {
            for (int pass = 0; pass < 2; pass++)
                for (String m : migrations) ScriptUtils.executeSqlScript(c, new FileSystemResource(sql(m)));
        }
    }

    static String sql(String name) {
        String p = "sql/" + name;
        if (new java.io.File(p).exists()) return p;
        return "chengyinhub-admin/sql/" + name;
    }

    public <T> T mapper(Class<T> type, String... xml) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 既有业务 mapper XML 用短别名（parameterType="SmsCoupon" 等），与应用 typeAliasesPackage 一致。
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        factory.setConfiguration(configuration);
        Resource[] resources = new Resource[xml.length];
        for (int i = 0; i < xml.length; i++) resources[i] = new ClassPathResource(xml[i]);
        factory.setMapperLocations(resources);
        return new SqlSessionTemplate(factory.getObject()).getMapper(type);
    }

    private static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }
}
