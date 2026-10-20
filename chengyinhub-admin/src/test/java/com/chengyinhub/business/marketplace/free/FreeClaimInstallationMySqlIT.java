package com.chengyinhub.business.marketplace.free;
import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
/** Real InnoDB + unchanged migrations + persisted authority. Reviewed ingestion is test-seeded only. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class FreeClaimInstallationMySqlIT extends FreeClaimInstallationTransactionTest {
    private final IsolatedMySqlSchema mysql=new IsolatedMySqlSchema();
    @Override protected DriverManagerDataSource createDataSource() throws Exception{return mysql.open("freeinstall");}
    @Override protected void applySchema(JdbcTemplate jdbc) throws Exception {
        mysql.apply("content_draft_im_poll_20260828");mysql.apply("w18_free_claim_registry_20261003");
        mysql.apply("w18_module_install_reservation_20261002");mysql.apply("w18_owned_text_draft_install_20261002");
        mysql.apply("w18_content_draft_install_scope_20261003");
    }
    @AfterEach void closeSchema() throws Exception {mysql.close();}
}
