package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** All 35 shared assertions on actual MySQL/XML. Authority remains a labelled test fixture. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class ModuleReservationMySqlIT extends ModuleInstallReservationTransactionTest {
    private final IsolatedMySqlSchema mysql = new IsolatedMySqlSchema();
    @Override protected DriverManagerDataSource createDataSource() throws Exception { return mysql.open("hold"); }
    @Override protected void applySchema(JdbcTemplate jdbc) throws Exception { mysql.apply("w18_module_install_reservation_20261002"); mysql.assertModuleSchema(4,2); }
    @Override protected String snapshotIsolation() { return "REPEATABLE READ"; }
    @Override protected void setDefaultIsolation(String isolation) { mysql.isolation(isolation); }
    @AfterEach void removeMysqlScratch() throws Exception { mysql.close(); }
}
