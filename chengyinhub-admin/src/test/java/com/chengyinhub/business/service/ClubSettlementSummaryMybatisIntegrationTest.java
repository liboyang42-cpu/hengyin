package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.web.controller.api.ApiClubSettlementController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClubSettlementSummaryMybatisIntegrationTest {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("club_summary_it");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ApiClubSettlementController api;
    private ICashWithdrawService withdrawals;

    @BeforeAll void setup() throws Exception {
        new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("CREATE DATABASE " + mysql.databaseName());
        dataSource = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(dataSource);
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        // cms_topic:CU-C-40 汇总行 left join 主题名,真库 schema 要有这张表
        for (String table : new String[]{"coop_settlement", "coop_settlement_adjustment", "ums_member_balance_detail", "cms_topic"}) {
            int start = base.indexOf("CREATE TABLE `" + table + "` (");
            jdbc.execute(base.substring(start, base.indexOf(";", start)));
        }
        Configuration config = new Configuration(new Environment("club-summary", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        config.addMapper(CoopSettlementMapper.class);
        String resource = "mapper/business/CoopSettlementMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        CoopSettlementMapper mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(CoopSettlementMapper.class);
        IClubService clubs = mock(IClubService.class);
        Club club = new Club(); club.setId(9L); club.setMemberId(1L);
        when(clubs.selectClubById(9L)).thenReturn(club);
        withdrawals = mock(ICashWithdrawService.class);
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.resolve(1L, 9L, null)).thenReturn(new ClubAccessContext(
                1L, 9L, 1L, null, Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        api = new ApiClubSettlementController(mapper, clubs, access, withdrawals) {
            @Override public Long getAppUserId() { return 1L; }
        };
    }

    @BeforeEach void resetRows() {
        jdbc.update("DELETE FROM coop_settlement_adjustment");
        jdbc.update("DELETE FROM ums_member_balance_detail");
        jdbc.update("DELETE FROM coop_settlement");
    }

    @AfterAll void cleanup() {
        if (dataSource != null) new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("DROP DATABASE " + mysql.databaseName());
    }

    @Test void voidSettlementKeepsHistoryButNeverPromisesPayment() {
        settlement(1, 2, "9.99");
        Map<String, Object> data = summary();
        Map<String, Object> row = row(data, 0);
        assertEquals("void", row.get("status"));
        assertEquals("¥ 9.99", row.get("amountText"));
        assertEquals("已作废，不再打款", row.get("paidText"));
        assertEquals("¥ 0.00", data.get("settledAmountText"));
        verifyNoInteractions(withdrawals);
    }

    @Test void missingPayoutMakesWholeTotalUnverifiedIncludingZeroAmount() {
        settlement(1, 1, "10.00"); payout(1, "10.00");
        settlement(2, 1, "0.00");
        Map<String, Object> data = summary();
        assertNull(data.get("settledAmountText"));
        assertEquals("unverified", data.get("settledAmountStatus"));
        assertEquals(1, data.get("unverifiedSettledCount"));
        assertNull(byId(data, 2).get("amountText"));
        assertEquals("unverified", byId(data, 2).get("amountStatus"));
        assertEquals("¥ 10.00", byId(data, 1).get("amountText"));
        verifyNoInteractions(withdrawals);
    }

    @Test void onlyExactLiveIncomeLedgerForRecipientProvesPayout() {
        settlement(1, 1, "10.00"); payout(1, "10.00");
        for (String mutation : new String[]{
                "member_id=2", "change_type=2", "del_flag=1",
                "event_type=4", "change_reason='其他余额收入'", "change_balance=NULL"}) {
            jdbc.update("DELETE FROM ums_member_balance_detail");
            payout(1, "10.00");
            jdbc.update("UPDATE ums_member_balance_detail SET " + mutation);
            assertNull(summary().get("settledAmountText"), mutation);
        }
        jdbc.update("DELETE FROM ums_member_balance_detail");
        payout(1, "10.00");
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> payout(1, "10.00"));
        assertEquals("¥ 10.00", summary().get("settledAmountText"));
    }

    @Test void emptyAndScopedRowsNeverIncludeOtherBeneficiariesOrVoids() {
        assertEquals("¥ 0.00", summary().get("settledAmountText"));
        settlement(1, 1, "10.00"); payout(1, "8.00");
        settlement(2, 1, "20.00"); payout(2, "21.00");
        settlement(3, 1, "99.00"); payout(3, "99.00");
        jdbc.update("UPDATE coop_settlement SET beneficiary_id=99 WHERE id=3");
        settlement(4, 1, "88.00");
        jdbc.update("UPDATE coop_settlement SET beneficiary_role='MERCHANT' WHERE id=4");
        settlement(5, 1, "77.00");
        jdbc.update("UPDATE coop_settlement SET del_flag=1 WHERE id=5");
        settlement(6, 2, "6.00"); adjustment(6, "-8.00", "EXECUTED");
        Map<String, Object> data = summary();
        assertEquals("¥ 29.00", data.get("settledAmountText"));
        assertEquals(3, ((List<?>) data.get("topics")).size());
        assertEquals("¥ -2.00", byId(data, 6).get("netAmountText"));
        assertEquals("void", byId(data, 6).get("status"));
        verifyNoInteractions(withdrawals);
    }

    @Test void unknownStatusFailsInsteadOfPromisingPayment() {
        settlement(1, 9, "5.00");
        assertNotEquals(200, api.summary(Collections.singletonMap("clubId", 9L)).get("code"));
    }

    @Test void executedAdjustmentsAndActualPayoutRemainDistinctAfterLaterAdjustment() {
        settlement(1, 1, "12.34"); adjustment(1, "-2.34", "EXECUTED"); payout(1, "10.00");
        settlement(2, 0, "7.00"); adjustment(2, "1.00", "EXECUTED");
        adjustment(2, "99.00", "PENDING");
        Map<String, Object> data = summary();
        assertEquals("¥ 10.00", data.get("settledAmountText"));
        assertEquals("¥ 10.00", byId(data, 1).get("amountText"));
        assertEquals("¥ 8.00", byId(data, 2).get("amountText"));
        assertEquals("¥ 12.34", byId(data, 1).get("originalAmountText"));
        assertEquals("¥ -2.34", byId(data, 1).get("executedAdjustmentText"));
        adjustment(1, "-1.00", "EXECUTED");
        data = summary();
        assertEquals("¥ 10.00", data.get("settledAmountText"));
        assertEquals("¥ 10.00", byId(data, 1).get("amountText"));
        assertEquals("¥ 9.00", byId(data, 1).get("netAmountText"));
        assertEquals("¥ -3.34", byId(data, 1).get("executedAdjustmentText"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_balance_detail", Integer.class));
        verifyNoInteractions(withdrawals);
    }

    private void adjustment(long id, String amount, String status) {
        jdbc.update("INSERT INTO coop_settlement_adjustment(settlement_id,claim_id,entry_kind,event_key,amount,status,create_time) VALUES(?,?,'ADJUSTMENT',?,?,?,NOW())", id, id, UUID.randomUUID().toString(), amount, status);
    }

    private void payout(long id, String amount) {
        jdbc.update("INSERT INTO ums_member_balance_detail(member_id,change_balance,event_type,event_id,change_type,del_flag,change_reason,create_time) VALUES(1,?,5,?,1,0,'Coop结算打款 - 主题10',NOW())", amount, id);
    }

    @SuppressWarnings("unchecked") private Map<String, Object> byId(Map<String, Object> data, long id) {
        return ((List<Map<String, Object>>) data.get("topics")).stream()
                .filter(row -> Long.valueOf(id).equals(row.get("id"))).findFirst().orElseThrow(AssertionError::new);
    }

    private void settlement(long id, int status, String amount) {
        jdbc.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,beneficiary_role,beneficiary_id,amount,status,create_time) VALUES(?,10,1,'CLUB',9,?,?,NOW())", id, amount, status);
    }

    @SuppressWarnings("unchecked") private Map<String, Object> summary() {
        AjaxResult result = api.summary(Collections.singletonMap("clubId", 9L));
        assertEquals(200, result.get("code"));
        return (Map<String, Object>) result.get("data");
    }

    @SuppressWarnings("unchecked") private Map<String, Object> row(Map<String, Object> data, int index) {
        return ((List<Map<String, Object>>) data.get("topics")).get(index);
    }
}
