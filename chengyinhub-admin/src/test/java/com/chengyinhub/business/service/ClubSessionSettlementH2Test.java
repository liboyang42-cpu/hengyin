package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiClubSettlementController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F-16(j2 走查):俱乐部场次结算后「可提现 ¥151.32」对,但俱乐部结算页写「还没有平台分润记录」——
 * 场次的钱由 settleActivity 直接入主理人余额(event_type=3)与平台(event_type=7),从不进 coop_settlement。
 * 这里用 H2 真跑 CoopSettlementMapper.xml,按 j2 的真实流水(¥156.00 → 主理人 ¥151.32、平台 ¥4.68)
 * 读回场次行;再按转让后的新主理人读,确认前任的那场仍列出并写明入了原主理人账户。
 * 负控:提现申请同样是 event_type=3(change_type=2),不得混进到账;别的俱乐部的场次不得出现。
 */
class ClubSessionSettlementH2Test {
    private static final long CLUB = 9L, FORMER = 990005L, CURRENT = 990006L, PLATFORM = 9001L;
    private JdbcTemplate jdbc;
    private CoopSettlementMapper settlements;
    private Club club;
    private long viewer;

    @BeforeEach
    void setUp() throws Exception {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:club_session_settle_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table coop_settlement (id bigint primary key, topic_id bigint, merchant_id bigint,"
                + " payee_type varchar(20), beneficiary_role varchar(20), beneficiary_id bigint, amount decimal(12,2),"
                + " status int, payable_time datetime, settle_time datetime, payout_time datetime,"
                + " del_flag int default 0, create_time datetime)");
        jdbc.execute("create table coop_settlement_adjustment (settlement_id bigint, amount decimal(12,2), status varchar(20))");
        jdbc.execute("create table ums_member_balance_detail (id bigint auto_increment primary key, member_id bigint,"
                + " change_balance decimal(12,2), change_type int, del_flag int, coop_settlement_payout_id bigint,"
                + " event_type int, event_id bigint, create_time datetime)");
        jdbc.execute("create table cms_topic (id bigint primary key, name varchar(255), del_flag int default 0)");
        jdbc.execute("create table cms_activity (id bigint primary key, name varchar(255), club_id bigint, start_date datetime)");
        jdbc.execute("create table settlement_detail (id bigint auto_increment primary key, source_type varchar(32),"
                + " source_id bigint, receiver_type varchar(32), receiver_id bigint, settlement_amount decimal(12,2), status int)");
        jdbc.execute("create table refund_application (id bigint primary key, source_type varchar(32), source_id bigint)");
        jdbc.execute("create table cms_registration (id bigint primary key, owner_type int, owner_id bigint)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopSettlementMapper.xml"));
        org.apache.ibatis.session.SqlSessionFactory sqlFactory = factory.getObject();
        settlements = new SqlSessionTemplate(sqlFactory).getMapper(CoopSettlementMapper.class);
        // 应用数据源开着 Druid wall:新查询原样过 wall,不然上线后整页 500。
        String sql = sqlFactory.getConfiguration().getMappedStatement(CoopSettlementMapper.class.getName()
                + ".selectClubSessionSettlements").getBoundSql(Collections.singletonMap("clubId", CLUB)).getSql();
        com.alibaba.druid.wall.WallConfig wallConfig = new com.alibaba.druid.wall.WallConfig(
                com.alibaba.druid.wall.spi.MySqlWallProvider.DEFAULT_CONFIG_DIR);
        wallConfig.setMultiStatementAllow(false);
        assertEquals(java.util.List.of(), new com.alibaba.druid.wall.spi.MySqlWallProvider(wallConfig).check(sql).getViolations());

