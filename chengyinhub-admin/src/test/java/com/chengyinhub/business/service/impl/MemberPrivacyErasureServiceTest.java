package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.MemberPrivacyErasureMapper;
import com.chengyinhub.business.mapper.RoamFogCellMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * R13: seeds every table the erasure touches for member 7 (erased) and member 8 (bystander), runs the erasure with the
 * production mapper XMLs inside a Spring transaction (as the deregistration job does), and proves only member 7's
 * rows are gone or anonymised. Also proves audit tables are untouched and a failing step rolls everything back.
 */
class MemberPrivacyErasureServiceTest {

    private static final long ERASED = 7L;
    private static final long OTHER = 8L;
    private static final String RESULT_JSON =
            "{\"distanceM\":1200,\"xp\":30,\"track\":[[31.2,121.4]],\"lastLat\":31.2,\"summary\":{\"lat\":31.2,\"pois\":2}}";

    @Test
    void erasesOnlyTheGivenMembersRowsAndKeepsAuditRecords() throws Exception {
        try (Fixture f = new Fixture()) {
            f.seed(ERASED);
            f.seed(OTHER);

            Map<String, Integer> counts = f.tx.execute(s -> f.service.erase(ERASED, new Date()));

            // Deleted for member 7.
            for (String table : new String[]{"roam_member_fog_cell", "roam_fog_tile", "roam_session_record_archive",
                    "roam_share_snapshot", "location_trust_fix", "app_install_link", "push_device"}) {
                assertEquals(0, f.count(table, ERASED), table + " of the erased member");
                assertTrue(f.count(table, OTHER) > 0, table + " of the other member must stay");
            }
            assertEquals(2, counts.get("roam_member_fog_cell"));
            assertEquals(2, counts.get("push_device"));

            // Channel events (C3): own RSVPs and album rows gone; a posted event stays without the poster link.
            for (String table : new String[]{"channel_event_rsvp", "channel_event_photo"}) {
                assertEquals(0, f.count(table, ERASED), table + " of the erased member");
                assertEquals(1, f.count(table, OTHER), table + " of the other member must stay");
            }
            assertEquals(1, f.db.queryForObject("select count(*) from channel_event where activity_id=? and poster_member_id=0",
                    Integer.class, 900 + ERASED));
            assertEquals(1, f.db.queryForObject("select count(*) from channel_event where poster_member_id=?", Integer.class, OTHER));

            // Roam sessions: kept with aggregates, every coordinate gone.
            Map<String, Object> s7 = f.db.queryForMap("select * from roam_session where member_id=?", ERASED);
            assertNull(s7.get("last_lat"));
            assertNull(s7.get("last_lng"));
            assertNull(s7.get("last_report_at"));
            assertNull(s7.get("track_json"));
            assertEquals(0, ((Number) s7.get("presence_visible")).intValue());
            assertEquals(1200, ((Number) s7.get("distance_m")).intValue());
            assertTrue(s7.get("location_cleared_at") != null);
            String stripped = (String) s7.get("finish_result_json");
            assertFalse(stripped.contains("track") || stripped.contains("lastLat") || stripped.contains("\"lat\""), stripped);
            assertTrue(stripped.contains("distanceM") && stripped.contains("pois") == false, stripped);
            Map<String, Object> s8 = f.db.queryForMap("select * from roam_session where member_id=?", OTHER);
            assertTrue(s8.get("last_lat") != null && s8.get("track_json") != null && s8.get("location_cleared_at") == null);
            assertEquals(RESULT_JSON, s8.get("finish_result_json"));

            // Place rows: kept for ledgers, place and fingerprint gone.
            assertEquals(0, f.db.queryForObject("select count(*) from roam_poi_found where member_id=? and"
                    + " (poi_id is not null or claim_fingerprint is not null)", Integer.class, ERASED));
            assertEquals(1, f.count("roam_poi_found", ERASED));
            assertEquals(0, f.db.queryForObject("select count(*) from roam_shop_visit where member_id=? and"
                    + " (source_id is not null or claim_fingerprint is not null or shop_name<>'')", Integer.class, ERASED));
            assertEquals(1, f.db.queryForObject("select count(*) from roam_poi_reward_log where member_id=? and"
                    + " trigger_lat is null and claim_fingerprint is null and poi_id = 501", Integer.class, ERASED),
                    "a coupon row keeps poi_id for coupon accounting");
            assertEquals(1, f.db.queryForObject("select count(*) from roam_poi_reward_log where member_id=? and"
                    + " trigger_lat is null and poi_id is null", Integer.class, ERASED));
            assertEquals(2, f.db.queryForObject("select count(*) from roam_poi_reward_log where member_id=? and"
                    + " trigger_lat is not null and poi_id is not null", Integer.class, OTHER));
            assertEquals(1, f.db.queryForObject("select count(*) from roam_poi_found where member_id=? and poi_id is not null",
                    Integer.class, OTHER));
            assertEquals(1, f.db.queryForObject("select count(*) from roam_shop_visit where member_id=? and source_id is not null",
                    Integer.class, OTHER));
            // Merchant visitor counts were registered before the raw visit was erased; never for the bystander.
            verify(f.roam).recordAnonymousShopVisitor(ERASED, 1, 77L);
            verify(f.roam, never()).recordAnonymousShopVisitor(eq(OTHER), anyInt(), anyLong());

            // Claim evidence: hashes kept for duplicate detection, member link and coordinates gone.
            assertEquals(0, f.count("location_claim_evidence", ERASED));
            Map<String, Object> ev = f.db.queryForMap("select * from location_claim_evidence where photo_sha256=?", "sha-" + ERASED);
            assertEquals(0L, ((Number) ev.get("member_id")).longValue());
            assertNull(ev.get("photo_lat"));
            assertNull(ev.get("fix_id"));
            assertNull(ev.get("consumed_ref"));
            assertTrue(ev.get("consumed_at") != null);
            assertEquals(1, f.db.queryForObject("select count(*) from location_claim_evidence where member_id=? and photo_lat is not null",
                    Integer.class, OTHER));

            // CITY + roam territory: own check-ins gone, held territory released, audits unlinked.
            assertEquals(0, f.count("roam_territory_checkin", ERASED));
            assertEquals(1, f.count("roam_territory_checkin", OTHER));
            assertEquals(1, f.db.queryForObject("select count(*) from roam_territory_checkin where member_id=? and owner_member_id=0",
                    Integer.class, 100 + ERASED), "a bystander's check-in at the erased member's territory stays, unlinked");
            assertEquals(1, f.db.queryForObject("select count(*) from roam_territory_checkin where member_id=? and owner_member_id=?",
                    Integer.class, 100 + OTHER, OTHER));
            Map<String, Object> t7 = f.db.queryForMap("select * from roam_territory where territory_id=?", "terr-" + ERASED);
            assertEquals(0L, ((Number) t7.get("owner_member_id")).longValue());
            assertEquals("", t7.get("claim_photo_url"));
            assertEquals("REVOKED", t7.get("status"));
            Map<String, Object> t8 = f.db.queryForMap("select * from roam_territory where territory_id=?", "terr-" + OTHER);
            assertEquals(OTHER, ((Number) t8.get("owner_member_id")).longValue());
            assertEquals("ACTIVE", t8.get("status"));
            assertEquals(0, f.count("roam_territory_audit", ERASED));
            assertEquals(1, f.db.queryForObject("select count(*) from roam_territory_audit where member_id=0 and latitude is null"
                    + " and detail_json is null", Integer.class));
            assertEquals(1, f.db.queryForObject("select count(*) from roam_territory_audit where member_id=? and latitude is not null",
                    Integer.class, OTHER));
            assertEquals(0, f.db.queryForObject("select count(*) from city_game_board_audit where actor_type='PLAYER' and actor_id=?",
                    Integer.class, ERASED));
            assertEquals(1, f.db.queryForObject("select count(*) from city_game_board_audit where actor_id is null"
                    + " and action='BOARD_TERRITORY_CLAIM' and detail_json is null", Integer.class));
            assertEquals(1, f.db.queryForObject("select count(*) from city_game_board_audit where actor_id is null"
                    + " and action='DICE_ROLL' and detail_json is not null", Integer.class));
            assertEquals(1, f.db.queryForObject("select count(*) from city_game_board_audit where actor_type='ADMIN' and actor_id=?",
                    Integer.class, ERASED), "an admin user id that happens to equal the member id is not a member");
            assertEquals(2, f.db.queryForObject("select count(*) from city_game_board_audit where actor_type='PLAYER' and actor_id=?",
                    Integer.class, OTHER));

            // Audit records kept on purpose.
            assertEquals(2, f.count("consent_record", ERASED));
            assertEquals(1, f.count("account_deregistration", ERASED));
            assertEquals(1, f.count("age_attestation", ERASED));
        }
    }

