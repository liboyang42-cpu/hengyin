package com.chengyinhub.business.pointsgov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.chengyinhub.business.citycoin.CityCoinService;
import com.chengyinhub.business.mapper.CityCoinMapper;
import com.chengyinhub.business.mapper.PointsGovernanceMapper;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;

/**
 * Runs the governance and city coin mappers on H2 (MySQL mode) through the real services: daily cap per source,
 * monthly programme budget, the dashboard, and city coin idempotency and daily cap.
 */
class PointsGovernanceMyBatisTest {

    private static final String[] DDL = {
        "create table points_award_cap (event_type bigint primary key, source_name varchar(64) not null,"
            + " programme varchar(32) not null, daily_cap_points int, status char(1) not null default '0',"
            + " update_by varchar(64), update_time timestamp)",
        "create table points_programme_budget (programme varchar(32) primary key, programme_name varchar(64) not null,"
            + " monthly_budget_points bigint, status char(1) not null default '0', update_by varchar(64), update_time timestamp)",
        "create table points_budget_usage (programme varchar(32) not null, period char(7) not null,"
            + " issued_points bigint not null default 0, update_time timestamp, primary key (programme, period))",
        "create table ums_member_points_detail (id bigint auto_increment primary key, member_id bigint, change_points bigint,"
            + " event_type bigint, change_type bigint, del_flag int, create_time timestamp)",
        "create table ums_member (id bigint primary key, point bigint)",
        "create table city_coin_account (member_id bigint primary key, balance bigint not null default 0,"
            + " create_time timestamp not null, update_time timestamp)",
        "create table city_coin_ledger (id bigint auto_increment primary key, member_id bigint not null, amount int not null,"
            + " balance_after bigint not null, reason varchar(32) not null, ref_key varchar(160) not null,"
            + " place_id varchar(64), create_time timestamp not null, constraint uk_city_coin_ref unique (ref_key))",
        "insert into points_programme_budget values ('PLAY', '游玩奖励', null, '0', 'm', null)",
        "insert into points_award_cap values (12, '节点打卡', 'PLAY', 50, '0', 'm', null)",
        "insert into points_award_cap values (13, '通关', 'PLAY', null, '0', 'm', null)",
        "insert into points_award_cap values (14, '停用', 'PLAY', 1, '1', 'm', null)",
        "insert into ums_member values (1, 1000), (2, 500)"
    };

