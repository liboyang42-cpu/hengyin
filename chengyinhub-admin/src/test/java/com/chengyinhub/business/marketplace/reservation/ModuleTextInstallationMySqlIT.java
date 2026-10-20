package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** All 38 shared assertions, including independent review probes, on unmodified migrations/MySQL/XML.
 * Buyer, grant, release, target and resource authority are test fixtures, never production adapters. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class ModuleTextInstallationMySqlIT extends ModuleTextDraftInstallationTransactionTest {
    private final IsolatedMySqlSchema mysql = new IsolatedMySqlSchema();
    @Override protected DriverManagerDataSource createDataSource() throws Exception { return mysql.open("install"); }
    @Override protected void applySchema(JdbcTemplate jdbc) throws Exception {
        mysql.apply("w18_module_install_reservation_20261002"); mysql.apply("w18_owned_text_draft_install_20261002"); mysql.assertModuleSchema(7,6);
    }
    @AfterEach void removeMysqlScratch() throws Exception { mysql.close(); }
}
