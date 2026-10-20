package com.chengyinhub.business.marketplace.free;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real Oracle MySQL metadata and repeated-migration negatives. Never uses the H2 adaptation. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class FreeModuleSchemaMySqlIT {
    private final IsolatedMySqlSchema mysql=new IsolatedMySqlSchema();
    private JdbcTemplate jdbc;
    @BeforeEach void open() throws Exception {jdbc=new JdbcTemplate(mysql.open("freeclaimschema"));mysql.apply("w18_free_claim_registry_20261003");}
    @AfterEach void close() throws Exception {mysql.close();}
    private void rejected() {assertThrows(AssertionError.class,()->mysql.apply("w18_free_claim_registry_20261003"));}

    @Test void repeatedExactSchemaPassesAndPreservesRows() throws Exception {
        jdbc.update("insert into w18_free_publication values('fixture',repeat('1',64),8,11,repeat('2',64),'{}',X'01',X'02','2030-01-01 00:00:00')");
        mysql.apply("w18_free_claim_registry_20261003");
        assertEquals(1,jdbc.queryForObject("select count(*) from w18_free_publication",Integer.class));
    }
    @Test void sameNameNonuniqueOwnershipIndexFailsClosed() {
        jdbc.execute("alter table w18_free_publication drop index uk_w18_free_publication_identity, add key uk_w18_free_publication_identity(ownership_key)");rejected();
    }
    @Test void changedOwnedIndexColumnsFailClosed() {
        jdbc.execute("alter table w18_free_claim drop index uk_w18_free_claim_owned, add unique key uk_w18_free_claim_owned(buyer_member_id,request_key)");rejected();
    }
    @Test void reversedRequestIndexOrderFailsClosed() {
        jdbc.execute("alter table w18_free_claim drop index uk_w18_free_claim_request, add unique key uk_w18_free_claim_request(request_key,buyer_member_id)");rejected();
    }
    @Test void prefixRequestIndexFailsClosed() {
        jdbc.execute("alter table w18_free_claim drop index uk_w18_free_claim_request, add unique key uk_w18_free_claim_request(buyer_member_id,request_key(16))");rejected();
    }
    @Test void nullableBuyerIdentityFailsClosed() {
        jdbc.execute("alter table w18_free_claim modify buyer_member_id bigint null");rejected();
    }
    @Test void caseInsensitiveRequestCollationFailsClosed() {
        jdbc.execute("alter table w18_free_claim modify request_key varchar(128) character set ascii collate ascii_general_ci not null");rejected();
    }
    @Test void cascadingForeignKeyFailsClosed() {
        jdbc.execute("alter table w18_free_claim drop foreign key fk_w18_free_claim_publication");
        jdbc.execute("alter table w18_free_claim add constraint fk_w18_free_claim_publication foreign key(publication_id,document_hash) references w18_free_publication(publication_id,document_hash) on delete cascade");rejected();
    }
    @Test void lostTimestampPrecisionFailsClosed() {
        jdbc.execute("alter table w18_free_claim modify valid_until datetime(0) not null");rejected();
    }
    @Test void nullableOwnershipKeyFailsClosed() {
        jdbc.execute("alter table w18_free_publication modify ownership_key char(64) character set ascii collate ascii_bin null");rejected();
    }
    @Test void caseInsensitiveOwnershipDigestFailsClosed() {
        jdbc.execute("alter table w18_free_publication modify ownership_key char(64) character set ascii collate ascii_general_ci not null");rejected();
    }
    @Test void nonuniquePublicationDigestFailsClosed() {
        jdbc.execute("alter table w18_free_claim drop foreign key fk_w18_free_claim_publication");
        jdbc.execute("alter table w18_free_publication drop index uk_w18_free_publication_digest, add key uk_w18_free_publication_digest(publication_id,document_hash)");
        jdbc.execute("alter table w18_free_claim add constraint fk_w18_free_claim_publication foreign key(publication_id,document_hash) references w18_free_publication(publication_id,document_hash)");rejected();
    }
    @Test void sameNameForeignKeyWithWrongSourceTableFailsClosed() {
        jdbc.execute("create table fixture_wrong_publication(publication_id varchar(128) character set ascii collate ascii_bin not null primary key) engine=InnoDB");
        jdbc.execute("alter table w18_free_publication_policy drop foreign key fk_w18_free_policy_publication");
        jdbc.execute("alter table w18_free_publication_policy add constraint fk_w18_free_policy_publication foreign key(publication_id) references fixture_wrong_publication(publication_id)");rejected();
    }
}
