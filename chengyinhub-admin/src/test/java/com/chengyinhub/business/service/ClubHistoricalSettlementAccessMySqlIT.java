package com.chengyinhub.business.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Same seven behavior assertions on real MySQL; uses only an exact task-owned disposable schema. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class ClubHistoricalSettlementAccessMySqlIT extends ClubHistoricalSettlementAccessH2Test {
    private MysqlIntegrationTestConfig mysql;
    private boolean created;

    @Override
    protected DataSource newDataSource() {
        mysql = MysqlIntegrationTestConfig.fromEnvironment("r14_access_" + UUID.randomUUID().toString().substring(0, 8));
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()", String.class)),
                "Historical settlement isolation requires real MySQL 8+");
        admin.execute("CREATE DATABASE " + mysql.databaseName());
        created = true;
        return new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
    }

    // Registry precheck counts declarations in this source; keep each inherited assertion explicitly registered.
    @Override @Test
    void formerPrincipalReadsOnlyFrozenOwnRowsAndOwnPartOfSharedSession() {
        super.formerPrincipalReadsOnlyFrozenOwnRowsAndOwnPartOfSharedSession();
    }

    @Override @Test
    void newPrincipalPersonalViewExcludesFormerIncomeAndStrangersHaveNoAccess() {
        super.newPrincipalPersonalViewExcludesFormerIncomeAndStrangersHaveNoAccess();
    }

    @Override @Test
    void wrongClubMissingNegativeAndAnonymousRequestsFailClosed() {
        super.wrongClubMissingNegativeAndAnonymousRequestsFailClosed();
    }

    @Override @Test
    void transferTwiceNeverReassignsAmountsOrPayeesAndFormerWithdrawalStaysCustomerService() {
        super.transferTwiceNeverReassignsAmountsOrPayeesAndFormerWithdrawalStaysCustomerService();
    }

    @Override @Test
    void executedRefundsUseHistoricalReceiverNotTheOtherPayee() {
        super.executedRefundsUseHistoricalReceiverNotTheOtherPayee();
    }

    @Override @Test
    void clubSelfPlayFrozenInitiatorIncomeIsVisibleWithoutInventingClubBeneficiary() {
        super.clubSelfPlayFrozenInitiatorIncomeIsVisibleWithoutInventingClubBeneficiary();
    }

    @Override @Test
    void currentPrincipalRequestCannotWithdrawFormerPrincipalsPaidAmount() {
        super.currentPrincipalRequestCannotWithdrawFormerPrincipalsPaidAmount();
    }

    @AfterEach
    void dropTaskSchema() {
        if (created) {
            new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                    .execute("DROP DATABASE " + mysql.databaseName());
        }
    }
}
