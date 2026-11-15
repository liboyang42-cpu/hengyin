package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.mapper.RoamFogCellMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiComplianceController;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * R13: withdrawing roam location consent through the real route (/api/compliance/consents) deletes the member's kept
 * fog cells in the same transaction, idempotently, and touches no one else's cells. Agreeing again does not bring
 * them back. Withdrawing the home-city location consent (a different purpose) keeps them.
 */
class RoamConsentWithdrawalDeletesKeptCellsTest {

    private static final long ME = 9L;
    private static final long OTHER = 10L;

    @Test
    void withdrawingRoamLocationDeletesOnlyMyKeptCells() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(200, f.write("roam_location", "AGREE", "roam-agree", "").get("code"));
            assertEquals(3, f.cells(ME), "agreeing never deletes");

            assertEquals(200, f.write("roam_location", "REVOKE", "roam-revoke", "roam-agree").get("code"));
            assertEquals(0, f.cells(ME));
            assertEquals(2, f.cells(OTHER), "another member's kept cells stay");
            assertEquals(0, f.presence(), "withdrawal still stops nearby sharing");
            assertEquals(2, f.consents(), "the consent ledger keeps both events");
        }
    }

    @Test
    void replayedWithdrawalIsIdempotentAndFinishesCellsLeftByAnEarlierBuild() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(200, f.write("roam_location", "AGREE", "roam-agree", "").get("code"));
            assertEquals(200, f.write("roam_location", "REVOKE", "roam-revoke", "roam-agree").get("code"));
            assertEquals(0, f.cells(ME));
            // A cell written before this build deployed (e.g. by the fog-map backfill) is still removed on replay.
            f.db.update("insert into roam_member_fog_cell values(?,'wtw3sjz','wtw3s',now(),now())", ME);
            assertEquals(200, f.write("roam_location", "REVOKE", "roam-revoke", "roam-agree").get("code"));
            assertEquals(0, f.cells(ME));
            assertEquals(2, f.consents(), "a replay writes no second ledger row");
            assertEquals(2, f.cells(OTHER));
        }
    }

    @Test
    void agreeingAgainDoesNotRestoreCellsAndHomeCityWithdrawalKeepsThem() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(200, f.write("roam_location", "AGREE", "roam-agree", "").get("code"));
            assertEquals(200, f.write("home_city_location", "AGREE", "home-agree", "").get("code"));
            assertEquals(200, f.write("home_city_location", "REVOKE", "home-revoke", "home-agree").get("code"));
            assertEquals(3, f.cells(ME), "home-city withdrawal is a different purpose");

            assertEquals(200, f.write("roam_location", "REVOKE", "roam-revoke", "roam-agree").get("code"));
            assertEquals(200, f.write("roam_location", "AGREE", "roam-again", "roam-revoke").get("code"));
            assertEquals(0, f.cells(ME));
        }
    }

    /** Real controller, services, Spring transaction and production mapper XMLs on H2; member 9 signed in. */
    private static final class Fixture implements AutoCloseable {
        final JdbcTemplate db;
        final TransactionTemplate tx;
        final ApiComplianceController controller = new ApiComplianceController() {
            @Override public Long getAppUserId() { return ME; }
        };

        Fixture() throws Exception {
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:roam_withdraw_"
                    + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            db = new JdbcTemplate(source);
            db.execute("create table ums_member(id bigint primary key)");
            db.execute("insert into ums_member values(9),(10)");
            db.execute("create table roam_session(id bigint primary key,member_id bigint,status int,presence_visible boolean)");
            db.execute("insert into roam_session values(1,9,0,true)");
            db.execute("create table consent_record(id bigint auto_increment primary key, member_id bigint,doc_type varchar(64),"
                    + "doc_version varchar(64),scene varchar(64),scope_type varchar(32),scope_id bigint,event_type varchar(16),"
                    + "occurred_at datetime,request_id varchar(64),ip_address varchar(128),create_time datetime,"
                    + "unique(member_id,request_id))");
            db.execute("create table roam_member_fog_cell(member_id bigint, cell_key varchar(12), cell_prefix5 char(5),"
                    + " first_revealed_at datetime, last_revealed_at datetime, primary key(member_id, cell_key))");
            for (String cell : new String[]{"wtw3sjq", "wtw3sjr", "wtw3sjw"})
                db.update("insert into roam_member_fog_cell values(?,?,'wtw3s',now(),now())", ME, cell);
            for (String cell : new String[]{"wtw3sjq", "wtw3sjr"})
                db.update("insert into roam_member_fog_cell values(?,?,'wtw3s',now(),now())", OTHER, cell);

            Configuration config = new Configuration(new Environment("roam-withdraw", new SpringManagedTransactionFactory(), source));
            config.getTypeAliasRegistry().registerAlias("ConsentRecord", ConsentRecord.class);
            config.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
            config.getTypeAliasRegistry().registerAlias("RoamSession", RoamSession.class);
            for (String xml : new String[]{"mapper/business/ConsentRecordMapper.xml", "mapper/business/RoamMapper.xml",
                    "mapper/business/RoamFogCellMapper.xml"}) {
                try (InputStream input = Resources.getResourceAsStream(xml)) {
                    new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
                }
            }
            SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            ConsentRecordMapper records = sessions.getMapper(ConsentRecordMapper.class);
            ComplianceProperties props = new ComplianceProperties();
            props.setDocVersions(Collections.singletonMap("privacy_policy", "v-test"));
            RoamLocationConsentService location = new RoamLocationConsentService(sessions.getMapper(RoamMapper.class),
                    records, props, sessions.getMapper(RoamFogCellMapper.class));
            ConsentRecordServiceImpl generic = new ConsentRecordServiceImpl();
            ReflectionTestUtils.setField(generic, "consentRecordMapper", records);
            ReflectionTestUtils.setField(controller, "consentRecordService", generic);
            ReflectionTestUtils.setField(controller, "roamLocationConsentService", location);
            ReflectionTestUtils.setField(controller, "complianceProperties", props);
            tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        }

        AjaxResult write(String scene, String event, String id, String expected) {
            Map<String, Object> body = new HashMap<>(Map.of("docType", "privacy_policy", "scene", scene,
                    "eventType", event, "requestId", id));
            if (expected != null) body.put("expectedRequestId", expected);
            return tx.execute(status -> controller.record(body, mock(HttpServletRequest.class)));
        }

        int cells(long member) {
            return db.queryForObject("select count(*) from roam_member_fog_cell where member_id=?", Integer.class, member);
        }

        int presence() {
            return db.queryForObject("select count(*) from roam_session where presence_visible=true", Integer.class);
        }

        int consents() {
            return db.queryForObject("select count(*) from consent_record where member_id=? and scene='roam_location'",
                    Integer.class, ME);
        }

        @Override public void close() {
            db.execute("drop all objects");
            db.execute("shutdown");
        }
    }
}
