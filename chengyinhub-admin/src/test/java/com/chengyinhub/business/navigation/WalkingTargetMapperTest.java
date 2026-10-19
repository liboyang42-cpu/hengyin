package com.chengyinhub.business.navigation;

import com.chengyinhub.business.mapper.WalkingTargetMapper;
import com.chengyinhub.business.navigation.WalkingTargetContracts.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.*;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real mapper SQL against synthetic H2 fixtures, inherited unchanged by the MySQL suite. */
class WalkingTargetMapperTest {
    protected DriverManagerDataSource source;
    protected JdbcTemplate jdbc;
    @BeforeEach void setup() throws Exception {
        source=createDataSource();jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE roam_poi (id BIGINT PRIMARY KEY,type INT,status INT,application_type INT,audit_status INT,lat DECIMAL(10,6),lng DECIMAL(10,6),merchant_id BIGINT)");
        jdbc.execute("CREATE TABLE mms_merchant (id BIGINT PRIMARY KEY,status INT,account_status INT,del_flag INT)");
        applyProvenanceSchema();
        jdbc.update("INSERT INTO mms_merchant VALUES(7,1,1,0)");
        jdbc.update("INSERT INTO roam_poi VALUES(11,2,1,1,1,35,135,7)");
    }
    protected DriverManagerDataSource createDataSource() throws Exception {
        return new DriverManagerDataSource("jdbc:h2:mem:walk_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    }
    protected void applyProvenanceSchema() throws Exception {
        // This is a logical fixture only. MySQL subclass executes the actual strict-CREATE migration.
        jdbc.execute("CREATE TABLE roam_poi_navigation_provenance (poi_id BIGINT PRIMARY KEY,provenance_revision BIGINT NOT NULL DEFAULT 1,review_state VARCHAR(12) NOT NULL DEFAULT 'UNREVIEWED',latitude DECIMAL(10,7),longitude DECIMAL(10,7),coordinate_datum VARCHAR(8),country_region CHAR(2),source_kind VARCHAR(16),source_reference VARCHAR(192),source_revision VARCHAR(192),review_reference VARCHAR(192),reviewed_at BIGINT)");
    }
    protected void assertPhysicalSchema() { assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM roam_poi_navigation_provenance",Integer.class)); }
    protected WalkingTargetMapper mapper(SqlSession session) { return session.getMapper(WalkingTargetMapper.class); }
    protected SqlSessionFactory factory() throws Exception {
        Configuration c=new Configuration(new Environment("walking",new SpringManagedTransactionFactory(),source));
        try(InputStream in=getClass().getResourceAsStream("/mapper/business/WalkingTargetMapper.xml")) {
            new XMLMapperBuilder(in,c,"walking",c.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(c);
    }
    protected SqlSession open() throws Exception { return factory().openSession(true); }
    protected WalkingTargetEvidenceProducer producer() throws Exception {
        return new WalkingTargetEvidenceProducer(new SqlSessionTemplate(factory()).getMapper(WalkingTargetMapper.class),
                new DataSourceTransactionManager(source),Clock.fixed(Instant.ofEpochMilli(WalkingTargetEvidenceProducerTest.NOW),ZoneOffset.UTC));
    }
    protected Evidence read() throws Exception { return producer().readCityTarget(11); }
    protected void reviewed() {
        // Explicit synthetic test-only review fixture. No application promotion/writer exists.
        jdbc.update("INSERT INTO roam_poi_navigation_provenance VALUES(11,1,'REVIEWED',35,135,'WGS84','JP','SURVEY_RECORD','synthetic-survey-1','v1','synthetic-review-1',?)",WalkingTargetEvidenceProducerTest.NOW-1000);
    }
    protected void unavailable(Failure f) {assertEquals(f,assertThrows(Unavailable.class,this::read).getFailure());}

    @Test void migrationStartsEmptyAndDefaultsStayUnknown() {
        assertPhysicalSchema();jdbc.update("INSERT INTO roam_poi_navigation_provenance(poi_id) VALUES(11)");
        assertEquals("UNREVIEWED",jdbc.queryForObject("SELECT review_state FROM roam_poi_navigation_provenance",String.class));
        for(String field:Arrays.asList("coordinate_datum","country_region","review_reference","reviewed_at")) assertNull(jdbc.queryForObject("SELECT "+field+" FROM roam_poi_navigation_provenance",Object.class));
        unavailable(Failure.PROVENANCE_UNAVAILABLE);
    }
    @Test void repeatedCreateFailsRatherThanBlessExistingOrDriftedTable() {
        assertThrows(Exception.class,this::applyProvenanceSchema);
        jdbc.execute("ALTER TABLE roam_poi_navigation_provenance ADD unexpected INT");
        assertThrows(Exception.class,this::applyProvenanceSchema);
    }
    @Test void missingProvenanceFailsEvenForCurrentPublicTarget() { unavailable(Failure.PROVENANCE_UNAVAILABLE); }
    @Test void realMapperReturnsTypedReviewedEvidence() throws Exception { reviewed();assertEquals("roam_poi",read().getNamespace());assertEquals("JP",read().getCountryRegion()); }
    @Test void rawCoordinateEditInvalidatesEvidence() {reviewed();jdbc.update("UPDATE roam_poi SET lat=35.000001");unavailable(Failure.COORDINATES_CHANGED);}
    @Test void historicalStatusAndPendingOrRejectedPlacementNeverAuthorize() {
        reviewed();jdbc.update("UPDATE roam_poi SET status=0");unavailable(Failure.TARGET_UNAVAILABLE);
        jdbc.update("UPDATE roam_poi SET status=1,audit_status=0");unavailable(Failure.TARGET_UNAVAILABLE);
        jdbc.update("UPDATE roam_poi SET audit_status=2");unavailable(Failure.TARGET_UNAVAILABLE);
    }
    @Test void merchantVisibilityChangesAndMissingJoinAlwaysDeny() {
        reviewed();
        for(String field:Arrays.asList("status","account_status","del_flag")) {
            jdbc.update("UPDATE mms_merchant SET "+field+"=NULL");unavailable(Failure.TARGET_UNAVAILABLE);
            jdbc.update("UPDATE mms_merchant SET "+field+"=2");unavailable(Failure.TARGET_UNAVAILABLE);
            jdbc.update("UPDATE mms_merchant SET status=1,account_status=1,del_flag=0");
        }
        jdbc.update("DELETE FROM mms_merchant");unavailable(Failure.TARGET_UNAVAILABLE);
    }
    @Test void targetRemovalWrongNamespaceAndUnaffiliatedPoiNeverFallThrough() {
        reviewed();jdbc.update("UPDATE roam_poi SET type=1");unavailable(Failure.TARGET_UNAVAILABLE);
        jdbc.update("UPDATE roam_poi SET type=2,merchant_id=NULL");unavailable(Failure.TARGET_UNAVAILABLE);
        jdbc.update("DELETE FROM roam_poi");unavailable(Failure.TARGET_UNAVAILABLE);
    }
    @Test void legacyAndClaimContentPolicyMatchesExistingPoiReadWithoutOtherReleaseGrants() throws Exception {
        reviewed();String first=read().getAuthorityRevision();jdbc.update("UPDATE roam_poi SET application_type=NULL,audit_status=NULL");
        assertNotEquals(first,read().getAuthorityRevision());jdbc.update("UPDATE roam_poi SET application_type=2,audit_status=0");assertNotNull(read());
    }
    @Test void stagingIsDurableUnreviewedAndIgnoresForgedApprovalAndRevision() throws Exception {
        Provenance p=WalkingTargetEvidenceProducerTest.row();p.provenanceRevision=999L;
        try(SqlSession s=open()){new NavigationProvenanceIngestion(mapper(s)).stage(p);}
        try(SqlSession s=open()){
            CityTargetRow saved=mapper(s).selectCurrentPublicCityTarget(11);assertEquals("UNREVIEWED",saved.reviewState);
            assertEquals(1L,saved.provenanceRevision);assertNull(saved.reviewReference);assertNull(saved.reviewedAt);
        }
        unavailable(Failure.PROVENANCE_UNAVAILABLE);
    }
    @Test void stagingAllowsUnsetDatumCountryAndNeverMassBackfills() throws Exception {
        Provenance p=new Provenance();p.poiId=11L;p.latitude=new BigDecimal("35");p.longitude=new BigDecimal("135");
        jdbc.update("INSERT INTO roam_poi VALUES(12,2,1,1,1,35,135,7)");
        try(SqlSession s=open()){new NavigationProvenanceIngestion(mapper(s)).stage(p);}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM roam_poi_navigation_provenance",Integer.class));
        assertNull(jdbc.queryForObject("SELECT coordinate_datum FROM roam_poi_navigation_provenance",String.class));
        assertNull(jdbc.queryForObject("SELECT country_region FROM roam_poi_navigation_provenance",String.class));unavailable(Failure.PROVENANCE_UNAVAILABLE);
    }
    @Test void stagingStaleCoordinatesAndWrongPoiKindCannotWrite() throws Exception {
        try(SqlSession s=open()){
            NavigationProvenanceIngestion ingestion=new NavigationProvenanceIngestion(mapper(s));
            Provenance p=WalkingTargetEvidenceProducerTest.row();p.latitude=new BigDecimal("36");assertThrows(Unavailable.class,()->ingestion.stage(p));
            p.latitude=new BigDecimal("35");jdbc.update("UPDATE roam_poi SET type=1");assertThrows(Unavailable.class,()->ingestion.stage(p));
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM roam_poi_navigation_provenance",Integer.class));
    }
    @Test void stagingNeverOverwritesReviewedRecord() throws Exception {
        reviewed();String revision=read().getAuthorityRevision();
        try(SqlSession s=open()){assertThrows(Exception.class,()->new NavigationProvenanceIngestion(mapper(s)).stage(WalkingTargetEvidenceProducerTest.row()));}
        assertEquals(revision,read().getAuthorityRevision());assertEquals("REVIEWED",jdbc.queryForObject("SELECT review_state FROM roam_poi_navigation_provenance",String.class));
    }
    @Test void sameMapperSessionCannotReusePriorVisibilityFromLocalCache() throws Exception {
        reviewed();
        try(SqlSession s=open()) {
            WalkingTargetMapper m=mapper(s);assertNotNull(m.selectCurrentPublicCityTarget(11));
            jdbc.update("UPDATE mms_merchant SET account_status=2");assertNull(m.selectCurrentPublicCityTarget(11));
        }
    }
    @Test void producerSuspendsOldRepeatableReadSnapshotAndUsesFreshVisibility() throws Exception {
        reviewed();WalkingTargetEvidenceProducer p=producer();
        org.springframework.transaction.support.TransactionTemplate outer=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.execute(status->{
            assertEquals(1,jdbc.queryForObject("SELECT account_status FROM mms_merchant WHERE id=7",Integer.class));
            // A separate JDBC connection simulates the committed administrative hide.
            try(java.sql.Connection c=source.getConnection();java.sql.Statement sql=c.createStatement()){sql.executeUpdate("UPDATE mms_merchant SET account_status=2 WHERE id=7");}
            catch(java.sql.SQLException e){throw new IllegalStateException(e);}
            assertEquals(Failure.TARGET_UNAVAILABLE,assertThrows(Unavailable.class,()->p.readCityTarget(11)).getFailure());
            status.setRollbackOnly();return null;
        });
    }
    @Test void revocationAndMalformedSourceCannotAuthorize() {
        reviewed();jdbc.update("UPDATE roam_poi_navigation_provenance SET review_state='REVOKED'");unavailable(Failure.PROVENANCE_UNAVAILABLE);
        jdbc.update("UPDATE roam_poi_navigation_provenance SET review_state='REVIEWED',country_region='ZZ'");unavailable(Failure.PROVENANCE_UNAVAILABLE);
    }
}