    @Test
    void runningTheErasureAgainChangesNothing() throws Exception {
        try (Fixture f = new Fixture()) {
            f.seed(ERASED);
            f.seed(OTHER);
            f.tx.execute(s -> f.service.erase(ERASED, new Date()));
            Map<String, Integer> again = f.tx.execute(s -> f.service.erase(ERASED, new Date()));
            for (Map.Entry<String, Integer> step : again.entrySet())
                assertEquals(0, step.getValue().intValue(), step.getKey() + " should be a no-op the second time");
        }
    }

    @Test
    void aFailingStepRollsBackTheWholeErasure() throws Exception {
        try (Fixture f = new Fixture()) {
            f.seed(ERASED);
            f.db.execute("drop table push_device");
            assertThrows(RuntimeException.class, () -> f.tx.execute(s -> f.service.erase(ERASED, new Date())));
            assertEquals(2, f.count("roam_member_fog_cell", ERASED), "earlier steps must roll back with the failure");
            assertEquals(1, f.count("app_install_link", ERASED));
            assertEquals(1, f.db.queryForObject("select count(*) from roam_session where member_id=? and track_json is not null",
                    Integer.class, ERASED));
        }
    }

    @Test
    void rejectsAMissingMember() {
        MemberPrivacyErasureService service = new MemberPrivacyErasureService(mock(MemberPrivacyErasureMapper.class),
                mock(RoamFogCellMapper.class), mock(RoamMapper.class));
        assertThrows(IllegalArgumentException.class, () -> service.erase(null, new Date()));
        assertThrows(IllegalArgumentException.class, () -> service.erase(0L, new Date()));
    }

