package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.web.controller.api.ApiClubSettlementController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes the production mapper: membership changes never change frozen payees or ledger ownership. */
class ClubHistoricalSettlementAccessH2Test {
    private JdbcTemplate jdbc;
    private CoopSettlementMapper mapper;
    private ApiClubSettlementController api;
    private ICashWithdrawService withdrawals;
    private Club club;
    private long viewer = 11L;

    @BeforeEach
    void setup() throws Exception {
        javax.sql.DataSource ds = newDataSource();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table club (id bigint primary key, name varchar(100), member_id bigint)");
        jdbc.execute("insert into club values (7, '原俱乐部', 22), (8, '另一俱乐部', 33)");
        jdbc.execute("create table coop_settlement (id bigint primary key, topic_id bigint, merchant_id bigint,"
                + " payee_type varchar(20), line_type varchar(32), beneficiary_role varchar(20), beneficiary_id bigint, amount decimal(12,2),"
                + " status int, payable_time datetime, settle_time datetime, payout_time datetime,"
                + " del_flag int default 0, create_time datetime)");
        jdbc.execute("create table coop_settlement_adjustment (settlement_id bigint, amount decimal(12,2), status varchar(20))");
        jdbc.execute("create table ums_member_balance_detail (id bigint auto_increment primary key, member_id bigint,"
                + " change_balance decimal(12,2), change_type int, del_flag int, coop_settlement_payout_id bigint,"
                + " event_type int, event_id bigint, create_time datetime)");
        jdbc.execute("create table cms_topic (id bigint primary key, name varchar(100), club_id bigint)");
        jdbc.execute("create table cms_activity (id bigint primary key, name varchar(100), club_id bigint, start_date datetime)");
        jdbc.execute("create table settlement_detail (source_type varchar(32), source_id bigint, receiver_type varchar(32),"
                + " receiver_id bigint, settlement_amount decimal(12,2), status int)");
        jdbc.execute("create table refund_application (id bigint primary key, source_type varchar(32), source_id bigint)");
        jdbc.execute("create table cms_registration (id bigint primary key, owner_type int, owner_id bigint)");
        jdbc.execute("insert into cms_topic values (1, '原主题', 7), (2, '新主题', 7)");
        jdbc.execute("insert into coop_settlement (id, topic_id, merchant_id, beneficiary_role, beneficiary_id, amount, status, create_time)"
                + " values (1, 1, 11, 'CLUB', 7, 12.00, 0, now()), (2, 2, 22, 'CLUB', 7, 99.00, 0, now()),"
                + " (3, 2, 33, 'CLUB', 8, 88.00, 0, now())");
        jdbc.execute("insert into cms_activity values (101, '同场分笔', 7, now()), (102, '别的场次', 8, now())");
        flow(11, 3, 1, 101, "30.00");
        flow(22, 3, 1, 101, "70.00");
        flow(11, 3, 2, 101, "999.00"); // Withdrawal expense is not earnings.
        flow(33, 3, 1, 102, "88.00");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopSettlementMapper.xml"));
        org.apache.ibatis.session.SqlSessionFactory sessionFactory = factory.getObject();
        mapper = new SqlSessionTemplate(sessionFactory).getMapper(CoopSettlementMapper.class);
        for (String statement : List.of("selectPersonalClubSummary", "selectPersonalClubSessions", "selectPersonalSettlementClubs")) {
            Map<String, Object> params = Map.of("memberId", 11L, "clubId", 7L);
            String sql = sessionFactory.getConfiguration().getMappedStatement(CoopSettlementMapper.class.getName()
                    + "." + statement).getBoundSql(params).getSql();
            com.alibaba.druid.wall.WallConfig config = new com.alibaba.druid.wall.WallConfig(
                    com.alibaba.druid.wall.spi.MySqlWallProvider.DEFAULT_CONFIG_DIR);
            config.setMultiStatementAllow(false);
            assertTrue(new com.alibaba.druid.wall.spi.MySqlWallProvider(config).check(sql).getViolations().isEmpty(), statement);
        }
        club = new Club(); club.setId(7L); club.setMemberId(22L);
        IClubService clubs = mock(IClubService.class);
        when(clubs.selectClubById(7L)).thenReturn(club);
        withdrawals = mock(ICashWithdrawService.class);
        api = new ApiClubSettlementController(mapper, clubs,
                mock(com.chengyinhub.business.access.club.IClubAccessService.class), withdrawals) {
            @Override public Long getAppUserId() { return viewer; }
        };
    }

