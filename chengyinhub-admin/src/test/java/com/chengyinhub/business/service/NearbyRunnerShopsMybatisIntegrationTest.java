package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.vo.NearbyRunnerVO;
import com.chengyinhub.business.domain.vo.RunnerShopVO;
import com.chengyinhub.business.mapper.RoamMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL mixed-collation source columns; each test owns a scratch schema. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class NearbyRunnerShopsMybatisIntegrationTest {
    private MysqlIntegrationTestConfig config;
    private SqlSessionFactory factory;
    private static final long NOW = 1800000000000L;

    @BeforeEach
    void setUp() throws Exception {
        config = MysqlIntegrationTestConfig.fromEnvironment(
                "nearby_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        try (Connection connection = DriverManager.getConnection(config.adminUrl(), config.user(), config.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + config.databaseName()
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
        configuration.getTypeAliasRegistry().registerAlias("RoamSession", RoamSession.class);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                        databaseUrl() + "&connectionCollation=utf8mb4_0900_ai_ci&useServerPrepStmts=true",
                        config.user(), config.password())));
        String resource = "mapper/business/RoamMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        // Match the source collations in base_schema_20260818.sql, without unrelated business columns.
        execute("create table roam_poi(id bigint primary key,cover_img varchar(500) "
                + "character set utf8mb4 collate utf8mb4_0900_ai_ci)");
        execute("create table cms_registration_merchant(id bigint primary key,pic_url varchar(2048) "
                + "character set utf8mb4 collate utf8mb4_general_ci)");
        execute("create table ums_member(id bigint primary key,nickname varchar(80),avatar varchar(500),del_flag int)");
        execute("create table roam_session(id bigint primary key,member_id bigint,status int,"
                + "last_report_at datetime,last_lat decimal(10,6),last_lng decimal(10,6),"
                + "explore_pct int,started_at datetime,create_time datetime)");
        execute("create table roam_shop_visit(id bigint primary key,member_id bigint,session_id bigint,"
                + "source_type int,source_id bigint,shop_name varchar(80),visited_at datetime)");
        execute("insert into ums_member values(1,'viewer','',0),(2,'runner','',0),(3,'outside','',0)");
        session(10, 1, 0, 0);
        session(20, 2, 0, 0);
        session(30, 3, 0, 0);
        execute("insert into roam_poi values(1,'poi-image'),(2,''),(3,null),(4,'   ')");
        execute("insert into cms_registration_merchant values(1,'merchant-image'),(2,''),(3,null),(4,'   ')");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (config != null) {
            try (Connection connection = DriverManager.getConnection(config.adminUrl(), config.user(), config.password());
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE `" + config.databaseName() + "`");
            }
        }
    }

    @Test
    void nonemptyNearbyRunnersCanReadBothMixedCollationImageSources() throws Exception {
        visit(1, 2, 20, 1, 1);
        visit(2, 2, 20, 2, 1);
        try (SqlSession session = factory.openSession(true)) {
            RoamMapper mapper = session.getMapper(RoamMapper.class);
            List<NearbyRunnerVO> runners = mapper.selectNearbyRunners(1L, 31.0, 121.0, 1000,
                    new Date(NOW - 300000), new Date(NOW), 20);
            assertEquals(2, runners.size(), "nonempty discovery must reach the photo query");
            List<RunnerShopVO> shops = mapper.selectRunnerShops(
                    runners.stream().map(NearbyRunnerVO::getMemberId).collect(Collectors.toList()), 6);
            assertEquals(Arrays.asList("merchant-image", "poi-image"), images(shops));
            assertTrue(shops.stream().allMatch(row -> row.getMemberId().equals(2L)));
        }
    }

    @Test
    void nullAndEmptyImagesAreExcludedWithoutTrimmingNonemptyValues() throws Exception {
        for (int type : new int[]{1, 2}) {
            for (int source = 1; source <= 4; source++) {
                visit(type * 10 + source, 2, 20, type, source);
            }
        }
        visit(30, 2, 20, 3, 1);
        visit(31, 2, 20, 1, 999);
        List<String> found = images(shops(20));
        assertEquals(Arrays.asList("   ", "   ", "merchant-image", "poi-image"), found);
        assertFalse(found.contains(""));
        assertFalse(found.contains(null));
    }

    @Test
    void existingHomogeneousNoPadCollationAllowsSpaceOnlyImages() throws Exception {
        execute("alter table cms_registration_merchant modify pic_url varchar(2048) "
                + "character set utf8mb4 collate utf8mb4_0900_ai_ci");
        assertEquals("1", scalar("select cover_img <> '' from roam_poi where id=4"));
        visit(1, 2, 20, 1, 4);
        visit(2, 2, 20, 2, 4);
        assertEquals(Arrays.asList("   ", "   "), images(shops(6)));
    }

    @Test
    void memberLatestActiveSessionAndPhotoLimitRemainUnchanged() throws Exception {
        session(19, 2, 0, 30);
        session(21, 2, 0, 0);
        session(22, 2, 1, -10);
        visit(1, 2, 19, 1, 1);
        visit(2, 2, 20, 1, 1);
        visit(3, 2, 22, 1, 1);
        visit(4, 3, 30, 1, 1);
        visit(5, 2, 21, 1, 1);
        visit(6, 2, 21, 2, 1);
        assertEquals(Arrays.asList("merchant-image", "poi-image"), images(shops(6)));
        assertEquals(Collections.singletonList("merchant-image"), images(shops(1)));
        visit(7, 2, 21, 1, 2);
        assertTrue(shops(1).isEmpty(), "blank newest visit still consumes the pre-filter photo slot");
        execute("update roam_session set status=1 where member_id=2");
        assertTrue(shops(6).isEmpty(), "ended sessions cannot supply current photos");
    }

    @Test
    void nearbyDiscoveryStillExcludesSelfStaleEndedDistantAndDeletedMembers() throws Exception {
        execute("insert into ums_member values(4,'ended','',0),(5,'distant','',0),(6,'deleted','',2)");
        session(40, 4, 1, 0);
        session(50, 5, 0, 0);
        session(60, 6, 0, 0);
        execute("update roam_session set last_lat=0,last_lng=0 where id=50");
        execute("update roam_session set last_report_at=from_unixtime(" + (NOW / 1000 - 301) + ") where id=30");
        try (SqlSession session = factory.openSession(true)) {
            List<NearbyRunnerVO> found = session.getMapper(RoamMapper.class).selectNearbyRunners(
                    1L, 31.0, 121.0, 1000, new Date(NOW - 300000), new Date(NOW), 20);
            assertEquals(Collections.singletonList(2L),
                    found.stream().map(NearbyRunnerVO::getMemberId).collect(Collectors.toList()));
        }
    }

    private List<String> images(List<RunnerShopVO> rows) {
        return rows.stream().map(RunnerShopVO::getShopImage).sorted().collect(Collectors.toList());
    }

    private List<RunnerShopVO> shops(int limit) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(RoamMapper.class).selectRunnerShops(Collections.singletonList(2L), limit);
        }
    }

    private void session(long id, long member, int status, int ageSeconds) throws Exception {
        execute("insert into roam_session values(" + id + "," + member + "," + status
                + ",from_unixtime(" + (NOW / 1000 - ageSeconds)
                + "),31,121,10,from_unixtime(" + (NOW / 1000 - 600) + "),null)");
    }

    private void visit(long id, long member, long session, int type, long source) throws Exception {
        execute("insert into roam_shop_visit values(" + id + "," + member + "," + session + "," + type
                + "," + source + ",'shop-" + id + "',from_unixtime(" + (NOW / 1000) + "))");
    }

    private String databaseUrl() {
        // Same fixture clock for the local macOS server and the UTC MySQL container.
        return config.databaseUrl().replace("connectionTimeZone=LOCAL", "connectionTimeZone=UTC")
                + "&forceConnectionTimeZoneToSession=true";
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(databaseUrl(), config.user(), config.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(databaseUrl(), config.user(), config.password());
             Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
