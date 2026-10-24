package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.dto.PointsStatisticsDTO;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.UmsMemberServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class UmsMemberRankMySqlIT {
    @Test void realMapperRanksAndStatisticsKeepJdbcNumbers() throws Exception {
        for (String key : new String[]{"HOST", "PORT", "DATABASE_BASE", "DATABASE_SUFFIX", "USER", "PASSWORD"}) {
            assertTrue(System.getenv().containsKey("CHENGYIN_TEST_MYSQL_" + key),
                    "必须显式提供独占测试配置: CHENGYIN_TEST_MYSQL_" + key);
        }
        String host = System.getenv("CHENGYIN_TEST_MYSQL_HOST");
        assertTrue("127.0.0.1".equals(host) || "localhost".equals(host), "Rank IT 只允许显式 loopback 测试库");
        assertFalse(System.getenv("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX").trim().isEmpty(), "测试库 suffix 不能为空");
        assertFalse(System.getenv("CHENGYIN_TEST_MYSQL_USER").trim().isEmpty(), "测试账号不能为空");
        MysqlIntegrationTestConfig testConfig = MysqlIntegrationTestConfig.fromEnvironment("member_rank");
        assertTrue(testConfig.databaseName().length() <= 47, "库名前缀须为UUID独占后缀预留17字符");
        String database = testConfig.databaseName() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String url = testConfig.databaseUrl().replace("/" + testConfig.databaseName() + "?", "/" + database + "?");
        PooledDataSource ds = null;
        try (Connection admin = DriverManager.getConnection(testConfig.adminUrl(), testConfig.user(), testConfig.password())) {
            boolean created = false;
            try {
                try (Statement statement = admin.createStatement();
                     ResultSet version = statement.executeQuery("select version()")) {
                    assertTrue(version.next());
                    assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version.getString(1)), "必须使用真实MySQL 8+");
                }
                try (PreparedStatement check = admin.prepareStatement(
                        "select count(*) from information_schema.schemata where schema_name = ?")) {
                    check.setString(1, database);
                    try (ResultSet rows = check.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(0, rows.getInt(1), "UUID测试库必须尚不存在，不能复用或删除已有库");
                    }
                }
                try (Statement statement = admin.createStatement()) {
                    statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
                    created = true;
                }
                try (Connection fixture = DriverManager.getConnection(url, testConfig.user(), testConfig.password())) {
                    Path baseline = Paths.get("sql/base_schema_20260818.sql");
                    if (!Files.exists(baseline)) baseline = Paths.get("../sql/base_schema_20260818.sql");
                    String ddl = new String(Files.readAllBytes(baseline), StandardCharsets.UTF_8);
                    int start = ddl.indexOf("CREATE TABLE `ums_member`");
                    assertTrue(start >= 0, "基线ums_member定义缺失");
                    int end = ddl.indexOf(';', start);
                    assertTrue(end > start);
                    try (Statement statement = fixture.createStatement()) {
                        statement.execute(ddl.substring(start, end + 1));
                        // 与现有 migration_app_open_id.sql 的新增列形一致，供当前会员mapper完整投影使用。
                        statement.execute("ALTER TABLE ums_member ADD COLUMN app_open_id varchar(64) NULL"
                                + " COMMENT '微信开放平台移动应用openid(App端)' AFTER wx_open_id");
                        statement.execute("INSERT INTO ums_member(id,point,balance,del_flag,create_time) VALUES"
                                + "(990011,300,100.00,0,'2020-01-01 00:00:00'),"
                                + "(990012,200,300.00,0,'2020-01-02 00:00:00'),"
                                + "(990013,200,200.00,0,'2020-01-03 00:00:00'),"
                                + "(990014,99999,99999.00,2,'2019-01-01 00:00:00')");
                    }
                }
                ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", url, testConfig.user(), testConfig.password());
                Configuration config = new Configuration(new Environment("rank", new JdbcTransactionFactory(), ds));
                config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
                String resource = "mapper/business/UmsMemberMapper.xml";
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                    new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
                }
                try (SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession()) {
                    session.getConnection().setReadOnly(true);
                    UmsMemberMapper mapper = session.getMapper(UmsMemberMapper.class);
                    UmsMemberServiceImpl service = new UmsMemberServiceImpl();
                    ReflectionTestUtils.setField(service, "umsMemberMapper", mapper);
                    IUmsMemberPointsDetailService points = mock(IUmsMemberPointsDetailService.class);
                    IUmsMemberBalanceDetailService balance = mock(IUmsMemberBalanceDetailService.class);
                    when(points.getWeekPointsIncome(anyLong())).thenReturn(BigDecimal.ONE);
                    when(balance.getWeekBalanceIncome(anyLong())).thenReturn(BigDecimal.ONE);
                    ReflectionTestUtils.setField(service, "pointsDetailService", points);
                    ReflectionTestUtils.setField(service, "balanceDetailService", balance);
                    for (String score : new String[]{"point", "balance"}) {
                        List<Long> ordered = new ArrayList<>();
                        Connection connection = session.getConnection();
                        connection.setReadOnly(true);
                        try (ResultSet rows = connection.createStatement().executeQuery(
                                "SELECT id FROM ums_member WHERE del_flag=0 ORDER BY " + score + " DESC,create_time ASC")) {
                            while (rows.next()) ordered.add(rows.getLong(1));
                        }
                        assertTrue(ordered.contains(990011L));
                        for (int index = 0; index < ordered.size(); index++) {
                            long id = ordered.get(index);
                            Map<String, Object> rank = "point".equals(score)
                                    ? mapper.selectUserRankInfo(id) : mapper.selectUserBalanceRankInfo(id);
                            assertEquals(index + 1, ((Number) rank.get("user_rank")).intValue(), score + " member " + id);
                            assertEquals(ordered.size(), ((Number) rank.get("total_users")).intValue());
                            String bucket = "TOP" + Math.min(100, (int) Math.ceil((index + 1.0) / ordered.size() * 5) * 20) + "%";
                            assertEquals(bucket, rank.get("rank_percentage"));
                            if (index == 0) System.out.println(score + " JDBC mapper: rank=" + rank.get("user_rank").getClass().getName() + ",total=" + rank.get("total_users").getClass().getName());
                            PointsStatisticsDTO stats = "point".equals(score)
                                    ? service.getPointsStatistics(id) : service.getBalanceStatistics(id);
                            assertEquals(index + 1, stats.getUserRank(), score + " statistics member " + id);
                            assertEquals(ordered.size(), stats.getTotalUsers());
                            assertEquals(bucket, stats.getRankPercentage());
                            assertEquals(BigDecimal.ONE, stats.getWeekPoints());
                            if (id == 990011L) System.out.println(score + " mapper types: user_rank="
                                    + rank.get("user_rank").getClass().getName() + ",total_users="
                                    + rank.get("total_users").getClass().getName());
                        }
                        assertNull("point".equals(score) ? mapper.selectUserRankInfo(-1L) : mapper.selectUserBalanceRankInfo(-1L));
                    }
                    for (String kind : new String[]{"Points", "Balance"}) {
                        String mapperResource = "mapper/business/UmsMember" + kind + "DetailMapper.xml";
                        try (InputStream in = getClass().getClassLoader().getResourceAsStream(mapperResource)) {
                            new XMLMapperBuilder(in, config, mapperResource, config.getSqlFragments()).parse();
                        }
                        String table = "ums_member_" + kind.toLowerCase() + "_detail";
                        String amount = "change_" + kind.toLowerCase();
                        String mapperId = "com.chengyinhub.business.mapper.UmsMember" + kind
                                + "DetailMapper.selectWeek" + kind + "Income";
                        String realSql = config.getMappedStatement(mapperId)
                                .getBoundSql(Collections.singletonMap("memberId", 990005L)).getSql();
                        for (String date : new String[]{"2026-10-05", "2026-10-06", "2026-10-07",
                                "2026-10-08", "2026-10-09", "2026-10-10", "2026-10-11", "2026-12-31", "2027-01-03"}) {
                            LocalDate monday = LocalDate.parse(date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                            String sample = "(SELECT 990005 member_id, 5 " + amount
                                    + ", 1 change_type, 0 del_flag, TIMESTAMP('" + monday + " 00:00:00') create_time"
                                    + " UNION ALL SELECT 990005,7,1,0,'" + monday.plusDays(6) + " 23:59:59'"
                                    + " UNION ALL SELECT 990005,11,1,0,'" + monday.minusDays(1) + " 23:59:59'"
                                    + " UNION ALL SELECT 990005,13,1,0,'" + monday.plusDays(7) + " 00:00:00'"
                                    + " UNION ALL SELECT 990006,17,1,0,'" + monday + " 12:00:00'"
                                    + " UNION ALL SELECT 990005,19,1,1,'" + monday + " 12:00:00'"
                                    + " UNION ALL SELECT 990005,23,2,0,'" + monday + " 12:00:00') " + table;
                            // Exercise the mapped SQL on read-only derived rows without changing accounts or the database clock.
                            String sql = realSql.replace(table, sample).replace("CURDATE()", "DATE('" + date + "')");
                            try (PreparedStatement statement = session.getConnection().prepareStatement(sql)) {
                                statement.setLong(1, 990005L);
                                try (ResultSet rows = statement.executeQuery()) {
                                    assertTrue(rows.next());
                                    assertEquals(0, new BigDecimal("12").compareTo(rows.getBigDecimal(1)), kind + " week " + date);
                                    assertFalse(rows.next());
                                }
                            }
                        }
                    }
                }
            } finally {
                if (ds != null) ds.forceCloseAll();
                if (created) {
                    try (Statement statement = admin.createStatement()) {
                        statement.execute("DROP DATABASE `" + database + "`");
                    }
                    try (PreparedStatement check = admin.prepareStatement(
                            "select count(*) from information_schema.schemata where schema_name = ?")) {
                        check.setString(1, database);
                        try (ResultSet rows = check.executeQuery()) {
                            assertTrue(rows.next());
                            assertEquals(0, rows.getInt(1), "测试库清理后必须独立回读不存在");
                        }
                    }
                    System.out.println("UmsMemberRankMySqlIT scratch cleanup=CLEAN schema=" + database);
                }
            }
        }
    }
}
