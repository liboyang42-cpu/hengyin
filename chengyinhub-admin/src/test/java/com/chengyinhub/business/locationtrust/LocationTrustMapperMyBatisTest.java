package com.chengyinhub.business.locationtrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.chengyinhub.business.mapper.LocationTrustMapper;

/**
 * Parses LocationTrustMapper.xml against the interface and runs the portable statements on H2 (MySQL mode).
 * MySQL-only statements (bit_count, &lt;=&gt;, DELETE … ORDER BY … LIMIT) are only rendered here; they need the
 * MySQL integration environment.
 */
class LocationTrustMapperMyBatisTest {

    private static final String DDL_FIX = "create table location_trust_fix (id bigint auto_increment primary key,"
            + " member_id bigint not null, session_id bigint, kind varchar(32) not null, ref_id varchar(64),"
            + " lat decimal(10,6) not null, lng decimal(10,6) not null, accuracy_m decimal(9,1), client_ts timestamp(3),"
            + " received_at timestamp(3) not null, simulated tinyint, accessory tinyint, speed_mps decimal(9,2),"
            + " decision varchar(24) not null, applied_decision varchar(24) not null, reasons varchar(255), detail varchar(255))";
    private static final String DDL_EVIDENCE = "create table location_claim_evidence (id bigint auto_increment primary key,"
            + " member_id bigint not null, place_ref varchar(64), place_lat decimal(10,6) not null, place_lng decimal(10,6) not null,"
            + " capture_source varchar(16) not null, photo_sha256 char(64) not null, photo_phash bigint, photo_taken_at timestamp(3),"
            + " photo_lat decimal(10,6), photo_lng decimal(10,6), fix_id bigint, verdict varchar(16) not null, reasons varchar(255),"
            + " duplicate_of bigint, consumed_at timestamp, consumed_ref varchar(64), create_time timestamp not null)";
    private static final String DDL_TILE = "create table roam_fog_tile (id bigint auto_increment primary key, member_id bigint,"
            + " session_id bigint, geohash7 varchar(12), revealed_at timestamp)";

    private SqlSessionFactory factory() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:location_trust_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(DDL_FIX);
            st.execute(DDL_EVIDENCE);
            st.execute(DDL_TILE);
        }
        Configuration configuration = new Configuration(new Environment("lt", new JdbcTransactionFactory(), ds));
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(LocationTrustMapper.class);
        String resource = "mapper/business/LocationTrustMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing " + resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static LocationTrustFixRow fix(long member, Long session, String kind, double lat, String decision, Integer sim, long at) {
        LocationTrustFixRow r = new LocationTrustFixRow();
        r.setMemberId(member);
        r.setSessionId(session);
        r.setKind(kind);
        r.setLat(BigDecimal.valueOf(lat).setScale(6));
        r.setLng(new BigDecimal("121.473700"));
        r.setAccuracyM(new BigDecimal("8.0"));
        r.setReceivedAt(new Date(at));
        r.setSimulated(sim);
        r.setDecision(decision);
        r.setAppliedDecision(decision);
        return r;
    }

    @Test
    void trailStatements_roundTrip() throws Exception {
        try (SqlSession session = factory().openSession(true)) {
            LocationTrustMapper m = session.getMapper(LocationTrustMapper.class);
            long now = System.currentTimeMillis();
            m.insertFix(fix(7, 3L, "REVEAL", 31.230000, "ACCEPT", 0, now - 60_000));
            LocationTrustFixRow simulated = fix(7, 3L, "REVEAL", 31.240000, "ACCEPT_NO_REWARD", 1, now - 1_000);
            m.insertFix(simulated);
            assertNotNull(simulated.getId());
            m.insertFix(fix(8, null, "CHECKIN", 31.250000, "ACCEPT", null, now));

            Map<String, Object> last = m.selectLastCleanFix(7L, new Date(now - 3_600_000));
            assertEquals(0, new BigDecimal("31.230000").compareTo(new BigDecimal(String.valueOf(last.get("lat")))),
                    "withheld/simulated fixes are never a baseline");
            assertNull(m.selectLastCleanFix(7L, new Date(now)));
            assertEquals(1, m.countIdenticalSince(7L, new BigDecimal("31.230000"), new BigDecimal("121.473700"),
                    new BigDecimal("8.0"), new Date(now - 3_600_000)));
            assertEquals(1, m.selectSessionRevealTrack(7L, 3L, 10).size());
            assertEquals(1, m.selectCleanFixesSince(7L, new Date(now - 3_600_000), 10).size());
            assertEquals(1, m.updateFixDetail(simulated.getId(), "tiles=3"));
            List<LocationTrustFixRow> flagged = m.selectFlagged(7L, null, 10);
            assertEquals(1, flagged.size());
            assertEquals("ACCEPT_NO_REWARD", flagged.get(0).getDecision());
            assertEquals(0, m.countTilesRevealedSince(7L, new Date(now - 60_000)));
        }
    }

    @Test
    void evidenceStatements_roundTrip() throws Exception {
        try (SqlSession session = factory().openSession(true)) {
            LocationTrustMapper m = session.getMapper(LocationTrustMapper.class);
            ClaimEvidenceRow e = new ClaimEvidenceRow();
            e.setMemberId(7L);
            e.setPlaceRef("cell:1");
            e.setPlaceLat(new BigDecimal("31.230400"));
            e.setPlaceLng(new BigDecimal("121.473700"));
            e.setCaptureSource("CAMERA");
            e.setPhotoSha256("a".repeat(64));
            e.setPhotoPhash(42L);
            e.setVerdict("ACCEPT");
            e.setCreateTime(new Date());
            m.insertEvidence(e);
            assertNotNull(e.getId());
            assertEquals("cell:1", m.selectEvidenceOwned(e.getId(), 7L).getPlaceRef());
            assertNull(m.selectEvidenceOwned(e.getId(), 8L));
            assertEquals(1, m.selectEvidenceBySha("a".repeat(64), new Date(0)).size());
            assertEquals(1, m.consumeEvidence(e.getId(), 7L, "claim:1", new Date()));
            assertEquals(0, m.consumeEvidence(e.getId(), 7L, "claim:2", new Date()), "one evidence backs one claim");
        }
    }

    @Test
    void mysqlOnlyStatements_render() throws Exception {
        Configuration c = factory().getConfiguration();
        Map<String, Object> p = new HashMap<>();
        p.put("phash", 42L);
        p.put("since", new Date());
        p.put("memberId", 7L);
        p.put("placeRef", "cell:1");
        String nearest = c.getMappedStatement("com.chengyinhub.business.mapper.LocationTrustMapper.selectNearestPhash")
                .getBoundSql(p).getSql();
        assertTrue(nearest.contains("bit_count(photo_phash ^ ?)"), nearest);
        assertTrue(nearest.contains("place_ref <=> ?"), nearest);
        Map<String, Object> d = new HashMap<>();
        d.put("cutoff", new Date());
        d.put("limit", 100);
        assertTrue(c.getMappedStatement("com.chengyinhub.business.mapper.LocationTrustMapper.deleteFixesBefore")
                .getBoundSql(d).getSql().contains("limit ?"));
        assertTrue(c.getMappedStatement("com.chengyinhub.business.mapper.LocationTrustMapper.clearEvidencePhotoCoordinatesBefore")
                .getBoundSql(d).getSql().contains("photo_lat = null"));
    }
}
