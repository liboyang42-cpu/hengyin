package com.chengyinhub.business.discovery;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Existing opt-in loopback MySQL 8.0.45 scratch harness; never points at a production database. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class EggDiscoveryMySqlIT extends EggDiscoveryPersistenceH2Test {
    private final IsolatedMySqlSchema mysql=new IsolatedMySqlSchema();
    @Override protected DataSource createDataSource() throws Exception { return mysql.open("egg"); }
    @Override protected void applySchema(JdbcTemplate jdbc) throws Exception { mysql.apply("20261004_egg_discovery_evaluation"); }
    @Override protected void dropFailureCheck(String table,String name) { jdbc.execute("ALTER TABLE "+table+" DROP CHECK "+name); }
    @AfterEach void removeScratch() throws Exception { mysql.close(); }
    @Test void physicalSchemaPreservesIsolationAndBinaryIdentifiers() {
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'w14_egg_evaluation_%' AND engine='InnoDB'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name IN ('fk_w14_egg_receipt_scope','fk_w14_egg_outbox_scope')",Integer.class));
        assertEquals("ascii_bin",jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='w14_egg_evaluation_scope' AND column_name='evaluation_key'",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='CHECK' AND constraint_name IN ('ck_w14_egg_scope','ck_w14_egg_receipt','ck_w14_egg_outbox')",Integer.class));
    }
}