    /** H2 (MySQL mode) with the columns the statements use; production mapper XMLs; Spring-managed transaction. */
    static final class Fixture implements AutoCloseable {
        final JdbcTemplate db;
        final TransactionTemplate tx;
        final RoamMapper roam = mock(RoamMapper.class);
        final MemberPrivacyErasureService service;

        Fixture() throws Exception {
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:privacy_erasure_"
                    + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
            db = new JdbcTemplate(source);
            db.execute("create table roam_session(id bigint auto_increment primary key, member_id bigint not null, status int,"
                    + " distance_m int, xp_awarded int, last_lat decimal(10,6), last_lng decimal(10,6), last_report_at datetime,"
                    + " presence_visible tinyint, track_json varchar(4000), finish_result_json varchar(4000), location_cleared_at datetime)");
            db.execute("create table roam_fog_tile(id bigint auto_increment primary key, member_id bigint, session_id bigint,"
                    + " geohash7 varchar(12), revealed_at datetime, claim_fingerprint char(64))");
            db.execute("create table roam_session_record_archive(member_id bigint, session_id bigint, tile char(7))");
            db.execute("create table roam_share_snapshot(id bigint auto_increment primary key, share_token char(32),"
                    + " member_id bigint, snapshot_json clob)");
            db.execute("create table roam_shop_visit(id bigint auto_increment primary key, member_id bigint, session_id bigint,"
                    + " source_type tinyint, source_id bigint, shop_name varchar(64), claim_fingerprint char(64))");
            db.execute("create table roam_poi_found(id bigint auto_increment primary key, member_id bigint, session_id bigint,"
                    + " poi_id bigint, claim_fingerprint char(64))");
            db.execute("create table roam_poi_reward_log(id bigint auto_increment primary key, poi_id bigint, member_id bigint,"
                    + " coupon_id bigint, trigger_lat decimal(10,6), trigger_lng decimal(10,6), claim_fingerprint char(64))");
            db.execute("create table roam_member_fog_cell(member_id bigint, cell_key varchar(12), cell_prefix5 char(5),"
                    + " first_revealed_at datetime, last_revealed_at datetime, primary key(member_id, cell_key))");
            db.execute("create table location_trust_fix(id bigint auto_increment primary key, member_id bigint not null,"
                    + " lat decimal(10,6), lng decimal(10,6), received_at datetime)");
            db.execute("create table location_claim_evidence(id bigint auto_increment primary key, member_id bigint not null,"
                    + " place_ref varchar(64), photo_sha256 char(64), photo_phash bigint, photo_lat decimal(10,6),"
                    + " photo_lng decimal(10,6), fix_id bigint, consumed_at datetime, consumed_ref varchar(64))");
            db.execute("create table app_install_link(id bigint auto_increment primary key, install_id char(36),"
                    + " member_id bigint, linked_at datetime)");
            db.execute("create table push_device(id bigint auto_increment primary key, member_id bigint, token varchar(200),"
                    + " environment varchar(12))");
            db.execute("create table roam_territory(territory_id char(36) primary key, owner_member_id bigint not null,"
                    + " claim_photo_url varchar(500) not null, claim_request_id varchar(96) not null, status varchar(16),"
                    + " latitude decimal(16,12), longitude decimal(16,12), unique(owner_member_id, claim_request_id))");
            db.execute("create table roam_territory_checkin(checkin_id char(36) primary key, territory_id char(36),"
                    + " member_id bigint not null, owner_member_id bigint not null, request_id varchar(96),"
                    + " latitude decimal(16,12) not null, longitude decimal(16,12) not null, photo_url varchar(500))");
            db.execute("create table roam_territory_audit(id bigint auto_increment primary key, territory_id char(36),"
                    + " member_id bigint not null, latitude decimal(16,12), longitude decimal(16,12), accuracy_m double,"
                    + " distance_m double, detail_json varchar(2000))");
            db.execute("create table city_game_board_audit(id bigint auto_increment primary key, board_id varchar(96),"
                    + " action varchar(32), actor_id bigint, actor_type varchar(16), detail_json varchar(2000))");
            db.execute("create table channel_event(id bigint auto_increment primary key, activity_id bigint, poster_member_id bigint)");
            db.execute("create table channel_event_rsvp(id bigint auto_increment primary key, event_id bigint, member_id bigint,"
                    + " status varchar(8))");
            db.execute("create table channel_event_photo(id bigint auto_increment primary key, event_id bigint, member_id bigint,"
                    + " asset_id bigint, removed tinyint default 0)");
            // Audit tables the erasure must leave alone.
            db.execute("create table consent_record(id bigint auto_increment primary key, member_id bigint, scene varchar(64),"
                    + " event_type varchar(16))");
            db.execute("create table account_deregistration(id bigint auto_increment primary key, member_id bigint)");
            db.execute("create table age_attestation(id bigint auto_increment primary key, member_id bigint)");

            Configuration config = new Configuration(new Environment("privacy-erasure", new SpringManagedTransactionFactory(), source));
            for (String xml : new String[]{"mapper/business/MemberPrivacyErasureMapper.xml", "mapper/business/RoamFogCellMapper.xml"}) {
                try (InputStream input = Resources.getResourceAsStream(xml)) {
                    new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
                }
            }
            SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            service = new MemberPrivacyErasureService(sessions.getMapper(MemberPrivacyErasureMapper.class),
                    sessions.getMapper(RoamFogCellMapper.class), roam);
            tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        }

