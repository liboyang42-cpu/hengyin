package com.chengyinhub.business.marketplace.free;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Actual InnoDB schema/mapper execution; immutable publisher ingestion remains fixture-only. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class FreeModuleClaimMySqlIT extends FreeModuleClaimTransactionTest {
    private final IsolatedMySqlSchema mysql=new IsolatedMySqlSchema();
    @Override protected DriverManagerDataSource createDataSource() throws Exception {return mysql.open("freeclaim");}
    @Override protected void applySchema(JdbcTemplate jdbc) throws Exception {mysql.apply("w18_free_claim_registry_20261003");}
    @AfterEach void closeSchema() throws Exception {mysql.close();}
}
