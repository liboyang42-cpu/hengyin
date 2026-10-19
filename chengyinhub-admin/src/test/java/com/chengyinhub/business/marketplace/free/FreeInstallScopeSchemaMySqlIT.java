package com.chengyinhub.business.marketplace.free;
import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
/** Exact Oracle MySQL metadata checks for the real-editor sidecar; never H2 substitution. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
class FreeInstallScopeSchemaMySqlIT {
    private final IsolatedMySqlSchema mysql=new IsolatedMySqlSchema();private JdbcTemplate jdbc;
    @BeforeEach void open() throws Exception {jdbc=new JdbcTemplate(mysql.open("freeinstschema"));mysql.apply("content_draft_im_poll_20260828");mysql.apply("w18_content_draft_install_scope_20261003");}
    @AfterEach void close() throws Exception {mysql.close();}
    private void rejected(){assertThrows(AssertionError.class,()->mysql.apply("w18_content_draft_install_scope_20261003"));}
    @Test void exactRerunPreservesRealDraftScope() throws Exception {
        jdbc.update("INSERT INTO content_draft(id,owner_member_id,business_type,client_draft_key,payload_json,payload_hash,updated_by_device) VALUES(91,42,'TOPIC','draft','{}',repeat('1',64),'test')");
        jdbc.update("INSERT INTO w18_content_draft_install_scope VALUES(91,1,42,repeat('1',64),'region')");mysql.apply("w18_content_draft_install_scope_20261003");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM w18_content_draft_install_scope",Integer.class));
    }
    @Test void reversedVersionPrimaryKeyFailsClosed(){
        jdbc.execute("ALTER TABLE w18_content_draft_install_scope DROP FOREIGN KEY fk_w18_install_scope_content_draft");
        jdbc.execute("ALTER TABLE w18_content_draft_install_scope DROP PRIMARY KEY, ADD PRIMARY KEY(draft_version,draft_id)");
        jdbc.execute("ALTER TABLE w18_content_draft_install_scope ADD CONSTRAINT fk_w18_install_scope_content_draft FOREIGN KEY(draft_id) REFERENCES content_draft(id)");rejected();
    }
    @Test void nullableOwnerFailsClosed(){jdbc.execute("ALTER TABLE w18_content_draft_install_scope MODIFY owner_member_id BIGINT NULL");rejected();}
    @Test void caseInsensitiveHashFailsClosed(){jdbc.execute("ALTER TABLE w18_content_draft_install_scope MODIFY payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL");rejected();}
    @Test void defaultRegionFailsClosed(){jdbc.execute("ALTER TABLE w18_content_draft_install_scope MODIFY region VARCHAR(128) NOT NULL DEFAULT 'invented'");rejected();}
    @Test void cascadeDeleteFailsClosed(){
        jdbc.execute("ALTER TABLE w18_content_draft_install_scope DROP FOREIGN KEY fk_w18_install_scope_content_draft");
        jdbc.execute("ALTER TABLE w18_content_draft_install_scope ADD CONSTRAINT fk_w18_install_scope_content_draft FOREIGN KEY(draft_id) REFERENCES content_draft(id) ON DELETE CASCADE");rejected();
    }
    @Test void wrongEditorIdentityForeignKeyFailsClosed(){
        jdbc.execute("CREATE TABLE fixture_wrong_draft(id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("ALTER TABLE w18_content_draft_install_scope DROP FOREIGN KEY fk_w18_install_scope_content_draft");
        jdbc.execute("ALTER TABLE w18_content_draft_install_scope ADD CONSTRAINT fk_w18_install_scope_content_draft FOREIGN KEY(draft_id) REFERENCES fixture_wrong_draft(id)");rejected();
    }
}
