package com.chengyinhub.business.citygame.analytics.fairness.source;

import com.chengyinhub.business.citygame.analytics.fairness.CityFairnessIngestionService;
import com.chengyinhub.business.citygame.analytics.fairness.source.CityFairnessSourceRelay.ForwardResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Spring wires the relay to the real ingestion bean. The test does not construct that bean. */
class CityFairnessSourceRelayTest {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CityFairnessSourceRelay bean;
    private static CityFairnessSourceRelay relay;

    @BeforeAll
    static void start() {
        context = new AnnotationConfigApplicationContext(Wiring.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        bean = context.getBean(CityFairnessSourceRelay.class);
        relay = AopTestUtils.getUltimateTargetObject(bean);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @Test
    void springInjectsTheProductionIngestionBean() {
        assertSame(context.getBean(CityFairnessIngestionService.class), relay.ingestion);
    }

    @Test
    void committedOutcomeWithoutCityScopeDoesNotIngest() {
        long auto = insert(11L, 7L, 1L, "COMPLETED", "AUTO_HASH");
        long fallback = insert(11L, 8L, 2L, "FALLBACK_COMPLETED", "FALLBACK_PLAN");
        ForwardResult first = bean.forward(auto);
        ForwardResult again = bean.forward(auto);
        ForwardResult planned = bean.forward(fallback);
        assertEquals("SCOPE_ABSENT", first.status);
        assertEquals("city-board-scope-join", first.reason);
        assertEquals(Long.toString(auto), first.eventId);
        assertEquals("game-session:11", first.outingId);
        assertEquals(first.eventId, again.eventId);
        assertEquals("SCOPE_ABSENT", again.status);
        assertEquals(Long.toString(fallback), planned.eventId);
        assertEquals("game-session:11", planned.outingId);
        assertEquals(List.of("gps-false-rejections", "safety-tickets", "city-board-scope-join"), first.coverageGaps);
        assertEquals(0, count("city_fairness_event"));
    }

    @Test
    void unknownAndClientSuccessDoNotIngest() {
        long unknown = insert(21L, 7L, 1L, "UNKNOWN", "AUTO_HASH");
        long client = insert(22L, 7L, 1L, "COMPLETED", "success");
        ForwardResult rolled = bean.forward(unknown);
        ForwardResult forged = bean.forward(client);
        assertEquals("NOT_COMMITTED", rolled.status);
        assertEquals(Long.toString(unknown), rolled.eventId);
        assertEquals("game-session:21", rolled.outingId);
        assertEquals("NOT_AUTHORITATIVE", forged.status);
        assertEquals(Long.toString(client), forged.eventId);
        assertEquals("game-session:22", forged.outingId);
        assertEquals(0, count("city_fairness_event"));
    }

    @Test
    void missingAndRolledBackRowsStayAbsent() {
        assertEquals("ABSENT", bean.forward(null).status);
        assertEquals("ABSENT", bean.forward(0L).status);
        assertEquals("ABSENT", bean.forward(9_999_999L).status);
        TransactionTemplate tx = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        Long id = tx.execute(status -> {
            long generated = insert(31L, 7L, 1L, "COMPLETED", "EVIDENCE_ONLY");
            ForwardResult visible = bean.forward(generated);
            assertEquals("SCOPE_ABSENT", visible.status);
            assertEquals(Long.toString(generated), visible.eventId);
            assertEquals("game-session:31", visible.outingId);
            status.setRollbackOnly();
            return generated;
        });
        assertEquals("ABSENT", bean.forward(id).status);
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from game_player_node_outcome where game_session_id=31", Integer.class));
        assertEquals(0, count("city_fairness_event"));
    }

    private static long insert(long sessionId, long memberId, long nodeId, String status, String source) {
        jdbc.update("insert into game_player_node_outcome"
                + " (game_session_id, activity_id, station_run_id, node_id, team_id, member_id, status,"
                + " completion_source, completed_at, create_time) values (?,?,?,?,?,?,?,?,?,?)",
                sessionId, 100L, 200L, nodeId, 300L, memberId, status, source,
                Timestamp.from(Instant.parse("2026-10-21T12:00:00Z")),
                Timestamp.from(Instant.parse("2026-10-21T12:00:01Z")));
        return jdbc.queryForObject(
                "select id from game_player_node_outcome where game_session_id=? and member_id=? and node_id=?",
                Long.class, sessionId, memberId, nodeId);
    }

    private static int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan({
            "com.chengyinhub.business.citygame.analytics.fairness.mapper",
            "com.chengyinhub.business.citygame.analytics.fairness.source.mapper"
    })
    @ComponentScan(basePackageClasses = {CityFairnessIngestionService.class, CityFairnessSourceRelay.class},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {CityFairnessIngestionService.class, CityFairnessSourceRelay.class}))
    static class Wiring {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:fairness_source_" + System.nanoTime()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            new JdbcTemplate(dataSource).execute("create table game_player_node_outcome ("
                    + "id bigint generated by default as identity primary key,"
                    + "game_session_id bigint not null, activity_id bigint not null, station_run_id bigint not null,"
                    + "node_id bigint not null, team_id bigint not null, member_id bigint not null,"
                    + "status varchar(24) not null, completion_source varchar(32) not null,"
                    + "completed_at timestamp not null, create_time timestamp not null,"
                    + "unique (game_session_id, member_id, node_id))");
            new JdbcTemplate(dataSource).execute("create table city_fairness_event ("
                    + "source_ref varchar(128) not null, event_id varchar(96) not null, outing_id varchar(96),"
                    + "kind varchar(64) not null, actor_id bigint not null, occurred_at bigint not null,"
                    + "join_time bigint, team_size int, action_cost int, newcomer varchar(32),"
                    + "gain_amount bigint, verification_millis bigint, board_id varchar(64) not null,"
                    + "region_id varchar(64) not null, season_id varchar(64) not null, rules_version varchar(64) not null,"
                    + "primary key (source_ref, event_id))");
            return dataSource;
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTransactionFactory(new SpringManagedTransactionFactory());
            factory.setMapperLocations(
                    new ClassPathResource("mapper/business/CityFairnessMapper.xml"),
                    new ClassPathResource("mapper/business/FairnessCompletionSourceMapper.xml"));
            return factory.getObject();
        }
    }
}