        jdbc.execute("insert into cms_activity values (9, '人民广场 · 喷泉报时', " + CLUB + ", '2026-11-04 13:40:00')");
        jdbc.execute("insert into cms_activity values (10, '别的俱乐部场次', 77, '2026-11-04 13:40:00')");
        flow(FORMER, "151.32", 1, 3, 9);
        flow(PLATFORM, "4.68", 1, 7, 9);
        flow(FORMER, "151.32", 2, 3, 9);   // 提现申请:event_type=3 但 change_type=2(支出),event_id 是提现单号
        flow(FORMER, "88.00", 1, 3, 10);
        flow(PLATFORM, "2.72", 1, 7, 10);
        club = new Club(); club.setId(CLUB); club.setMemberId(FORMER);
    }

    private void flow(long member, String amount, int changeType, int eventType, long eventId) {
        jdbc.update("insert into ums_member_balance_detail (member_id, change_balance, change_type, del_flag,"
                + " event_type, event_id, create_time) values (?, " + amount + ", ?, 0, ?, ?, now())",
                member, changeType, eventType, eventId);
    }

    private ApiClubSettlementController api() {
        IClubService clubs = mock(IClubService.class);
        when(clubs.selectClubById(CLUB)).thenReturn(club);
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.resolve(viewer, CLUB, null)).thenReturn(new ClubAccessContext(viewer, CLUB, club.getMemberId(), null,
                new LinkedHashSet<>(Arrays.asList(ClubRoleCode.CLUB_OWNER))));
        return new ApiClubSettlementController(settlements, clubs, access, mock(ICashWithdrawService.class)) {
            @Override public Long getAppUserId() { return viewer; }
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sessions(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("sessions");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary() {
        AjaxResult result = api().summary(Collections.singletonMap("clubId", CLUB));
        assertEquals(200, result.get(AjaxResult.CODE_TAG), String.valueOf(result));
        return (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
    }

    @Test
    void ownerSeesSessionGrossFeeAndNetFromRealSettlementFlows() {
        viewer = FORMER;
        List<Map<String, Object>> rows = sessions(summary());
        assertEquals(1, rows.size(), "只列本俱乐部的场次");
        Map<String, Object> row = rows.get(0);
        assertEquals(9L, row.get("activityId"));
        assertEquals("人民广场 · 喷泉报时", row.get("name"));
        assertEquals("11月4日", row.get("dateText"));
        assertEquals("¥156.00", row.get("grossText"));
        assertEquals("¥4.68", row.get("feeText"));
        assertEquals("3%", row.get("feeRateText"));
        assertEquals("¥151.32", row.get("netText"), "提现那笔支出不得混进到账");
        assertEquals("已入你的账户", row.get("payeeText"));
    }

    private void refundAfterSettlement(long refundId, long registrationId, long activityId,
                                       String merchantClawback, String platformClawback, int status) {
        jdbc.update("insert into cms_registration values (?, 2, ?)", registrationId, activityId);
        jdbc.update("insert into refund_application values (?, 'registration', ?)", refundId, registrationId);
        jdbc.update("insert into settlement_detail (source_type, source_id, receiver_type, receiver_id, settlement_amount, status)"
                + " values ('refund', ?, 'merchant', ?, -" + merchantClawback + ", ?)", refundId, FORMER, status);
        jdbc.update("insert into settlement_detail (source_type, source_id, receiver_type, receiver_id, settlement_amount, status)"
                + " values ('refund', ?, 'platform', 0, -" + platformClawback + ", ?)", refundId, status);
    }

    @Test
    void refundAfterSettlementIsSubtractedFromTheSessionRow() {
        // 第十一轮 h4 L7:¥39 票结算后退一半(¥19.50),冲销明细按 3% 分到商户 −18.92、平台 −0.58。
        // 场次行要变成「已收 − 已退」:销售 136.50、服务费 4.10、到账 132.40;别的场次的退款与未落定冲销不算进来。
        viewer = FORMER;
        refundAfterSettlement(501, 74, 9, "18.92", "0.58", 1);
        refundAfterSettlement(502, 80, 10, "40.00", "1.00", 1);   // 别的俱乐部场次
        refundAfterSettlement(503, 75, 9, "5.00", "0.10", 0);     // 未落定(status=0)不算
        Map<String, Object> row = sessions(summary()).get(0);
        assertEquals("¥136.50", row.get("grossText"));
        assertEquals("¥4.10", row.get("feeText"));
        assertEquals("¥132.40", row.get("netText"));
    }

    @Test
    void afterOwnershipTransferTheNewOwnerSeesWhereTheFormerOwnersMoneyWent() {
        club.setMemberId(CURRENT);
        viewer = CURRENT;
        Map<String, Object> row = sessions(summary()).get(0);
        assertEquals("¥151.32", row.get("netText"));
        assertEquals("已入原主理人账户", row.get("payeeText"));
    }
}
