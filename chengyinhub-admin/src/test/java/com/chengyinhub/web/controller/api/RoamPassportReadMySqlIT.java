package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.RoamPassportMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.RoamPassportReadService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Mapper SQL验证；仅批准MySQL测试环境运行，禁用时不计通过。 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoamPassportReadMySqlIT {
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String database;
    private RoamPassportReadService service;
    @BeforeAll void setup() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("roam_passport");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertEquals("8.0.45", admin.queryForObject("select version()", String.class));
        database = "chengyin_roam_passport_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("create database " + database);
        DriverManagerDataSource source = new DriverManagerDataSource(
            config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"), config.user(), config.password());
        db = new JdbcTemplate(source);
        db.execute("create table roam_session(id bigint primary key, member_id bigint not null, status int, started_at datetime, finished_at datetime, distance_m int, finish_result_json longtext) engine=InnoDB");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/RoamPassportMapper.xml"));
        service = new RoamPassportReadService(new SqlSessionTemplate(factory.getObject()).getMapper(RoamPassportMapper.class));
    }
    @AfterAll void cleanup() { if (admin != null && database != null) admin.execute("drop database " + database); }
    @Test void exactPaginationOwnerSummaryAndDeletionUseActualSql() {
        db.update("insert into roam_session values(9007199254740995,7,1,'2026-10-01 10:00:00','2026-10-01 11:00:00',1200,'{\"sessionShops\":2}')");
        db.update("insert into roam_session values(9007199254740994,7,1,'2026-10-02 10:00:00','2026-10-02 11:00:00',800,'{\"sessionShops\":1}')");
        db.update("insert into roam_session values(9007199254740996,8,1,now(),now(),9999,'{\"sessionShops\":99}')");
        db.update("insert into roam_session values(9007199254740997,7,0,now(),null,0,null)");
        Map<String, Object> first = service.page(7L, null, 1);
        assertEquals("9007199254740995", first.get("nextBefore"));
        Map<String, Object> second = service.page(7L, (String) first.get("nextBefore"), 1);
        assertEquals(false, second.get("hasMore"));
        assertEquals("9007199254740994", ((Map<?, ?>) ((List<?>) second.get("items")).get(0)).get("sessionId"));
        Map<String, Object> summary = service.summary(7L);
        assertEquals(2L, ((Number) summary.get("trips")).longValue());
        assertEquals(2000L, ((Number) summary.get("distanceM")).longValue());
        assertEquals(3L, ((Number) summary.get("shops")).longValue());
        assertEquals(2L, ((Number) summary.get("days")).longValue());
        assertThrows(RuntimeException.class, () -> service.detail(8L, "9007199254740995"));
        db.update("insert into roam_session values(1,7,1,null,null,null,'invalid legacy json')");
        assertNull(service.summary(7L).get("shops"));
        assertNull(service.summary(7L).get("distanceM"));
        assertNull(service.detail(7L, "1").get("shops"));
        db.update("delete from roam_session where id=9007199254740995");
        assertThrows(RuntimeException.class, () -> service.detail(7L, "9007199254740995"));
        assertEquals(0L, ((Number) service.summary(9L).get("trips")).longValue());
    }
}
