package com.chengyinhub.business.home;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit scratch-schema runner for the unchanged mapper plus retention concurrency cases. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class PrivateHomeRetentionMySqlIT extends PrivateHomeRetentionTest {
    private final IsolatedMySqlSchema schema=new IsolatedMySqlSchema();
    @Override DataSource createDataSource() throws Exception {return schema.open("home_retention");}
    @Override void createSchema() throws Exception {schema.apply("20261002_private_player_home");}
    @AfterEach void cleanupScratchSchema() throws Exception {schema.close();}
}
