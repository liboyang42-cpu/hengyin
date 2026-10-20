package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.common.config.ComplianceProperties;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.io.InputStream;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.web.controller.api.ApiComplianceController;
import com.chengyinhub.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F-L5C-01/12: 撤回漫游定位必须在服务端立刻停止对他人展示位置。 */
class RoamLocationRevokeHidesPresenceTest {

    @Test
    void homeRevokeRejectsAnAgreeThatWasStartedAgainstTheOldBaseline() throws Exception {
        try (LocationFixture f = new LocationFixture()) {
            assertEquals(200, f.write("home_city_location", "AGREE", "home-first", "").get("code"));
            assertEquals(200, f.write("home_city_location", "REVOKE", "home-revoke", "home-first").get("code"));
            assertEquals(409, f.write("home_city_location", "AGREE", "home-delayed", "home-first").get("code"));
            assertEquals("REVOKE", f.latest("home_city_location").getEventType());
            assertEquals(2, f.rows());
        }
    }

    @Test
    void homeRevokeRejectsReplayOfAnAlreadyCommittedAgreeReceipt() throws Exception {
        try (LocationFixture f = new LocationFixture()) {
            assertEquals(200, f.write("home_city_location", "AGREE", "home-first", "").get("code"));
            assertEquals(200, f.write("home_city_location", "REVOKE", "home-revoke", "home-first").get("code"));
            assertEquals(409, f.write("home_city_location", "AGREE", "home-first", "").get("code"));
            assertEquals("REVOKE", f.latest("home_city_location").getEventType());
            assertEquals(2, f.rows());
        }
    }

    @Test
    void homeAgreementRequiresAnExplicitBaselineEvenForTheFirstConsent() throws Exception {
        try (LocationFixture f = new LocationFixture()) {
            assertEquals(409, f.write("home_city_location", "AGREE", "missing-baseline", null).get("code"));
            assertEquals(0, f.rows());
        }
    }

    @Test
    void purposeBaselinesAreIndependentAndHomeRevocationDoesNotCloseRoamPresence() throws Exception {
        try (LocationFixture f = new LocationFixture()) {
            assertEquals(200, f.write("roam_location", "AGREE", "roam-first", "").get("code"));
            assertEquals(200, f.write("home_city_location", "AGREE", "home-first", "").get("code"));
            assertEquals(200, f.write("home_city_location", "REVOKE", "home-revoke", "home-first").get("code"));
            assertEquals("AGREE", f.latest("roam_location").getEventType());
            assertEquals(1, f.presence());
            assertEquals(409, f.write("home_city_location", "AGREE", "cross-baseline", "roam-first").get("code"));
            assertEquals(200, f.write("home_city_location", "AGREE", "home-fresh", "home-revoke").get("code"));
            assertEquals(200, f.write("home_city_location", "AGREE", "home-fresh", "home-revoke").get("code"));
            assertEquals(4, f.rows());
            assertEquals(200, f.write("roam_location", "REVOKE", "roam-revoke", "roam-first").get("code"));
            assertEquals(0, f.presence());
            assertEquals("AGREE", f.latest("home_city_location").getEventType());
        }
    }

    @Test
    void aRequestIdCannotBeReusedBetweenTheTwoLocationPurposes() throws Exception {
        try (LocationFixture f = new LocationFixture()) {
            assertEquals(200, f.write("roam_location", "AGREE", "shared-id", "").get("code"));
            assertEquals(500, f.write("home_city_location", "AGREE", "shared-id", "").get("code"));
            assertEquals(1, f.rows());
        }
    }

    @Test
    void oldDocumentRevisionRemainsAValidCasBaselineButCannotGrantCurrentPermission() throws Exception {
        try (LocationFixture f = new LocationFixture()) {
            assertEquals(200, f.write("home_city_location", "AGREE", "old-version", "").get("code"));
            f.props.setDocVersions(Collections.singletonMap("privacy_policy", "v-new"));
            assertEquals(false, f.generic.hasCurrentAgreement(9L, "privacy_policy", "home_city_location", "v-new"));
            assertEquals(409, f.write("home_city_location", "AGREE", "old-version", "").get("code"));
            AjaxResult refreshed = f.write("home_city_location", "AGREE", "current-version", "old-version");
            assertEquals(200, refreshed.get("code"));
            assertEquals("v-new", ((Map<?, ?>) refreshed.get("data")).get("docVersion"));
            assertEquals(true, f.generic.hasCurrentAgreement(9L, "privacy_policy", "home_city_location", "v-new"));
            assertEquals(2, f.rows());
        }
    }

