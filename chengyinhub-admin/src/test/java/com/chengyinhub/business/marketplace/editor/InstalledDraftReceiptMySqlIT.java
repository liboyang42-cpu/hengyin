package com.chengyinhub.business.marketplace.editor;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Original MySQL migrations and the same SELECT/ownership/staleness assertions, never H2 fallback. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class InstalledDraftReceiptMySqlIT extends InstalledDraftReceiptReaderTest {
    final IsolatedMySqlSchema mysql=new IsolatedMySqlSchema();
    @Override protected DriverManagerDataSource createDataSource() throws Exception{return mysql.open("installreceipt");}
    @Override protected void applySchema(JdbcTemplate jdbc) throws Exception {
        mysql.apply("content_draft_im_poll_20260828");mysql.apply("w18_module_install_reservation_20261002");
        mysql.apply("w18_owned_text_draft_install_20261002");mysql.apply("w18_content_draft_install_scope_20261003");
        mysql.apply("w18_owned_text_receipt_read_index_20261003");
    }
    @AfterEach void closeSchema() throws Exception {mysql.close();}
}