        void seed(long m) {
            db.update("insert into roam_session(member_id,status,distance_m,xp_awarded,last_lat,last_lng,last_report_at,"
                    + "presence_visible,track_json,finish_result_json) values(?,1,1200,30,31.2,121.4,now(),1,'[[31.2,121.4]]',?)",
                    m, RESULT_JSON);
            db.update("insert into roam_fog_tile(member_id,session_id,geohash7,revealed_at) values(?,1,'wtw3sjq',now())", m);
            db.update("insert into roam_fog_tile(member_id,session_id,geohash7,revealed_at,claim_fingerprint) values(?,1,null,now(),'fp')", m);
            db.update("insert into roam_session_record_archive values(?,1,'wtw3sjq')", m);
            db.update("insert into roam_share_snapshot(share_token,member_id,snapshot_json) values(?,?,'{}')", "tok" + m, m);
            db.update("insert into roam_shop_visit(member_id,session_id,source_type,source_id,shop_name,claim_fingerprint)"
                    + " values(?,1,1,77,'Cafe',null)", m);
            db.update("insert into roam_poi_found(member_id,session_id,poi_id) values(?,1,500)", m);
            db.update("insert into roam_poi_reward_log(poi_id,member_id,coupon_id,trigger_lat,trigger_lng) values(501,?,9,31.2,121.4)", m);
            db.update("insert into roam_poi_reward_log(poi_id,member_id,coupon_id,trigger_lat,trigger_lng) values(502,?,0,31.2,121.4)", m);
            db.update("insert into roam_member_fog_cell values(?,'wtw3sjq','wtw3s',now(),now())", m);
            db.update("insert into roam_member_fog_cell values(?,'wtw3sjr','wtw3s',now(),now())", m);
            db.update("insert into location_trust_fix(member_id,lat,lng,received_at) values(?,31.2,121.4,now())", m);
            db.update("insert into location_claim_evidence(member_id,place_ref,photo_sha256,photo_phash,photo_lat,photo_lng,"
                    + "fix_id,consumed_at,consumed_ref) values(?,'cell:1',?,123,31.2,121.4,1,now(),'claim-1')", m, "sha-" + m);
            db.update("insert into app_install_link(install_id,member_id,linked_at) values(?,?,now())",
                    UUID.randomUUID().toString(), m);
            db.update("insert into push_device(member_id,token,environment) values(?,?,'PRODUCTION')", m, "aa" + m);
            db.update("insert into push_device(member_id,token,environment) values(?,?,'SANDBOX')", m, "bb" + m);
            db.update("insert into roam_territory values(?,?,'https://img/claim.jpg','claim-1','ACTIVE',31.2,121.4)", "terr-" + m, m);
            db.update("insert into roam_territory_checkin values(?,?,?,?,'claim-1',31.2,121.4,'https://img/claim.jpg')",
                    "ck-claim-" + m, "terr-" + m, m, m);
            db.update("insert into roam_territory_checkin values(?,?,?,?,'visit-1',31.2,121.4,null)",
                    "ck-visit-" + m, "terr-" + m, 100 + m, m);
            db.update("insert into roam_territory_audit(territory_id,member_id,latitude,longitude,accuracy_m,distance_m,detail_json)"
                    + " values(?,?,31.2,121.4,10,5,'{}')", "terr-" + m, m);
            db.update("insert into city_game_board_audit(board_id,action,actor_id,actor_type,detail_json)"
                    + " values('b1','BOARD_TERRITORY_CLAIM',?,'PLAYER','{\"photoUrl\":\"x\"}')", m);
            db.update("insert into city_game_board_audit(board_id,action,actor_id,actor_type,detail_json)"
                    + " values('b1','DICE_ROLL',?,'PLAYER','{}')", m);
            db.update("insert into city_game_board_audit(board_id,action,actor_id,actor_type,detail_json)"
                    + " values('b1','DRAFT_SAVE',?,'ADMIN','{}')", m);
            db.update("insert into consent_record(member_id,scene,event_type) values(?,'roam_location','AGREE')", m);
            db.update("insert into consent_record(member_id,scene,event_type) values(?,'app_launch','AGREE')", m);
            db.update("insert into account_deregistration(member_id) values(?)", m);
            db.update("insert into age_attestation(member_id) values(?)", m);
            db.update("insert into channel_event(activity_id,poster_member_id) values(?,?)", 900 + m, m);
            db.update("insert into channel_event_rsvp(event_id,member_id,status) values(1,?,'MAYBE')", m);
            db.update("insert into channel_event_photo(event_id,member_id,asset_id) values(1,?,?)", m, 300 + m);
        }

        int count(String table, long member) {
            return db.queryForObject("select count(*) from " + table + " where member_id=?", Integer.class, member);
        }

        @Override public void close() {
            db.execute("drop all objects");
        }
    }
}