    /** Real service, Spring transaction and both production mapper XMLs; only isolated H2 fixture rows. */
    private static final class LocationFixture implements AutoCloseable {
        final JdbcTemplate db;
        final ConsentRecordServiceImpl generic = new ConsentRecordServiceImpl();
        final ComplianceProperties props = new ComplianceProperties();
        final ApiComplianceController controller = new ApiComplianceController() {
            @Override public Long getAppUserId() { return 9L; }
        };
        final TransactionTemplate tx;
        LocationFixture() throws Exception {
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:location_cas_"
                    + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            db = new JdbcTemplate(source);
            db.execute("create table ums_member(id bigint primary key)");
            db.execute("insert into ums_member values(9)");
            db.execute("create table roam_session(id bigint primary key,member_id bigint,status int,presence_visible boolean)");
            db.execute("insert into roam_session values(1,9,0,true)");
            db.execute("create table consent_record(id bigint auto_increment primary key, member_id bigint,doc_type varchar(64),"
                    + "doc_version varchar(64),scene varchar(64),scope_type varchar(32),scope_id bigint,event_type varchar(16),"
                    + "occurred_at datetime,request_id varchar(64),ip_address varchar(128),create_time datetime,"
                    + "unique(member_id,request_id))");
            Configuration config = new Configuration(new Environment("location-cas", new SpringManagedTransactionFactory(), source));
            config.getTypeAliasRegistry().registerAlias("ConsentRecord", ConsentRecord.class);
            config.getTypeAliasRegistry().registerAlias("RoamPoi", RoamPoi.class);
            config.getTypeAliasRegistry().registerAlias("RoamSession", RoamSession.class);
            for (String xml : new String[]{"mapper/business/ConsentRecordMapper.xml", "mapper/business/RoamMapper.xml"}) {
                try (InputStream input = Resources.getResourceAsStream(xml)) {
                    new XMLMapperBuilder(input, config, xml, config.getSqlFragments()).parse();
                }
            }
            SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
            ConsentRecordMapper records = sessions.getMapper(ConsentRecordMapper.class);
            props.setDocVersions(Collections.singletonMap("privacy_policy", "v-test"));
            RoamLocationConsentService location = new RoamLocationConsentService(sessions.getMapper(RoamMapper.class), records, props);
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
        ConsentRecord latest(String scene) { return generic.latest(9L, "privacy_policy", scene); }
        int rows() { return db.queryForObject("select count(*) from consent_record", Integer.class); }
        int presence() { return db.queryForObject("select count(*) from roam_session where presence_visible=true", Integer.class); }
        @Override public void close() { db.execute("drop all objects"); db.execute("shutdown"); }
    }

    private RoamLocationConsentService service(RoamMapper roam, ConsentRecordMapper records) {
        ComplianceProperties properties = new ComplianceProperties();
        properties.setDocVersions(Collections.singletonMap("privacy_policy", "v-test"));
        when(roam.lockClaimOwner(9L)).thenReturn(9L);
        when(records.insertConsentRecord(any())).thenReturn(1);
        return new RoamLocationConsentService(roam, records, properties);
    }

    @Test
    void revokeClosesNearbySharingOnEveryOpenSessionInTheSameTransaction() {
        RoamMapper roam = mock(RoamMapper.class);
        ConsentRecordMapper records = mock(ConsentRecordMapper.class);
        RoamLocationConsentService consent = service(roam, records);
        ConsentRecord receipt = new ConsentRecord();
        receipt.setEventType("REVOKE");
        // 第一次查(幂等判定)为空,写入后回读得到刚写的行
        when(records.selectByRequestId(9L, "revoke-1")).thenReturn(null, receipt);

        assertEquals("REVOKE", consent.record(9L, "REVOKE", "revoke-1", null, "").getEventType());
        verify(roam).updatePresenceVisibility(isNull(), eq(9L), eq(false));
    }

    @Test
    void agreeNeverOpensSharingByItself() {
        RoamMapper roam = mock(RoamMapper.class);
        ConsentRecordMapper records = mock(ConsentRecordMapper.class);
        RoamLocationConsentService consent = service(roam, records);
        ConsentRecord receipt = new ConsentRecord();
        receipt.setEventType("AGREE");
        when(records.selectByRequestId(9L, "agree-1")).thenReturn(null, receipt);

        consent.record(9L, "AGREE", "agree-1", "", "");
        verify(roam, never()).updatePresenceVisibility(any(), anyLong(), anyBoolean());
    }
}
