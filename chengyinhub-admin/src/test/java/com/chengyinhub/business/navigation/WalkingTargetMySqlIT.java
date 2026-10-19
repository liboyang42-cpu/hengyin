package com.chengyinhub.business.navigation;

import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Exact original migration + all sixteen shared SQL contracts. No H2 fallback. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
class WalkingTargetMySqlIT extends WalkingTargetMapperTest {
    private final IsolatedMySqlSchema mysql=new IsolatedMySqlSchema();
    @Override protected DriverManagerDataSource createDataSource() throws Exception { return mysql.open("walking"); }
    @Override protected void applyProvenanceSchema() throws Exception { mysql.apply("20261003_walking_target_provenance"); }
    @Override protected void assertPhysicalSchema() {
        super.assertPhysicalSchema();
        assertEquals("InnoDB",jdbc.queryForObject("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='roam_poi_navigation_provenance'",String.class));
        assertEquals("ascii_bin",jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='roam_poi_navigation_provenance' AND column_name='coordinate_datum'",String.class));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name='roam_poi_navigation_provenance' AND constraint_type='CHECK' AND enforced='YES'",Integer.class));
        assertThrows(Exception.class,()->jdbc.update("INSERT INTO roam_poi_navigation_provenance(poi_id,review_state) VALUES(99,'REVIEWED')"));
        assertThrows(Exception.class,()->jdbc.update("INSERT INTO roam_poi_navigation_provenance(poi_id) VALUES(0)"));
    }
    @AfterEach void closeScratch() throws Exception { mysql.close(); }
}