    // 2026-11-15 10:00 Asia/Shanghai
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private SqlSession session;
    private PointsGovernanceMapper gov;
    private CityCoinMapper coin;
    private ISysConfigService config;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:pointsgov_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : DDL) st.execute(sql);
        }
        Configuration configuration = new Configuration(new Environment("pg", new JdbcTransactionFactory(), ds));
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(PointsGovernanceMapper.class);
        configuration.addMapper(CityCoinMapper.class);
        parse(configuration, "mapper/business/PointsGovernanceMapper.xml");
        parse(configuration, "mapper/business/CityCoinMapper.xml");
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
        gov = session.getMapper(PointsGovernanceMapper.class);
        coin = session.getMapper(CityCoinMapper.class);
        config = mock(ISysConfigService.class);
    }

    private void parse(Configuration configuration, String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private PointsGovernanceService governance() {
        PointsGovernanceService s = new PointsGovernanceService(gov, config, null, new CityCoinService(coin, config, null));
        s.setClock(CLOCK);
        return s;
    }

    private void earned(long member, long eventType, long points, String at) throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into ums_member_points_detail (member_id, change_points, event_type, change_type, del_flag, create_time)"
                    + " values (" + member + ", " + points + ", " + eventType + ", 1, 0, timestamp '" + at + "')");
        }
    }

    @Test
    void dailyCapPerMemberAndSource() throws Exception {
        PointsGovernanceService s = governance();
        earned(1, 12, 40, "2026-11-15 09:00:00");
        earned(1, 12, 100, "2026-11-14 09:00:00"); // yesterday: not counted
        assertTrue(s.admit(1L, 12L, 10).admitted());
        PointsGovernanceService.Admission over = s.admit(1L, 12L, 11);
        assertFalse(over.admitted());
        assertEquals("DAILY_CAP", over.outcome());
        assertTrue(s.admit(2L, 12L, 50).admitted(), "another member has their own cap");
        assertTrue(s.admit(1L, 13L, 10_000).admitted(), "no daily cap on source 13");
        assertEquals("UNGOVERNED", s.admit(1L, 14L, 99).outcome(), "disabled cap row");
        assertEquals("UNGOVERNED", s.admit(1L, 99L, 99).outcome(), "source without a cap row");
    }

    @Test
    void shadowModeLogsButAdmits() throws Exception {
        when(config.selectConfigByKey(PointsGovernanceService.CONFIG_MODE)).thenReturn("shadow");
        earned(1, 12, 50, "2026-11-15 09:00:00");
        assertTrue(governance().admit(1L, 12L, 10).admitted());
        when(config.selectConfigByKey(PointsGovernanceService.CONFIG_MODE)).thenReturn("OFF");
        assertEquals("UNGOVERNED", governance().admit(1L, 12L, 10).outcome());
    }

    @Test
    void monthlyBudgetIsAllOrNothingAndTracked() {
        PointsGovernanceService s = governance();
        assertTrue(s.admit(1L, 13L, 70).admitted(), "budget not set: tracked only");
        s.updateBudget("PLAY", 100L, "0", "admin");
        assertTrue(s.admit(2L, 13L, 30).admitted());
        PointsGovernanceService.Admission over = s.admit(2L, 13L, 1);
        assertFalse(over.admitted());
        assertEquals("MONTHLY_BUDGET", over.outcome());
        List<Map<String, Object>> rows = gov.selectBudgetsWithUsage("2026-11");
        assertEquals(100L, ((Number) rows.get(0).get("issuedPoints")).longValue());
        assertEquals(0L, gov.selectBudgetsWithUsage("2026-12").get(0).get("issuedPoints") == null ? 0L
                : ((Number) gov.selectBudgetsWithUsage("2026-12").get(0).get("issuedPoints")).longValue());
        s.updateBudget("PLAY", 100L, "1", "admin");
        assertTrue(s.admit(2L, 13L, 1).admitted(), "budget not enforced when status=1");
    }

    @Test
    void dashboardShowsLiabilityInYuan() throws Exception {
        earned(1, 12, 200, "2026-11-03 09:00:00");
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into ums_member_points_detail (member_id, change_points, event_type, change_type, del_flag, create_time)"
                    + " values (1, 50, 2, 2, 0, timestamp '2026-11-04 09:00:00')");
        }
        PointsGovernanceService s = governance();
        s.updateBudget("PLAY", 5000L, "0", "admin");
        Map<String, Object> d = s.dashboard("2026-11");
        assertEquals(200L, d.get("issuedPoints"));
        assertEquals(new BigDecimal("4.00"), d.get("issuedYuan"));
        assertEquals(50L, d.get("redeemedPoints"));
        assertEquals(1500L, d.get("outstandingPoints"));
        assertEquals(new BigDecimal("30.00"), d.get("outstandingLiabilityYuan"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> programmes = (List<Map<String, Object>>) d.get("programmes");
        assertEquals(5000L, programmes.get(0).get("remainingPoints"));
        assertEquals(new BigDecimal("100.00"), programmes.get(0).get("budgetYuan"));
        assertEquals(3, ((List<?>) d.get("caps")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> city = (Map<String, Object>) d.get("cityCoin");
        assertEquals(0, city.get("liabilityYuan"));
        assertThrows(ServiceException.class, () -> s.dashboard("11/2026"));
    }

    @Test
    void adminUpdatesAreValidated() {
        PointsGovernanceService s = governance();
        assertEquals(1, s.updateCap(12L, 80, "0", "admin"));
        assertEquals(80, ((Number) gov.selectCap(12L).get("dailyCapPoints")).intValue());
        assertEquals(1, s.updateCap(12L, null, "0", "admin"));
        assertNull(gov.selectCap(12L).get("dailyCapPoints"));
        assertThrows(ServiceException.class, () -> s.updateCap(12L, -1, "0", "admin"));
        assertThrows(ServiceException.class, () -> s.updateCap(77L, 10, "0", "admin"));
        assertThrows(ServiceException.class, () -> s.updateCap(12L, 10, "x", "admin"));
        assertThrows(ServiceException.class, () -> s.updateBudget("NOPE", 10L, "0", "admin"));
    }

    @Test
    void cityCoinCreditIsIdempotentAndCapped() {
        when(config.selectConfigByKey(CityCoinService.CONFIG_HOLDER_YIELD)).thenReturn("30");
        when(config.selectConfigByKey(CityCoinService.CONFIG_DAILY_CAP)).thenReturn("70");
        CityCoinService c = new CityCoinService(coin, config, null);
        CityCoinService.CreditResult first = c.creditHolder(1L, "place-9", "checkin-1");
        assertEquals(CityCoinService.Outcome.CREDITED, first.outcome());
        assertEquals(30, first.amount());
        assertEquals(30L, first.balance());
        assertEquals(CityCoinService.Outcome.DUPLICATE, c.creditHolder(1L, "place-9", "checkin-1").outcome());
        assertEquals(30, c.creditHolder(1L, "place-9", "checkin-2").amount());
        CityCoinService.CreditResult clipped = c.creditHolder(1L, "place-3", "checkin-3");
        assertEquals(CityCoinService.Outcome.CREDITED, clipped.outcome());
        assertEquals(10, clipped.amount(), "clipped to the daily cap");
        assertEquals(CityCoinService.Outcome.CAPPED, c.creditHolder(1L, "place-3", "checkin-4").outcome());
        assertEquals(CityCoinService.Outcome.CREDITED, c.creditHolder(2L, "place-3", "checkin-4").outcome(),
                "a capped attempt writes no ledger row, so its reference stays free");
        assertEquals(CityCoinService.Outcome.INVALID, c.creditHolder(null, "p", "r").outcome());
        assertEquals(CityCoinService.Outcome.INVALID, c.creditHolder(1L, " ", "r").outcome());
        assertEquals(CityCoinService.Outcome.INVALID, c.creditHolder(1L, "p", "r".repeat(81)).outcome());

        Map<String, Object> me = c.summary(1L);
        assertEquals(70L, me.get("balance"));
        assertEquals(70L, me.get("todayEarned"));
        assertEquals(Boolean.TRUE, me.get("nonCashable"));
        assertEquals(3, ((List<?>) me.get("history")).size());
        assertEquals(0L, c.summary(42L).get("balance"));
    }
}
