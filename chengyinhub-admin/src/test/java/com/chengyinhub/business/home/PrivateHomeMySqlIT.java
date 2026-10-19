package com.chengyinhub.business.home;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Inherits the same 23 author/reviewer real mapper/transaction tests; explicit isolated Oracle MySQL gate only. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class PrivateHomeMySqlIT extends PrivateHomeBoundaryReviewTest {
    private final IsolatedMySqlSchema schema=new IsolatedMySqlSchema();
    @Override DataSource createDataSource() throws Exception {return schema.open("private_home");}
    @Override void createSchema() throws Exception {schema.apply("20261002_private_player_home");}
    @AfterEach void cleanupScratchSchema() throws Exception {schema.close();}
    void applyExactMigration() throws Exception {IsolatedMySqlSchema.applyMigration(ds,"20261002_private_player_home");}
    @Test void exactMigrationReplaysPreservingPrivateHome() throws Exception {
        home(); applyExactMigration(); assertEquals("Virtual base",service.getMyHome().label);assertEquals(1L,service.getMyHome().version);
    }
    @Test void wrongGrantUniquenessSchemaFailsAssertion() throws Exception {
        jdbc.execute("ALTER TABLE player_private_home_proof DROP INDEX uk_private_home_proof_grant");
        assertThrows(AssertionError.class,this::applyExactMigration);
    }
    @Test void canonicalChecksAreCapturedForReviewedSchemaPinning() {
        jdbc.query("SELECT constraint_name,check_clause FROM information_schema.check_constraints WHERE constraint_schema=DATABASE() ORDER BY constraint_name",
            (org.springframework.jdbc.core.RowCallbackHandler)rs->System.out.println("W19_CHECK_CANONICAL "+rs.getString(1)+"="+rs.getString(2)));
    }
    @Test void sameNameWeakCheckCannotPassMigration() throws Exception {
        jdbc.execute("ALTER TABLE player_private_home DROP CHECK chk_private_home, ADD CONSTRAINT chk_private_home CHECK (owner_id>0) ENFORCED");
        assertThrows(AssertionError.class,this::applyExactMigration,"Weak same-name CHECK must fail the pinned guard");
    }
    void replaceHomeCheck(String oldLiteral,String newLiteral) throws Exception {
        String clause=migrationSql().split("CONSTRAINT chk_private_home CHECK ",2)[1].split("\n\\) ENGINE",2)[0];
        assertTrue(clause.contains(oldLiteral));
        jdbc.execute("ALTER TABLE player_private_home DROP CHECK chk_private_home, ADD CONSTRAINT chk_private_home CHECK ("+clause.replace(oldLiteral,newLiteral)+") ENFORCED");
    }
    @Test void literalCaseDriftCannotPassMigration() throws Exception {
        replaceHomeCheck("'ACTIVE'","'active'");assertThrows(AssertionError.class,this::applyExactMigration);
    }
    @Test void literalWhitespaceDriftCannotPassMigration() throws Exception {
        replaceHomeCheck("'ACTIVE'","'ACT IVE'");assertThrows(AssertionError.class,this::applyExactMigration);
    }
    @Test void disabledCheckCannotPassMigration() throws Exception {
        jdbc.execute("ALTER TABLE player_private_home ALTER CHECK chk_private_home NOT ENFORCED");
        assertThrows(AssertionError.class,this::applyExactMigration);
    }
    @Test void requestIdentifiersRemainCaseSensitive() {
        home(); assertEquals("SAVED",service.setMyHome("SET",1,"Second",point()).decision);
        assertEquals(2,count("player_private_home_mutation"));
    }
}