    protected javax.sql.DataSource newDataSource() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:historical_club_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }

    private void flow(long member, int event, int change, long activity, String amount) {
        jdbc.update("insert into ums_member_balance_detail (member_id, change_balance, change_type, del_flag, event_type, event_id, create_time)"
                + " values (?, ?, ?, 0, ?, ?, now())", member, new BigDecimal(amount), change, event, activity);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> body) {
        Map<String, Object> result = api.summary(body);
        assertEquals(200, result.get("code"), result.toString());
        return (Map<String, Object>) result.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }

    @Test
    void formerPrincipalReadsOnlyFrozenOwnRowsAndOwnPartOfSharedSession() {
        Map<String, Object> data = summary(Map.of("clubId", 7L, "memberId", 22L, "payeeId", 22L));
        assertEquals(true, data.get("personal"));
        assertEquals(false, data.get("isOwner"));
        assertEquals(List.of(1L), rows(data, "topics").stream().map(row -> row.get("id")).toList());
        Map<String, Object> session = rows(data, "sessions").get(0);
        assertEquals("¥30.00", session.get("netText"));
        assertFalse(session.containsKey("grossText"));
        assertFalse(session.containsKey("feeText"));
        assertFalse(session.containsKey("merchantFeeText"));
        assertEquals(List.of(7L), mapper.selectPersonalSettlementClubs(11L).stream().map(row -> row.get("clubId")).toList());
        verifyNoInteractions(withdrawals);
    }

    @Test
    void newPrincipalPersonalViewExcludesFormerIncomeAndStrangersHaveNoAccess() {
        viewer = 22L;
        Map<String, Object> data = summary(Map.of("clubId", 7L, "personal", true));
        assertEquals(List.of(2L), rows(data, "topics").stream().map(row -> row.get("id")).toList());
        assertEquals("¥70.00", rows(data, "sessions").get(0).get("netText"));
        viewer = 44L;
        assertNotEquals(200, api.summary(Map.of("clubId", 7L)).get("code"));
        assertNotEquals(200, api.withdraw(Map.of("clubId", 7L)).get("code"));
        assertEquals(Collections.emptyList(), api.mine().get("data"));
        verifyNoInteractions(withdrawals);
    }

    @Test
    void wrongClubMissingNegativeAndAnonymousRequestsFailClosed() {
        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of(), Map.of("clubId", -7L),
                Map.of("clubId", 0L), Map.of("clubId", 8L))) {
            assertNotEquals(200, api.summary(body).get("code"));
            assertNotEquals(200, api.withdraw(body).get("code"));
        }
        assertNotEquals(200, api.summary(null).get("code"));
        viewer = 0L;
        assertNotEquals(200, api.summary(Map.of("clubId", 7L)).get("code"));
        assertNotEquals(200, api.mine().get("code"));
        verifyNoInteractions(withdrawals);
    }

    @Test
    void transferTwiceNeverReassignsAmountsOrPayeesAndFormerWithdrawalStaysCustomerService() {
        List<Map<String, Object>> before = jdbc.queryForList("select id, merchant_id, amount from coop_settlement order by id");
        for (long principal : new long[]{22L, 33L}) {
            club.setMemberId(principal);
            jdbc.update("update club set member_id=? where id=7", principal);
            assertEquals("¥12.00", rows(summary(Map.of("clubId", 7L)), "topics").get(0).get("amountText"));
            ReflectionTestUtils.setField(api, "fastWithdrawEnabled", true);
            assertEquals("请联系平台客服处理本人历史结算提现", api.withdraw(Map.of("clubId", 7L, "payeeId", principal)).get("msg"));
        }
        assertEquals(before, jdbc.queryForList("select id, merchant_id, amount from coop_settlement order by id"));
        assertEquals(new BigDecimal("30.00"), jdbc.queryForObject(
                "select change_balance from ums_member_balance_detail where member_id=11 and change_type=1", BigDecimal.class));
        verifyNoInteractions(withdrawals);
    }

    @Test
    void executedRefundsUseHistoricalReceiverNotTheOtherPayee() {
        jdbc.execute("insert into cms_registration values (1, 2, 101)");
        jdbc.execute("insert into refund_application values (1, 'registration', 1)");
        jdbc.execute("insert into settlement_detail values ('refund', 1, 'merchant', 11, -5.00, 1),"
                + " ('refund', 1, 'merchant', 22, -20.00, 1), ('refund', 1, 'merchant', 11, -8.00, 0)");
        assertEquals("¥25.00", rows(summary(Map.of("clubId", 7L)), "sessions").get(0).get("netText"));
    }

    @Test
    void clubSelfPlayFrozenInitiatorIncomeIsVisibleWithoutInventingClubBeneficiary() {
        jdbc.execute("insert into coop_settlement (id, topic_id, merchant_id, payee_type, line_type, amount, status, create_time)"
                + " values (4, 1, 11, 'initiator', 'INITIATOR_MARGIN', 56.26, 0, now()),"
                + " (5, 1, 22, 'initiator', 'INITIATOR_MARGIN', 100.00, 0, now()),"
                + " (6, 1, 11, 'merchant', 'MERCHANT_VERIFY', 9.00, 0, now())");
        List<Map<String, Object>> topics = rows(summary(Map.of("clubId", 7L)), "topics");
        assertEquals(2, topics.size());
        assertTrue(topics.stream().anyMatch(row -> Long.valueOf(4L).equals(row.get("id"))
                && "¥56.26".equals(row.get("amountText"))));
        viewer = 22L;
        List<Map<String, Object>> other = rows(summary(Map.of("clubId", 7L, "personal", true)), "topics");
        assertFalse(other.stream().anyMatch(row -> Long.valueOf(4L).equals(row.get("id"))));
        jdbc.execute("delete from coop_settlement where id != 4");
        jdbc.execute("delete from ums_member_balance_detail");
        assertEquals(7L, mapper.selectPersonalSettlementClubs(11L).get(0).get("clubId"));
        assertTrue(mapper.selectPersonalSettlementClubs(22L).isEmpty());
    }

    @Test
    void currentPrincipalRequestCannotWithdrawFormerPrincipalsPaidAmount() {
        viewer = 22L;
        jdbc.execute("update coop_settlement set status=1");
        jdbc.execute("insert into ums_member_balance_detail (member_id, change_balance, change_type, del_flag, coop_settlement_payout_id)"
                + " values (11, 12.00, 1, 0, 1), (22, 99.00, 1, 0, 2)");
        ReflectionTestUtils.setField(api, "fastWithdrawEnabled", true);
        assertEquals(200, api.withdraw(new HashMap<>(Map.of("clubId", 7L, "memberId", 11L))).get("code"));
        verify(withdrawals).withdraw(22L, new BigDecimal("99.00"));
        verifyNoMoreInteractions(withdrawals);
    }
}
